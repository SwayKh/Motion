package db

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/SwayKh/Motion/internal/models"

	"github.com/google/uuid"
)

var ErrEditProjectNotFound = errors.New("edit project not found")

// EditProjectStore persists reusable editing sessions.
type EditProjectStore struct {
	db *sql.DB
}

func NewEditProjectStore(db *sql.DB) *EditProjectStore {
	return &EditProjectStore{db: db}
}

func (s *EditProjectStore) Create(project *models.EditProject) error {
	if project.ID == "" {
		project.ID = uuid.NewString()
	}
	request, err := json.Marshal(project.Request)
	if err != nil {
		return fmt.Errorf("marshaling edit project %s: %w", project.ID, err)
	}
	now := time.Now()
	project.CreatedAt = now
	project.UpdatedAt = now
	if _, err := s.db.Exec(`
		INSERT INTO edit_projects (id, user_id, video_id, name, request, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)
	`, project.ID, project.UserID, project.VideoID, project.Name, string(request), project.CreatedAt, project.UpdatedAt); err != nil {
		return fmt.Errorf("creating edit project %s: %w", project.ID, err)
	}
	return nil
}

func (s *EditProjectStore) Update(project *models.EditProject) error {
	request, err := json.Marshal(project.Request)
	if err != nil {
		return fmt.Errorf("marshaling edit project %s: %w", project.ID, err)
	}
	project.UpdatedAt = time.Now()
	result, err := s.db.Exec(`
		UPDATE edit_projects
		SET name = ?, request = ?, updated_at = ?
		WHERE id = ? AND user_id = ?
	`, project.Name, string(request), project.UpdatedAt, project.ID, project.UserID)
	if err != nil {
		return fmt.Errorf("updating edit project %s: %w", project.ID, err)
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return ErrEditProjectNotFound
	}
	return nil
}

func (s *EditProjectStore) Get(id, userID string) (*models.EditProject, error) {
	row := s.db.QueryRow(`
		SELECT id, user_id, video_id, name, request, created_at, updated_at
		FROM edit_projects
		WHERE id = ? AND user_id = ?
	`, id, userID)
	return scanEditProject(row)
}

func (s *EditProjectStore) List(userID string) ([]models.EditProject, error) {
	rows, err := s.db.Query(`
		SELECT id, user_id, video_id, name, request, created_at, updated_at
		FROM edit_projects
		WHERE user_id = ?
		ORDER BY updated_at DESC
	`, userID)
	if err != nil {
		return nil, fmt.Errorf("listing edit projects: %w", err)
	}
	defer rows.Close()

	projects := make([]models.EditProject, 0)
	for rows.Next() {
		project, err := scanEditProject(rows)
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

func (s *EditProjectStore) Delete(id, userID string) error {
	result, err := s.db.Exec(`DELETE FROM edit_projects WHERE id = ? AND user_id = ?`, id, userID)
	if err != nil {
		return fmt.Errorf("deleting edit project %s: %w", id, err)
	}
	if n, _ := result.RowsAffected(); n == 0 {
		return ErrEditProjectNotFound
	}
	return nil
}

func scanEditProject(row rowScanner) (*models.EditProject, error) {
	var (
		project   models.EditProject
		request   string
		createdAt time.Time
		updatedAt time.Time
	)
	err := row.Scan(&project.ID, &project.UserID, &project.VideoID, &project.Name, &request, &createdAt, &updatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrEditProjectNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal([]byte(request), &project.Request); err != nil {
		return nil, fmt.Errorf("decoding edit project %s: %w", project.ID, err)
	}
	project.CreatedAt = createdAt
	project.UpdatedAt = updatedAt
	return &project, nil
}
