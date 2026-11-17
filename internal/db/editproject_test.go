package db

import (
	"errors"
	"testing"

	"github.com/SwayKh/Motion/internal/ffmpeg"
	"github.com/SwayKh/Motion/internal/models"
)

func TestEditProjectCRUD(t *testing.T) {
	database := openTestDB(t)
	insertTestUser(t, database, "owner")
	insertTestUser(t, database, "other")
	videos := NewVideoStore(database)
	createTestVideo(t, videos, "video-1")

	store := NewEditProjectStore(database)
	project := &models.EditProject{
		UserID:  "owner",
		VideoID: "video-1",
		Name:    "First cut",
		Request: models.ProcessRequest{Operations: []models.Operation{
			{Type: "flip", Direction: ffmpeg.Horizontal},
		}},
	}
	if err := store.Create(project); err != nil {
		t.Fatalf("create project: %v", err)
	}
	if project.ID == "" {
		t.Fatal("project id was not assigned")
	}

	got, err := store.Get(project.ID, "owner")
	if err != nil {
		t.Fatalf("get project: %v", err)
	}
	if len(got.Request.Operations) != 1 || got.Request.Operations[0].Type != "flip" {
		t.Fatalf("unexpected operations: %+v", got.Request.Operations)
	}
	if got.Name != "First cut" {
		t.Fatalf("name = %q, want First cut", got.Name)
	}

	if _, err := store.Get(project.ID, "other"); !errors.Is(err, ErrEditProjectNotFound) {
		t.Fatalf("cross-user get error = %v, want ErrEditProjectNotFound", err)
	}

	list, err := store.List("owner")
	if err != nil {
		t.Fatalf("list projects: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("list length = %d, want 1", len(list))
	}
	otherList, err := store.List("other")
	if err != nil {
		t.Fatalf("list other projects: %v", err)
	}
	if len(otherList) != 0 {
		t.Fatalf("other user saw %d projects, want 0", len(otherList))
	}

	project.Name = "Second cut"
	project.Request.Operations = []models.Operation{{Type: "speed", Factor: 1.5}}
	if err := store.Update(project); err != nil {
		t.Fatalf("update project: %v", err)
	}
	got, err = store.Get(project.ID, "owner")
	if err != nil {
		t.Fatalf("reload project: %v", err)
	}
	if got.Name != "Second cut" || got.Request.Operations[0].Type != "speed" {
		t.Fatalf("update not persisted: %+v", got)
	}

	if err := store.Delete(project.ID, "other"); !errors.Is(err, ErrEditProjectNotFound) {
		t.Fatalf("cross-user delete error = %v, want ErrEditProjectNotFound", err)
	}
	if err := store.Delete(project.ID, "owner"); err != nil {
		t.Fatalf("delete project: %v", err)
	}
	if _, err := store.Get(project.ID, "owner"); !errors.Is(err, ErrEditProjectNotFound) {
		t.Fatalf("deleted project error = %v, want ErrEditProjectNotFound", err)
	}
}
