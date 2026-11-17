package db

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/SwayKh/Motion/internal/timeline"

	"github.com/google/uuid"
)

var (
	// ErrTimelineProjectNotFound means the project does not exist or is not
	// owned by the requesting user.
	ErrTimelineProjectNotFound = errors.New("timeline project not found")
	// ErrTimelineRevisionConflict means the client tried to save a stale
	// revision, so another editor changed the project first.
	ErrTimelineRevisionConflict = errors.New("timeline revision conflict")
)

// TimelineProjectStore persists multitrack timeline projects.
type TimelineProjectStore struct {
	db *sql.DB
}

func NewTimelineProjectStore(db *sql.DB) *TimelineProjectStore {
	return &TimelineProjectStore{db: db}
}

func (s *TimelineProjectStore) Create(project *timeline.Project) error {
	if project.ID == "" {
		project.ID = uuid.NewString()
	}
	project.Revision = 1
	now := time.Now()
	project.CreatedAt = now
	project.UpdatedAt = now

	payload, err := json.Marshal(project)
	if err != nil {
		return fmt.Errorf("marshaling timeline project %s: %w", project.ID, err)
	}
	if _, err := s.db.Exec(`
		INSERT INTO timeline_projects (id, user_id, name, project, revision, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)
	`, project.ID, project.UserID, project.Name, string(payload), project.Revision, project.CreatedAt, project.UpdatedAt); err != nil {
		return fmt.Errorf("creating timeline project %s: %w", project.ID, err)
	}
	return nil
}

// Update saves the project only when the caller's revision still matches the
// stored one. On success the revision is bumped and the in-memory project is
// updated to the new revision.
func (s *TimelineProjectStore) Update(project *timeline.Project) error {
	expected := project.Revision
	if expected <= 0 {
		expected = 1
	}
	next := expected + 1
	now := time.Now()

	payload, err := json.Marshal(project)
	if err != nil {
		return fmt.Errorf("marshaling timeline project %s: %w", project.ID, err)
	}

	result, err := s.db.Exec(`
		UPDATE timeline_projects
		SET name = ?, project = ?, revision = ?, updated_at = ?
		WHERE id = ? AND user_id = ? AND revision = ?
	`, project.Name, string(payload), next, now, project.ID, project.UserID, expected)
	if err != nil {
		return fmt.Errorf("updating timeline project %s: %w", project.ID, err)
	}
	if n, _ := result.RowsAffected(); n == 0 {
		// Distinguish a missing project from a stale revision so the UI can
		// tell the user which recovery to offer.
		var exists int
		if err := s.db.QueryRow(
			`SELECT COUNT(1) FROM timeline_projects WHERE id = ? AND user_id = ?`,
			project.ID, project.UserID,
		).Scan(&exists); err != nil {
			return fmt.Errorf("checking timeline project %s: %w", project.ID, err)
		}
		if exists == 0 {
			return ErrTimelineProjectNotFound
		}
		return ErrTimelineRevisionConflict
	}

	project.Revision = next
	project.UpdatedAt = now
	return nil
}

func (s *TimelineProjectStore) Get(id, userID string) (*timeline.Project, error) {
	row := s.db.QueryRow(`
		SELECT id, user_id, name, project, revision, created_at, updated_at
		FROM timeline_projects
		WHERE id = ? AND user_id = ?
	`, id, userID)
	return scanTimelineProject(row)
}

func (s *TimelineProjectStore) List(userID string) ([]timeline.Project, error) {
	rows, err := s.db.Query(`
		SELECT id, user_id, name, project, revision, created_at, updated_at
		FROM timeline_projects
		WHERE user_id = ?
		ORDER BY updated_at DESC
	`, userID)
	if err != nil {
		return nil, fmt.Errorf("listing timeline projects: %w", err)
	}
	defer rows.Close()

	projects := make([]timeline.Project, 0)
	for rows.Next() {
		project, err := scanTimelineProject(rows)
		if err != nil {
			return nil, err
		}
		projects = append(projects, *project)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return projects, nil
}

func (s *TimelineProjectStore) Delete(id, userID string) error {
	result, err := s.db.Exec(`DELETE FROM timeline_projects WHERE id = ? AND user_id = ?`, id, userID)
	if err != nil {
		return fmt.Errorf("deleting timeline project %s: %w", id, err)
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return ErrTimelineProjectNotFound
	}
	return nil
}

func scanTimelineProject(row rowScanner) (*timeline.Project, error) {
	var (
		id        string
		userID    string
		name      string
		payload   string
		revision  int
		createdAt time.Time
		updatedAt time.Time
	)
	err := row.Scan(&id, &userID, &name, &payload, &revision, &createdAt, &updatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrTimelineProjectNotFound
	}
	if err != nil {
		return nil, err
	}

	var project timeline.Project
	if err := json.Unmarshal([]byte(payload), &project); err != nil {
		return nil, fmt.Errorf("decoding timeline project %s: %w", id, err)
	}
	project.ID = id
	project.UserID = userID
	project.Name = name
	project.Revision = revision
	project.CreatedAt = createdAt
	project.UpdatedAt = updatedAt
	return &project, nil
}
