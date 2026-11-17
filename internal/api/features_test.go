package api

import (
	"errors"
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/job"
	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/storage"
)

// newTestEnvWithConfig builds a full API test environment with an explicit
// configuration, so tests can exercise quotas, encryption and email
// verification without touching the process environment.
func newTestEnvWithConfig(t *testing.T, mutate func(*config.Config)) *testEnv {
	t.Helper()

	logger.Init()

	dir := t.TempDir()
	t.Chdir(dir)

	for _, sub := range []string{"uploads", "outputs", "tmp"} {
		if err := os.MkdirAll(sub, 0o755); err != nil {
			t.Fatalf("create %s dir: %v", sub, err)
		}
	}

	database, err := db.Open(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("open test db: %v", err)
	}

	cfg := config.Load()
	cfg.Env = "test"
	cfg.SecureCookies = false
	cfg.DataDir = dir
	cfg.UploadDir = "uploads"
	cfg.OutputDir = "outputs"
	cfg.TempDir = "tmp"
	cfg.DevEmailOutbox = false
	if mutate != nil {
		mutate(cfg)
	}

	st, err := storage.New(cfg)
	if err != nil {
		t.Fatalf("new storage: %v", err)
	}
	if err := st.EnsureDirs(); err != nil {
		t.Fatalf("ensure dirs: %v", err)
	}

	store := db.NewVideoStore(database)
	jobs := db.NewJobStore(database)
	manager := job.NewManager(jobs, store, st, 2)
	server := httptest.NewServer(NewRouterWithConfig(database, manager, "", cfg, st))

	t.Cleanup(func() {
		server.Close()
		manager.Stop()
		database.Close()
	})

	env := &testEnv{
		dir:     dir,
		db:      database,
		store:   store,
		jobs:    jobs,
		manager: manager,
		server:  server,
		cfg:     cfg,
		st:      st,
	}
	globalTestClient = nil
	return env
}

// registerUser creates an account through the public API and installs the
// resulting authenticated client as the global test client.
func registerUser(t *testing.T, e *testEnv, email, password string) *http.Client {
	t.Helper()

	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar}

	body := fmt.Sprintf(`{"email":%q,"password":%q,"passwordConfirm":%q}`, email, password, password)
	req, err := http.NewRequest(http.MethodPost, e.url("/api/auth/register"), strings.NewReader(body))
	if err != nil {
		t.Fatalf("create register request: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("register status = %d, want 201", resp.StatusCode)
	}

	// Establish the CSRF cookie for subsequent state-changing requests.
	req, _ = http.NewRequest(http.MethodGet, e.url("/api/auth/csrf"), nil)
	resp, err = client.Do(req)
	if err != nil {
		t.Fatalf("csrf: %v", err)
	}
	resp.Body.Close()

	e.client = client
	globalTestClient = client
	return client
}

// readOutboxToken extracts the most recent verification token from the dev
// outbox file.
func readOutboxToken(t *testing.T, path string) string {
	t.Helper()

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read dev outbox: %v", err)
	}
	idx := strings.LastIndex(string(data), "token=")
	if idx == -1 {
		t.Fatalf("no verification token in outbox:\n%s", data)
	}
	rest := string(data)[idx+len("token="):]
	end := strings.IndexAny(rest, " \n\r\t")
	if end == -1 {
		end = len(rest)
	}
	token := rest[:end]
	if token == "" {
		t.Fatalf("empty verification token in outbox:\n%s", data)
	}
	return token
}

func TestDeleteVideoRemovesFilesAndJobs(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	outputPath := filepath.Join("outputs", video.ID+"-out.mp4")
	if err := os.WriteFile(outputPath, []byte("processed output"), 0o644); err != nil {
		t.Fatalf("write output: %v", err)
	}

	queued, err := env.jobs.CreateQueued(video.ID, `{"operations":[{"type":"flip","orientation":1}]}`)
	if err != nil {
		t.Fatalf("create job: %v", err)
	}
	if _, err := env.jobs.ClaimNext(); err != nil {
		t.Fatalf("claim job: %v", err)
	}
	if _, _, err := env.jobs.CompleteWithOutput(queued.ID, outputPath, false, ""); err != nil {
		t.Fatalf("complete job: %v", err)
	}

	response, body := doRequest(t, http.MethodDelete, env.url("/videos/"+video.ID), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("delete status = %d, body=%s", response.StatusCode, body)
	}

	if _, err := os.Stat(video.OriginalPath); !os.IsNotExist(err) {
		t.Fatalf("original file still exists: %v", err)
	}
	if _, err := os.Stat(outputPath); !os.IsNotExist(err) {
		t.Fatalf("output file still exists: %v", err)
	}
	if _, err := env.jobs.Get(queued.ID); !errors.Is(err, db.ErrJobNotFound) {
		t.Fatalf("job still exists after video delete: %v", err)
	}

	response, _ = doRequest(t, http.MethodGet, env.url("/videos/"+video.ID), "", "")
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("get deleted video = %d, want 404", response.StatusCode)
	}
}

func TestDeleteVideoBlockedByActiveJob(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	if _, err := env.jobs.CreateQueued(video.ID, `{"operations":[{"type":"flip","orientation":1}]}`); err != nil {
		t.Fatalf("create job: %v", err)
	}

	response, body := doRequest(t, http.MethodDelete, env.url("/videos/"+video.ID), "", "")
	if response.StatusCode != http.StatusConflict {
		t.Fatalf("delete with active job = %d, body=%s, want 409", response.StatusCode, body)
	}
	if _, err := os.Stat(video.OriginalPath); err != nil {
		t.Fatalf("original file was removed despite active job: %v", err)
	}
}

func TestDeleteJobResetsVideo(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	outputPath := filepath.Join("outputs", video.ID+"-out.mp4")
	if err := os.WriteFile(outputPath, []byte("processed output"), 0o644); err != nil {
		t.Fatalf("write output: %v", err)
	}

	queued, err := env.jobs.CreateQueued(video.ID, `{"operations":[{"type":"flip","orientation":1}]}`)
	if err != nil {
		t.Fatalf("create job: %v", err)
	}
	if _, err := env.jobs.ClaimNext(); err != nil {
		t.Fatalf("claim job: %v", err)
	}
	if _, _, err := env.jobs.CompleteWithOutput(queued.ID, outputPath, false, ""); err != nil {
		t.Fatalf("complete job: %v", err)
	}

	response, body := doRequest(t, http.MethodDelete, env.url("/jobs/"+queued.ID), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("delete job status = %d, body=%s", response.StatusCode, body)
	}

	if _, err := os.Stat(outputPath); !os.IsNotExist(err) {
		t.Fatalf("job output still exists: %v", err)
	}

	reloaded, err := env.store.Get(video.ID)
	if err != nil {
		t.Fatalf("get video: %v", err)
	}
	if reloaded.OutputPath != "" || reloaded.Status != models.StatusUploaded {
		t.Fatalf("video state = %s/%q, want uploaded/empty", reloaded.Status, reloaded.OutputPath)
	}
}

func TestEditProjectAPI(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	createBody := fmt.Sprintf(
		`{"videoId":%q,"name":"First cut","request":{"operations":[{"type":"flip","orientation":1}]}}`,
		video.ID,
	)
	response, body := doRequest(t, http.MethodPost, env.url("/api/edit-projects"), createBody, "application/json")
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("create project = %d, body=%s, want 201", response.StatusCode, body)
	}
	created := decodeJSON[map[string]any](t, body)
	id, _ := created["id"].(string)
	if id == "" {
		t.Fatalf("created project has no id: %s", body)
	}
	if created["name"] != "First cut" || created["videoId"] != video.ID {
		t.Fatalf("unexpected created project: %s", body)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/api/edit-projects"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("list projects = %d, body=%s", response.StatusCode, body)
	}
	list := decodeJSON[[]map[string]any](t, body)
	if len(list) != 1 || list[0]["id"] != id {
		t.Fatalf("list projects = %s, want one entry %s", body, id)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/api/edit-projects/"+id), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("get project = %d, body=%s", response.StatusCode, body)
	}

	updateBody := `{"name":"Second cut","request":{"operations":[{"type":"speed","factor":1.5}]}}`
	response, body = doRequest(t, http.MethodPut, env.url("/api/edit-projects/"+id), updateBody, "application/json")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("update project = %d, body=%s", response.StatusCode, body)
	}
	updated := decodeJSON[map[string]any](t, body)
	if updated["name"] != "Second cut" {
		t.Fatalf("update did not persist name: %s", body)
	}
	request, _ := updated["request"].(map[string]any)
	operations, _ := request["operations"].([]any)
	if len(operations) != 1 {
		t.Fatalf("update did not persist operations: %s", body)
	}

	response, body = doRequest(t, http.MethodDelete, env.url("/api/edit-projects/"+id), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("delete project = %d, body=%s", response.StatusCode, body)
	}
	response, _ = doRequest(t, http.MethodGet, env.url("/api/edit-projects/"+id), "", "")
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("get deleted project = %d, want 404", response.StatusCode)
	}
}

func TestEditProjectRejectsUnknownVideo(t *testing.T) {
	env := newTestEnv(t)

	body := `{"videoId":"missing","name":"Nope","request":{"operations":[{"type":"flip","orientation":1}]}}`
	response, responseBody := doRequest(t, http.MethodPost, env.url("/api/edit-projects"), body, "application/json")
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("create project for unknown video = %d, body=%s, want 404", response.StatusCode, responseBody)
	}
}

func TestEmailVerificationFlow(t *testing.T) {
	env := newTestEnvWithConfig(t, func(cfg *config.Config) {
		cfg.RequireEmailVerification = true
		cfg.DevEmailOutbox = true
	})

	registerUser(t, env, "verify@example.com", "password123")

	// The frontend learns that verification is required from /api/config.
	response, body := doRequest(t, http.MethodGet, env.url("/api/config"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("config = %d, body=%s", response.StatusCode, body)
	}
	if cfg := decodeJSON[map[string]any](t, body); cfg["requireEmailVerification"] != true {
		t.Fatalf("config requireEmailVerification = %v, want true", cfg["requireEmailVerification"])
	}

	// An unverified account is refused by verification-gated routes.
	response, body = doRequest(t, http.MethodGet, env.url("/videos"), "", "")
	if response.StatusCode != http.StatusForbidden {
		t.Fatalf("unverified /videos = %d, body=%s, want 403", response.StatusCode, body)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/api/auth/me"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("me = %d, body=%s", response.StatusCode, body)
	}
	if verified := meEmailVerified(t, body); verified {
		t.Fatal("new account reported as verified")
	}

	token := readOutboxToken(t, filepath.Join(env.dir, "outbox.log"))

	response, body = doRequest(t, http.MethodPost, env.url("/api/auth/verify"), fmt.Sprintf(`{"token":%q}`, token), "application/json")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("verify = %d, body=%s", response.StatusCode, body)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/api/auth/me"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("me after verify = %d, body=%s", response.StatusCode, body)
	}
	if !meEmailVerified(t, body) {
		t.Fatal("account was not marked verified")
	}

	response, body = doRequest(t, http.MethodGet, env.url("/videos"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("verified /videos = %d, body=%s, want 200", response.StatusCode, body)
	}

	// A verification token is single use.
	response, _ = doRequest(t, http.MethodPost, env.url("/api/auth/verify"), fmt.Sprintf(`{"token":%q}`, token), "application/json")
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("replayed verify = %d, want 400", response.StatusCode)
	}
}

func meEmailVerified(t *testing.T, body []byte) bool {
	t.Helper()

	payload := decodeJSON[map[string]any](t, body)
	user, ok := payload["user"].(map[string]any)
	if !ok {
		t.Fatalf("me payload missing user: %s", body)
	}
	verified, _ := user["emailVerified"].(bool)
	return verified
}
