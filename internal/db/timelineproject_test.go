package db

import (
	"errors"
	"testing"

	"github.com/SwayKh/Motion/internal/timeline"
)

func testTimelineProject(userID, name string) *timeline.Project {
	return &timeline.Project{
		UserID:        userID,
		Name:          name,
		SchemaVersion: timeline.CurrentSchemaVersion,
		Tracks: []timeline.Track{
			{
				ID:      "v1",
				Type:    timeline.TrackVideo,
				Order:   0,
				Visible: true,
				Clips: []timeline.Clip{
					{
						ID: "c1", Kind: timeline.KindVideo, AssetID: "asset-1",
						SourceIn: 1, SourceOut: 3, TimelineStart: 0,
						Volume: 1, Opacity: 1, Scale: 1,
					},
				},
			},
		},
	}
}

func TestTimelineProjectCRUD(t *testing.T) {
	database := openTestDB(t)
	insertTestUser(t, database, "owner")
	insertTestUser(t, database, "other")

	store := NewTimelineProjectStore(database)
	project := testTimelineProject("owner", "First cut")
	if err := store.Create(project); err != nil {
		t.Fatalf("create timeline project: %v", err)
	}
	if project.ID == "" {
		t.Fatal("project id was not assigned")
	}
	if project.Revision != 1 {
		t.Fatalf("revision = %d, want 1", project.Revision)
	}

	got, err := store.Get(project.ID, "owner")
	if err != nil {
		t.Fatalf("get timeline project: %v", err)
	}
	if got.Name != "First cut" || len(got.Tracks) != 1 || len(got.Tracks[0].Clips) != 1 {
		t.Fatalf("loaded project = %+v", got)
	}
	if got.Revision != 1 {
		t.Fatalf("loaded revision = %d, want 1", got.Revision)
	}

	if _, err := store.Get(project.ID, "other"); !errors.Is(err, ErrTimelineProjectNotFound) {
		t.Fatalf("cross-user get error = %v, want ErrTimelineProjectNotFound", err)
	}

	list, err := store.List("owner")
	if err != nil {
		t.Fatalf("list timeline projects: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("list length = %d, want 1", len(list))
	}
	otherList, err := store.List("other")
	if err != nil {
		t.Fatalf("list other timeline projects: %v", err)
	}
	if len(otherList) != 0 {
		t.Fatalf("other user saw %d projects, want 0", len(otherList))
	}

	// Update with the current revision bumps it.
	project.Name = "Second cut"
	if err := store.Update(project); err != nil {
		t.Fatalf("update timeline project: %v", err)
	}
	if project.Revision != 2 {
		t.Fatalf("revision after update = %d, want 2", project.Revision)
	}

	// A stale revision is rejected without touching the stored row.
	stale := testTimelineProject("owner", "Stale")
	stale.ID = project.ID
	stale.Revision = 1
	if err := store.Update(stale); !errors.Is(err, ErrTimelineRevisionConflict) {
		t.Fatalf("stale update error = %v, want ErrTimelineRevisionConflict", err)
	}
	got, err = store.Get(project.ID, "owner")
	if err != nil {
		t.Fatalf("reload after stale update: %v", err)
	}
	if got.Name != "Second cut" {
		t.Fatalf("stale update changed the row: name = %q", got.Name)
	}

	// Updating a missing project is reported distinctly.
	missing := testTimelineProject("owner", "Missing")
	missing.ID = "does-not-exist"
	missing.Revision = 1
	if err := store.Update(missing); !errors.Is(err, ErrTimelineProjectNotFound) {
		t.Fatalf("missing update error = %v, want ErrTimelineProjectNotFound", err)
	}

	if err := store.Delete(project.ID, "other"); !errors.Is(err, ErrTimelineProjectNotFound) {
		t.Fatalf("cross-user delete error = %v, want ErrTimelineProjectNotFound", err)
	}
	if err := store.Delete(project.ID, "owner"); err != nil {
		t.Fatalf("delete timeline project: %v", err)
	}
	if _, err := store.Get(project.ID, "owner"); !errors.Is(err, ErrTimelineProjectNotFound) {
		t.Fatalf("deleted project error = %v, want ErrTimelineProjectNotFound", err)
	}
}
