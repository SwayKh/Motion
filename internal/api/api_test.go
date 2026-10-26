package api

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"VideoProject/internal/db"
	"VideoProject/internal/ffmpeg"
	"VideoProject/internal/job"
	"VideoProject/internal/logger"
	"VideoProject/internal/models"

	"github.com/google/uuid"
)

type testEnv struct {
	dir     string
	db      *sql.DB
	store   *db.VideoStore
	jobs    *db.JobStore
	manager *job.Manager
	server  *httptest.Server
}

func newTestEnv(t *testing.T) *testEnv {
	t.Helper()

	logger.Init()

	dir := t.TempDir()
	t.Chdir(dir)

	if err := os.MkdirAll("uploads", 0o755); err != nil {
		t.Fatalf("create uploads dir: %v", err)
	}
	if err := os.MkdirAll("outputs", 0o755); err != nil {
		t.Fatalf("create outputs dir: %v", err)
	}

	database, err := db.Open(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("open test db: %v", err)
	}

	store := db.NewVideoStore(database)
	jobs := db.NewJobStore(database)
	manager := job.NewManager(jobs, 2)
	server := httptest.NewServer(NewRouter(database, manager, ""))

	t.Cleanup(func() {
		server.Close()
		manager.Stop()
		database.Close()
	})

	return &testEnv{
		dir:     dir,
		db:      database,
		store:   store,
		jobs:    jobs,
		manager: manager,
		server:  server,
	}
}

func (e *testEnv) startManager(t *testing.T) {
	t.Helper()

	ctx, cancel := context.WithCancel(context.Background())
	e.manager.Start(ctx)
	t.Cleanup(func() {
		cancel()
		e.manager.Stop()
	})
}

func (e *testEnv) waitForJob(t *testing.T, id string) map[string]any {
	t.Helper()

	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		response, body := doRequest(t, http.MethodGet, e.url("/jobs/"+id), "", "")
		if response.StatusCode != http.StatusOK {
			t.Fatalf("job status = %d, body=%s", response.StatusCode, body)
		}
		payload := decodeJSON[map[string]any](t, body)
		status, _ := payload["status"].(string)
		if models.IsTerminalJobStatus(status) {
			return payload
		}
		time.Sleep(50 * time.Millisecond)
	}

	t.Fatalf("job %s did not finish before timeout", id)
	return nil
}

func (e *testEnv) url(path string) string {
	return e.server.URL + path
}

func (e *testEnv) seedVideo(t *testing.T, status string) *models.Video {
	t.Helper()

	id := uuid.NewString()
	original := []byte("test original file content")
	originalPath := filepath.Join("uploads", id+".mp4")

	if err := os.WriteFile(originalPath, original, 0o644); err != nil {
		t.Fatalf("write original: %v", err)
	}

	video := models.Video{
		ID:           id,
		OriginalPath: originalPath,
		OriginalName: "sample.mp4",
		FileSize:     int64(len(original)),
		Status:       models.StatusUploaded,
		Duration:     5,
		Width:        640,
		Height:       360,
	}

	if err := e.store.Create(&video); err != nil {
		t.Fatalf("create video: %v", err)
	}

	switch status {
	case models.StatusCompleted:
		outputPath := filepath.Join("outputs", id+".mp4")
		if err := os.WriteFile(outputPath, []byte("processed output"), 0o644); err != nil {
			t.Fatalf("write output: %v", err)
		}
		if err := e.store.Complete(id, outputPath); err != nil {
			t.Fatalf("complete video: %v", err)
		}
	case models.StatusFailed:
		if err := e.store.Fail(id, "seeded failure"); err != nil {
			t.Fatalf("fail video: %v", err)
		}
	case models.StatusQueued, models.StatusProcessing:
		if err := e.store.UpdateStatus(id, status); err != nil {
			t.Fatalf("update status: %v", err)
		}
	}

	got, err := e.store.Get(id)
	if err != nil {
		t.Fatalf("reload video: %v", err)
	}
	return got
}

func doRequest(t *testing.T, method, url, body, contentType string) (*http.Response, []byte) {
	t.Helper()

	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}

	request, err := http.NewRequest(method, url, reader)
	if err != nil {
		t.Fatalf("create request: %v", err)
	}
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}

	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("do request: %v", err)
	}
	defer response.Body.Close()

	data, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}

	return response, data
}

func postMultipart(t *testing.T, url, field, filename string, content []byte) (*http.Response, []byte) {
	t.Helper()

	var buffer bytes.Buffer
	writer := multipart.NewWriter(&buffer)

	part, err := writer.CreateFormFile(field, filename)
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write(content); err != nil {
		t.Fatalf("write form file: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close form writer: %v", err)
	}

	request, err := http.NewRequest(http.MethodPost, url, &buffer)
	if err != nil {
		t.Fatalf("create upload request: %v", err)
	}
	request.Header.Set("Content-Type", writer.FormDataContentType())

	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("upload request: %v", err)
	}
	defer response.Body.Close()

	data, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read upload response: %v", err)
	}

	return response, data
}

func decodeJSON[T any](t *testing.T, data []byte) T {
	t.Helper()

	var value T
	if err := json.Unmarshal(data, &value); err != nil {
		t.Fatalf("decode json %q: %v", string(data), err)
	}
	return value
}

func requireVideoTools(t *testing.T) {
	t.Helper()

	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg is not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe is not installed")
	}
}

func generateTestVideo(t *testing.T, path string) {
	t.Helper()
	requireVideoTools(t)

	command := exec.Command(
		"ffmpeg",
		"-hide_banner",
		"-loglevel", "error",
		"-y",
		"-f", "lavfi",
		"-i", "testsrc2=size=320x240:rate=24:duration=2",
		"-f", "lavfi",
		"-i", "sine=frequency=440:sample_rate=44100:duration=2",
		"-shortest",
		"-c:v", "libx264",
		"-preset", "ultrafast",
		"-pix_fmt", "yuv420p",
		"-c:a", "aac",
		path,
	)

	if output, err := command.CombinedOutput(); err != nil {
		t.Skipf("could not generate test video with ffmpeg: %v\n%s", err, output)
	}
}

func generateTestVideoWithoutAudio(t *testing.T, path string) {
	t.Helper()
	requireVideoTools(t)

	command := exec.Command(
		"ffmpeg",
		"-hide_banner",
		"-loglevel", "error",
		"-y",
		"-f", "lavfi",
		"-i", "testsrc2=size=320x240:rate=24:duration=2",
		"-c:v", "libx264",
		"-preset", "ultrafast",
		"-pix_fmt", "yuv420p",
		path,
	)

	if output, err := command.CombinedOutput(); err != nil {
		t.Skipf("could not generate silent test video with ffmpeg: %v\n%s", err, output)
	}
}

func TestHealthAndListVideos(t *testing.T) {
	env := newTestEnv(t)

	response, body := doRequest(t, http.MethodGet, env.url("/health"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("health status = %d, body=%s", response.StatusCode, body)
	}
	health := decodeJSON[map[string]string](t, body)
	if health["status"] != "ok" {
		t.Fatalf("health payload = %v", health)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/videos"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("list status = %d, body=%s", response.StatusCode, body)
	}
	videos := decodeJSON[[]map[string]any](t, body)
	if len(videos) != 0 {
		t.Fatalf("expected empty library, got %d entries", len(videos))
	}

	uploaded := env.seedVideo(t, models.StatusUploaded)
	completed := env.seedVideo(t, models.StatusCompleted)

	response, body = doRequest(t, http.MethodGet, env.url("/videos"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("list status = %d, body=%s", response.StatusCode, body)
	}
	videos = decodeJSON[[]map[string]any](t, body)
	if len(videos) != 2 {
		t.Fatalf("expected 2 videos, got %d", len(videos))
	}

	raw := strings.ToLower(string(body))
	for _, forbidden := range []string{"originalpath", "outputpath", "original_path", "output_path", "uploads/", "outputs/"} {
		if strings.Contains(raw, forbidden) {
			t.Fatalf("list response exposed internal value %q: %s", forbidden, body)
		}
	}
	if strings.Contains(raw, strings.ToLower(env.dir)) {
		t.Fatalf("list response exposed temp directory: %s", body)
	}

	foundCompleted := false
	for _, item := range videos {
		id, _ := item["id"].(string)
		if id == "" {
			t.Fatalf("video missing id: %v", item)
		}
		if _, ok := item["name"].(string); !ok {
			t.Fatalf("video missing name: %v", item)
		}
		if id == completed.ID {
			foundCompleted = true
			if item["status"] != models.StatusCompleted {
				t.Fatalf("completed video status = %v", item["status"])
			}
			if item["hasOutput"] != true {
				t.Fatalf("completed video should have output: %v", item)
			}
		}
		if id == uploaded.ID && item["hasOutput"] != false {
			t.Fatalf("uploaded video should not have output: %v", item)
		}
	}
	if !foundCompleted {
		t.Fatal("completed seeded video missing from list")
	}

	response, body = doRequest(t, http.MethodGet, env.url("/videos/"+uploaded.ID), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("get status = %d, body=%s", response.StatusCode, body)
	}
	single := decodeJSON[map[string]any](t, body)
	if single["id"] != uploaded.ID {
		t.Fatalf("get returned wrong video: %v", single)
	}
	if strings.Contains(strings.ToLower(string(body)), "path") {
		t.Fatalf("single video response exposed a path: %s", body)
	}
}

func TestUploadValidInvalidAndServeOriginal(t *testing.T) {
	requireVideoTools(t)
	env := newTestEnv(t)

	sourcePath := filepath.Join(t.TempDir(), "input.mp4")
	generateTestVideo(t, sourcePath)
	source, err := os.ReadFile(sourcePath)
	if err != nil {
		t.Fatalf("read source: %v", err)
	}

	response, body := postMultipart(t, env.url("/upload"), "video", "my-video.mp4", source)
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("upload status = %d, body=%s", response.StatusCode, body)
	}

	uploaded := decodeJSON[map[string]any](t, body)
	id, _ := uploaded["id"].(string)
	if id == "" {
		t.Fatalf("upload missing id: %s", body)
	}
	if uploaded["name"] != "my-video.mp4" {
		t.Fatalf("upload name = %v", uploaded["name"])
	}
	if uploaded["status"] != models.StatusUploaded {
		t.Fatalf("upload status = %v", uploaded["status"])
	}
	if uploaded["hasOriginal"] != true {
		t.Fatalf("upload hasOriginal = %v", uploaded["hasOriginal"])
	}
	if size, ok := uploaded["fileSize"].(float64); !ok || int64(size) != int64(len(source)) {
		t.Fatalf("upload fileSize = %v want %d", uploaded["fileSize"], len(source))
	}
	if strings.Contains(strings.ToLower(string(body)), "path") {
		t.Fatalf("upload response exposed a path: %s", body)
	}

	response, original := doRequest(t, http.MethodGet, env.url("/videos/"+id+"/original"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("original status = %d, body=%s", response.StatusCode, original)
	}
	if !bytes.Equal(original, source) {
		t.Fatalf("original bytes do not match upload (got %d, want %d)", len(original), len(source))
	}

	// A file with a supported extension but non-media content must be rejected
	// and must not create a database row.
	response, body = postMultipart(t, env.url("/upload"), "video", "broken.mp4", []byte("not a video"))
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("invalid upload status = %d, body=%s", response.StatusCode, body)
	}
	errorPayload := decodeJSON[map[string]string](t, body)
	if errorPayload["error"] == "" {
		t.Fatalf("invalid upload was missing error payload: %s", body)
	}

	response, body = postMultipart(t, env.url("/upload"), "video", "notes.txt", []byte("hello"))
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("unsupported extension status = %d, body=%s", response.StatusCode, body)
	}

	response, body = postMultipart(t, env.url("/upload"), "file", "wrong-field.mp4", source)
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("wrong field status = %d, body=%s", response.StatusCode, body)
	}

	_, listBody := doRequest(t, http.MethodGet, env.url("/videos"), "", "")
	var list []map[string]any
	if err := json.Unmarshal(listBody, &list); err != nil {
		t.Fatalf("decode list: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("invalid uploads created rows; list length = %d", len(list))
	}
}

func TestProcessRequestValidationAndDuplicatePrevention(t *testing.T) {
	env := newTestEnv(t)

	video := env.seedVideo(t, models.StatusUploaded)

	valid := `{"operations":[{"type":"speed","factor":1.5}]}`
	response, body := doRequest(t, http.MethodPost, env.url("/videos/"+video.ID+"/process"), valid, "application/json")
	if response.StatusCode != http.StatusAccepted {
		t.Fatalf("process status = %d, body=%s", response.StatusCode, body)
	}
	payload := decodeJSON[map[string]any](t, body)
	jobID, _ := payload["id"].(string)
	if jobID == "" || jobID == video.ID {
		t.Fatalf("process payload has no job id: %v", payload)
	}
	if payload["videoId"] != video.ID || payload["status"] != models.StatusQueued {
		t.Fatalf("process payload = %v", payload)
	}

	job, err := env.jobs.Get(jobID)
	if err != nil {
		t.Fatalf("get queued job: %v", err)
	}
	if job.VideoID != video.ID || job.Status != models.StatusQueued {
		t.Fatalf("stored job = %+v", job)
	}

	stored, err := env.store.Get(video.ID)
	if err != nil {
		t.Fatalf("get queued video: %v", err)
	}
	if stored.Status != models.StatusQueued {
		t.Fatalf("stored status = %q", stored.Status)
	}

	response, body = doRequest(t, http.MethodPost, env.url("/videos/"+video.ID+"/process"), valid, "application/json")
	if response.StatusCode != http.StatusConflict {
		t.Fatalf("duplicate process status = %d, body=%s", response.StatusCode, body)
	}

	other := env.seedVideo(t, models.StatusUploaded)
	invalidRequests := []string{
		`{"operations":[]}`,
		`{"operations":[{"type":"unknown"}]}`,
		`{"operations":[{"type":"speed","factor":0.25}]}`,
		`{"operations":[{"type":"speed","factor":3}]}`,
		`{"operations":[{"type":"volume","factor":0}]}`,
		`{"operations":[{"type":"flip","orientation":2}]}`,
		`{"operations":[{"type":"trim","start":"00:00:04","end":"00:00:02"}]}`,
		`{"operations":[{"type":"trim","start":"00:00:01","end":"00:00:06"}]}`,
		`{"operations":[{"type":"to_audio"},{"type":"speed","factor":1.5}]}`,
		`{"operations":[{"type":"trim","start":"nonsense","end":"00:00:02"}]}`,
	}

	for _, requestBody := range invalidRequests {
		response, body = doRequest(t, http.MethodPost, env.url("/videos/"+other.ID+"/process"), requestBody, "application/json")
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("invalid request %s status = %d, body=%s", requestBody, response.StatusCode, body)
		}
		errorPayload := decodeJSON[map[string]string](t, body)
		if errorPayload["error"] == "" {
			t.Fatalf("invalid request %s returned no error", requestBody)
		}

		stored, err = env.store.Get(other.ID)
		if err != nil {
			t.Fatalf("get invalid video: %v", err)
		}
		if stored.Status != models.StatusUploaded {
			t.Fatalf("invalid request changed status to %q", stored.Status)
		}
	}

	response, body = doRequest(t, http.MethodPost, env.url("/videos/missing/process"), valid, "application/json")
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("missing video process status = %d, body=%s", response.StatusCode, body)
	}
}

func TestMissingVideoAndMissingFiles(t *testing.T) {
	env := newTestEnv(t)

	response, body := doRequest(t, http.MethodGet, env.url("/videos/does-not-exist"), "", "")
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("missing video status = %d, body=%s", response.StatusCode, body)
	}
	if payload := decodeJSON[map[string]string](t, body); payload["error"] == "" {
		t.Fatalf("missing video returned no error: %s", body)
	}

	completed := env.seedVideo(t, models.StatusCompleted)
	if err := os.Remove(completed.OutputPath); err != nil {
		t.Fatalf("remove output: %v", err)
	}

	for _, path := range []string{
		"/videos/" + completed.ID + "/output",
		"/videos/" + completed.ID + "/download",
	} {
		response, body = doRequest(t, http.MethodGet, env.url(path), "", "")
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("%s status = %d, body=%s", path, response.StatusCode, body)
		}
		if payload := decodeJSON[map[string]string](t, body); payload["error"] == "" {
			t.Fatalf("%s returned no error: %s", path, body)
		}
	}

	uploaded := env.seedVideo(t, models.StatusUploaded)
	if err := os.Remove(uploaded.OriginalPath); err != nil {
		t.Fatalf("remove original: %v", err)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/videos/"+uploaded.ID+"/original"), "", "")
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("missing original status = %d, body=%s", response.StatusCode, body)
	}

	queued := env.seedVideo(t, models.StatusQueued)
	response, body = doRequest(t, http.MethodGet, env.url("/videos/"+queued.ID+"/download"), "", "")
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("queued download status = %d, body=%s", response.StatusCode, body)
	}
}

func TestProcessVideoEndToEnd(t *testing.T) {
	requireVideoTools(t)
	env := newTestEnv(t)

	sourcePath := filepath.Join(t.TempDir(), "input.mp4")
	generateTestVideo(t, sourcePath)
	source, err := os.ReadFile(sourcePath)
	if err != nil {
		t.Fatalf("read source: %v", err)
	}

	response, body := postMultipart(t, env.url("/upload"), "video", "pipeline.mp4", source)
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("upload status = %d, body=%s", response.StatusCode, body)
	}
	uploaded := decodeJSON[map[string]any](t, body)
	videoID, _ := uploaded["id"].(string)
	if videoID == "" {
		t.Fatalf("upload did not return an id: %s", body)
	}

	operations := models.ProcessRequest{
		Operations: []models.Operation{
			{Type: "trim", Start: "00:00:00.200", End: "00:00:01.600"},
			{Type: "speed", Factor: 1.25},
			{Type: "volume", Factor: 1.4},
			{Type: "flip", Direction: ffmpeg.Horizontal},
		},
	}
	operationsJSON, err := json.Marshal(operations)
	if err != nil {
		t.Fatalf("marshal operations: %v", err)
	}

	response, body = doRequest(t, http.MethodPost, env.url("/videos/"+videoID+"/process"), string(operationsJSON), "application/json")
	if response.StatusCode != http.StatusAccepted {
		t.Fatalf("process status = %d, body=%s", response.StatusCode, body)
	}
	createdJob := decodeJSON[map[string]any](t, body)
	jobID, _ := createdJob["id"].(string)
	if jobID == "" {
		t.Fatalf("process response missing job id: %s", body)
	}

	env.startManager(t)
	finalJob := env.waitForJob(t, jobID)
	if finalJob["status"] != models.StatusCompleted {
		t.Fatalf("final job status = %v (body=%v)", finalJob["status"], finalJob)
	}
	if finalJob["hasOutput"] != true || finalJob["outputType"] != "video" {
		t.Fatalf("job output metadata = %v", finalJob)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/videos/"+videoID), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status request = %d, body=%s", response.StatusCode, body)
	}
	final := decodeJSON[map[string]any](t, body)
	if final["status"] != models.StatusCompleted {
		t.Fatalf("final status = %v (body=%s)", final["status"], body)
	}
	if final["progress"] != float64(100) {
		t.Fatalf("final progress = %v", final["progress"])
	}
	if final["hasOutput"] != true || final["outputType"] != "video" {
		t.Fatalf("video output metadata = %v", final)
	}

	response, output := doRequest(t, http.MethodGet, env.url("/videos/"+videoID+"/output"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("output status = %d, body=%s", response.StatusCode, output)
	}
	if len(output) == 0 {
		t.Fatal("processed video output is empty")
	}

	response, download := doRequest(t, http.MethodGet, env.url("/videos/"+videoID+"/download"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("download status = %d, body=%s", response.StatusCode, download)
	}
	if !bytes.Equal(download, output) {
		t.Fatal("download body does not match output body")
	}
	if disposition := response.Header.Get("Content-Disposition"); !strings.Contains(disposition, "attachment") || !strings.Contains(disposition, ".mp4") {
		t.Fatalf("download disposition = %q", disposition)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/jobs/"+jobID+"/output"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("job output status = %d, body=%s", response.StatusCode, body)
	}
	if len(body) == 0 {
		t.Fatal("job output is empty")
	}

	// A terminal job does not prevent starting a fresh attempt from the same
	// source. Only an active attempt blocks a new job.
	response, body = doRequest(t, http.MethodPost, env.url("/videos/"+videoID+"/process"), string(operationsJSON), "application/json")
	if response.StatusCode != http.StatusAccepted {
		t.Fatalf("reprocess completed status = %d, body=%s", response.StatusCode, body)
	}
	secondJob := decodeJSON[map[string]any](t, body)
	secondID, _ := secondJob["id"].(string)
	if secondID == "" || secondID == jobID {
		t.Fatalf("second job was not distinct: %v", secondJob)
	}
	response, body = doRequest(t, http.MethodPost, env.url("/jobs/"+secondID+"/cancel"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("cancel second job status = %d, body=%s", response.StatusCode, body)
	}
	cancelled := decodeJSON[map[string]any](t, body)
	if cancelled["status"] != models.StatusCancelRequested && cancelled["status"] != models.StatusCancelled {
		t.Fatalf("cancel response status = %v", cancelled["status"])
	}
	cancelled = env.waitForJob(t, secondID)
	if cancelled["status"] != models.StatusCancelled {
		t.Fatalf("cancelled job status = %v", cancelled["status"])
	}

	// Audio extraction should produce an MP3 and be served as audio.
	response, body = postMultipart(t, env.url("/upload"), "video", "audio-source.mp4", source)
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("audio upload status = %d, body=%s", response.StatusCode, body)
	}
	audioUpload := decodeJSON[map[string]any](t, body)
	audioID, _ := audioUpload["id"].(string)

	audioOperations := models.ProcessRequest{
		Operations: []models.Operation{
			{Type: "trim", Start: "00:00:00", End: "00:00:01.200"},
			{Type: "volume", Factor: 1.2},
			{Type: "to_audio"},
		},
	}
	audioJSON, err := json.Marshal(audioOperations)
	if err != nil {
		t.Fatalf("marshal audio operations: %v", err)
	}

	response, body = doRequest(t, http.MethodPost, env.url("/videos/"+audioID+"/process"), string(audioJSON), "application/json")
	if response.StatusCode != http.StatusAccepted {
		t.Fatalf("audio process status = %d, body=%s", response.StatusCode, body)
	}
	audioJob := decodeJSON[map[string]any](t, body)
	audioJobID, _ := audioJob["id"].(string)

	audioFinalJob := env.waitForJob(t, audioJobID)
	if audioFinalJob["status"] != models.StatusCompleted || audioFinalJob["outputType"] != "audio" {
		t.Fatalf("audio job metadata = %v", audioFinalJob)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/videos/"+audioID), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("audio status request = %d, body=%s", response.StatusCode, body)
	}
	audioFinal := decodeJSON[map[string]any](t, body)
	if audioFinal["status"] != models.StatusCompleted || audioFinal["outputType"] != "audio" {
		t.Fatalf("audio output metadata = %v (body=%s)", audioFinal, body)
	}

	response, audioOutput := doRequest(t, http.MethodGet, env.url("/videos/"+audioID+"/output"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("audio output status = %d, body=%s", response.StatusCode, audioOutput)
	}
	if len(audioOutput) == 0 {
		t.Fatal("processed audio output is empty")
	}
	if contentType := response.Header.Get("Content-Type"); !strings.Contains(contentType, "audio") {
		t.Fatalf("audio content type = %q", contentType)
	}

	response, body = doRequest(t, http.MethodHead, env.url("/videos/"+audioID+"/download"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("audio download HEAD status = %d, body=%s", response.StatusCode, body)
	}
	if disposition := response.Header.Get("Content-Disposition"); !strings.Contains(disposition, ".mp3") {
		t.Fatalf("audio download disposition = %q", disposition)
	}
}

func TestOutputAndDownloadRequireCompletedStatus(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	for _, path := range []string{
		"/videos/" + video.ID + "/output",
		"/videos/" + video.ID + "/download",
	} {
		response, body := doRequest(t, http.MethodGet, env.url(path), "", "")
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("%s status = %d, body=%s", path, response.StatusCode, body)
		}
	}
}

func TestValidationErrorsIncludeOperationIndex(t *testing.T) {
	env := newTestEnv(t)
	video := env.seedVideo(t, models.StatusUploaded)

	response, body := doRequest(
		t,
		http.MethodPost,
		env.url("/videos/"+video.ID+"/process"),
		`{"operations":[{"type":"speed","factor":1.5},{"type":"flip","orientation":9}]}`,
		"application/json",
	)
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, body=%s", response.StatusCode, body)
	}

	payload := decodeJSON[map[string]string](t, body)
	if !strings.Contains(payload["error"], "operation 1") {
		t.Fatalf("error does not identify failing operation: %q", payload["error"])
	}
	if strings.Contains(strings.ToLower(payload["error"]), env.dir) {
		t.Fatalf("error exposed temp path: %q", payload["error"])
	}
}

func TestProcessVideoWithoutAudioFailsGracefully(t *testing.T) {
	requireVideoTools(t)
	env := newTestEnv(t)

	sourcePath := filepath.Join(t.TempDir(), "silent.mp4")
	generateTestVideoWithoutAudio(t, sourcePath)
	source, err := os.ReadFile(sourcePath)
	if err != nil {
		t.Fatalf("read source: %v", err)
	}

	response, body := postMultipart(t, env.url("/upload"), "video", "silent.mp4", source)
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("upload status = %d, body=%s", response.StatusCode, body)
	}
	uploaded := decodeJSON[map[string]any](t, body)
	videoID, _ := uploaded["id"].(string)

	operations := models.ProcessRequest{Operations: []models.Operation{{Type: "to_audio"}}}
	operationsJSON, err := json.Marshal(operations)
	if err != nil {
		t.Fatalf("marshal operations: %v", err)
	}

	response, body = doRequest(t, http.MethodPost, env.url("/videos/"+videoID+"/process"), string(operationsJSON), "application/json")
	if response.StatusCode != http.StatusAccepted {
		t.Fatalf("audio process status = %d, body=%s", response.StatusCode, body)
	}
	job := decodeJSON[map[string]any](t, body)
	jobID, _ := job["id"].(string)

	// A video without an audio stream must fail the job rather than panicking
	// the process or leaving the job stuck in the queue.
	env.startManager(t)
	finalJob := env.waitForJob(t, jobID)
	if finalJob["status"] != models.StatusFailed {
		t.Fatalf("final job status = %v (body=%v)", finalJob["status"], finalJob)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/videos/"+videoID), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status request = %d, body=%s", response.StatusCode, body)
	}
	final := decodeJSON[map[string]any](t, body)
	if final["status"] != models.StatusFailed {
		t.Fatalf("final status = %v (body=%s)", final["status"], body)
	}
	errorMessage, _ := final["error"].(string)
	if errorMessage == "" {
		t.Fatalf("failed job has no error message: %s", body)
	}
	if !strings.Contains(errorMessage, "no audio track") {
		t.Fatalf("failed job error is not actionable: %q", errorMessage)
	}

	response, body = doRequest(t, http.MethodGet, env.url("/health"), "", "")
	if response.StatusCode != http.StatusOK {
		t.Fatalf("server unhealthy after failed job: %d %s", response.StatusCode, body)
	}
}
