package api

import (
	"fmt"
	"net/http"
	"testing"

	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/timeline"
)

// timelineProjectBody builds the JSON object for a project with a single video
// clip on one video track.
func timelineProjectBody(name, assetID string, timelineStart, sourceIn, sourceOut float64) string {
	return fmt.Sprintf(`{
		"name": %q,
		"schemaVersion": 1,
		"tracks": [
			{
				"id": "v1", "type": "video", "order": 0, "visible": true,
				"clips": [
					{"id": "c1", "kind": "video", "assetId": %q,
					 "sourceIn": %g, "sourceOut": %g, "timelineStart": %g}
				]
			}
		]
	}`, name, assetID, sourceIn, sourceOut, timelineStart)
}

func TestTimelineProjectCRUDAPI(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	resp, body := doRequest(t, http.MethodPost, env.url("/api/timeline-projects"),
		timelineProjectBody("First cut", video.ID, 0, 1, 3), "application/json")
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create status = %d, body=%s", resp.StatusCode, body)
	}
	created := decodeJSON[timeline.Project](t, body)
	if created.ID == "" {
		t.Fatal("created project has no id")
	}
	if created.Revision != 1 {
		t.Fatalf("created revision = %d, want 1", created.Revision)
	}
	if created.Name != "First cut" {
		t.Fatalf("created name = %q", created.Name)
	}

	resp, body = doRequest(t, http.MethodGet, env.url("/api/timeline-projects"), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("list status = %d", resp.StatusCode)
	}
	list := decodeJSON[[]timeline.Project](t, body)
	if len(list) != 1 || list[0].ID != created.ID {
		t.Fatalf("list = %+v", list)
	}

	resp, body = doRequest(t, http.MethodGet, env.url("/api/timeline-projects/"+created.ID), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("get status = %d", resp.StatusCode)
	}

	// A matching revision updates the row and bumps the revision.
	updateBody := timelineProjectBody("Second cut", video.ID, 0, 1, 3)
	updateBody = updateBody[:len(updateBody)-1] + `, "revision": 1}`
	resp, body = doRequest(t, http.MethodPut, env.url("/api/timeline-projects/"+created.ID), updateBody, "application/json")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("update status = %d, body=%s", resp.StatusCode, body)
	}
	updated := decodeJSON[timeline.Project](t, body)
	if updated.Revision != 2 || updated.Name != "Second cut" {
		t.Fatalf("updated = revision %d name %q", updated.Revision, updated.Name)
	}

	// A stale revision is a conflict.
	resp, body = doRequest(t, http.MethodPut, env.url("/api/timeline-projects/"+created.ID), updateBody, "application/json")
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("stale update status = %d, body=%s", resp.StatusCode, body)
	}

	resp, body = doRequest(t, http.MethodDelete, env.url("/api/timeline-projects/"+created.ID), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("delete status = %d, body=%s", resp.StatusCode, body)
	}
	resp, _ = doRequest(t, http.MethodGet, env.url("/api/timeline-projects/"+created.ID), "", "")
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("get deleted status = %d, want 404", resp.StatusCode)
	}
}

func TestTimelineProjectOwnershipAPI(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)
	owner := globalTestClient

	resp, body := doRequest(t, http.MethodPost, env.url("/api/timeline-projects"),
		timelineProjectBody("Mine", video.ID, 0, 1, 3), "application/json")
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create status = %d, body=%s", resp.StatusCode, body)
	}
	created := decodeJSON[timeline.Project](t, body)

	// A second account must not see or render the first account's project.
	registerUser(t, env, "other-timeline@example.com", "password123")
	defer func() { globalTestClient = owner }()

	resp, _ = doRequest(t, http.MethodGet, env.url("/api/timeline-projects/"+created.ID), "", "")
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("cross-user get status = %d, want 404", resp.StatusCode)
	}

	// Rendering with the other account must fail the asset ownership check.
	resp, body = doRequest(t, http.MethodPost, env.url("/api/timeline/render"),
		fmt.Sprintf(`{"project": %s}`, timelineProjectBody("Mine", video.ID, 0, 1, 3)), "application/json")
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("cross-user render status = %d, body=%s", resp.StatusCode, body)
	}
}

func TestRenderTimelineQueues(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	resp, body := doRequest(t, http.MethodPost, env.url("/api/timeline/render"),
		fmt.Sprintf(`{"project": %s}`, timelineProjectBody("Render me", video.ID, 0, 1, 3)), "application/json")
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("render status = %d, body=%s", resp.StatusCode, body)
	}
	job := decodeJSON[jobResponse](t, body)
	if job.VideoID != video.ID {
		t.Fatalf("job videoId = %q, want %q", job.VideoID, video.ID)
	}
	if len(job.Operations) != 1 || job.Operations[0].Type != "slice" {
		t.Fatalf("job operations = %+v", job.Operations)
	}
	if job.Operations[0].Start != "1" || job.Operations[0].End != "3" {
		t.Fatalf("slice op = %+v", job.Operations[0])
	}
}

func TestRenderTimelineValidationErrors(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	// A gap before the first clip cannot be rendered yet.
	resp, body := doRequest(t, http.MethodPost, env.url("/api/timeline/render"),
		fmt.Sprintf(`{"project": %s}`, timelineProjectBody("Gap", video.ID, 2, 0, 3)), "application/json")
	if resp.StatusCode != http.StatusUnprocessableEntity {
		t.Fatalf("gap render status = %d, body=%s", resp.StatusCode, body)
	}

	// A missing asset is reported as a validation problem.
	resp, body = doRequest(t, http.MethodPost, env.url("/api/timeline/render"),
		fmt.Sprintf(`{"project": %s}`, timelineProjectBody("Ghost", "ghost-asset", 0, 0, 3)), "application/json")
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("missing asset render status = %d, body=%s", resp.StatusCode, body)
	}
	payload := decodeJSON[timelineValidationResponse](t, body)
	if len(payload.ValidationErrors) == 0 {
		t.Fatalf("expected validation errors, got %s", body)
	}
}

func TestTimelineProjectRejectsMissingName(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	resp, body := doRequest(t, http.MethodPost, env.url("/api/timeline-projects"),
		timelineProjectBody("   ", video.ID, 0, 0, 3), "application/json")
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("missing name status = %d, body=%s", resp.StatusCode, body)
	}
}
