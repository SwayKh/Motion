# Development, Testing, and Troubleshooting

See the root [README](../README.md) for the feature overview and [ARCHITECTURE.md](ARCHITECTURE.md) for package and lifecycle details.

## Prerequisites

- Go 1.26.2 or a compatible newer toolchain, as declared in `go.mod`.
- FFmpeg and FFprobe on `PATH`.
- A writable checkout directory for `uploads/`, `outputs/`, and `data/`.
- No Node.js/npm build step is required for the frontend.

```bash
go version
ffmpeg -version
ffprobe -version
```

The application does not check for FFmpeg/FFprobe at startup. Missing tools surface when uploading or processing.

## Local setup

From the repository root:

```bash
go mod download
go run .
```

Open `http://localhost:8080`. The process creates the runtime directories and database automatically.

Alternative binary build:

```bash
go build -o motion .
./motion
```

All paths are relative to the working directory. If you build elsewhere, either run the binary from the repository root or copy the `web/` directory and expect `uploads/`, `outputs/`, and `data/` to be created beside the working directory.

## Configuration

Configuration is via environment variables loaded by `internal/config` (see `.env.example` and the root README's Configuration section). The important ones for local development are:

- HTTP port via `PORT` (default `:8080`)
- database `<MOTION_DATA_DIR>/app.db` (`MOTION_DATA_DIR` default `data`)
- storage dirs `MOTION_UPLOAD_DIR`, `MOTION_OUTPUT_DIR`, `MOTION_TEMP_DIR`, `MOTION_PREVIEW_DIR` (cached thumbnail filmstrips + waveform peaks)
- worker count via `MAX_GLOBAL_CONCURRENT_JOBS` (default 3)
- upload/duration/resolution limits via `MAX_UPLOAD_BYTES`, `MAX_DURATION_SECONDS`, `MAX_WIDTH`, `MAX_HEIGHT`
- quotas via `MAX_*` variables
- cleanup via `RETENTION` and `CLEANUP_INTERVAL`
- optional encryption via `MOTION_ENCRYPTION_KEY`
- email verification via `REQUIRE_EMAIL_VERIFICATION`; in development, `DEV_EMAIL_OUTBOX=true` (the default outside production) writes outgoing mail to `<MOTION_DATA_DIR>/outbox.log`

Unset variables fall back to the defaults shown in `.env.example`. To override locally, export the variable or copy `.env.example` to `.env` and edit it.

## Running tests

Run everything:

```bash
go test ./...
```

Run with verbose output for API integration:

```bash
go test ./internal/api -v
```

Run specific suites:

```bash
go test ./internal/api -v
go test ./internal/db -v
go test ./internal/job -run TestCleanup -v
go test ./internal/service -v
go test ./internal/validation -v
go test ./internal/storage -v
go test ./internal/quota -v
go test ./internal/mediacrypt -v
go test ./internal/media -v
go test ./internal/preview -v
go test ./internal/timeline -v
go test ./internal/rate -v
```

Static checks:

```bash
go vet ./...
gofmt -l $(git ls-files '*.go')
for f in web/*.js; do node --check "$f"; done
```

The `gofmt` list should be empty when formatting is clean. There is no configured lint task, Makefile, or CI workflow in the repository, and there is no JavaScript test harness. Frontend changes are syntax-checked with Node and verified manually in a browser; backend behavior is covered by Go tests.

### FFmpeg-dependent tests

The API integration helper `requireVideoTools` skips tests when `ffmpeg` or `ffprobe` is missing. Test videos are generated with FFmpeg's `testsrc2` and `sine` sources. Key integration coverage includes:

- upload validation (bad extension, corrupt media, wrong form field) and internal storage/probe/database failures
- original serving and path non-disclosure
- processing validation and duplicate active-job prevention
- end-to-end trim/speed/volume/flip processing
- each operation with an audio track and with a video-only source, including output stream and playback checks
- audio extraction to MP3
- output/download behavior
- cancellation of a queued/running attempt
- graceful failure for a video without audio when adjusting volume or extracting audio
- cleanup retention, active-job protection, stale-snapshot rechecks, filesystem/database failure behavior, and queue/cleanup races
- volume factor boundaries at validation and service layers
- recovery of interrupted jobs
- legacy job backfill
- editing operations: `slice`, `remove_section`, `to_audio`, `add_audio`, `concat` (including a clip without audio), and `compose` (picture-in-picture + text overlays with pixel assertions, and a multi-clip join with audio), plus an encrypted upload/process/serve round trip
- API feature tests: video and job deletion, edit-project CRUD, timeline-project CRUD/render, preview-asset endpoints, and the email-verification flow
- timeline model tests: structure/asset validation, time math, and simple-vs-compose render-plan translation

### Recorded result

At the time this documentation was written, the following passed on Linux with Go 1.27.3 and FFmpeg/FFprobe n9.0.2:

```text
go test ./...   -> all packages passed
go vet ./...    -> passed
gofmt -l $(git ls-files '*.go') -> no output
node --check web/app.js -> no output
```

The repository declares Go 1.26.2 in `go.mod`; the local test environment used a newer toolchain.

## Manual API smoke test

Start the server from the repository root in one terminal. The API requires authentication, so the smoke test uses a cookie jar and the CSRF token. In another terminal:

```bash
JAR=$(mktemp)
BASE=http://localhost:8080

# Health (public)
curl -s "$BASE/health"

# Establish a CSRF cookie
curl -s -c "$JAR" -b "$JAR" "$BASE/api/auth/csrf"

# Register (sets the session cookie)
curl -s -c "$JAR" -b "$JAR" -X POST "$BASE/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@example.com","password":"password123","passwordConfirm":"password123"}'

# Read the CSRF token from the cookie jar for state-changing requests
CSRF=$(awk '$6=="motion_csrf"{print $7}' "$JAR")

# Upload (replace sample.mp4 with a real local file)
curl -s -c "$JAR" -b "$JAR" -H "X-CSRF-Token: $CSRF" -F "video=@sample.mp4" "$BASE/upload"
```

Copy the returned video `id`, then:

```bash
VIDEO_ID=<returned-id>

# List videos
curl -s -c "$JAR" -b "$JAR" "$BASE/videos"

# Queue a simple pipeline
curl -s -c "$JAR" -b "$JAR" -X POST \
  -H "Content-Type: application/json" \
  -H "X-CSRF-Token: $CSRF" \
  -d '{"operations":[{"type":"trim","start":"00:00:00","end":"00:00:02"},{"type":"speed","factor":1.5}]}' \
  "$BASE/videos/$VIDEO_ID/process"
```

Copy the returned job `id`, then:

```bash
JOB_ID=<returned-id>

# Poll the job
curl -s -c "$JAR" -b "$JAR" "$BASE/jobs/$JOB_ID"

# List this video's jobs
curl -s -c "$JAR" -b "$JAR" "$BASE/jobs?videoId=$VIDEO_ID"

# Check output headers without downloading the body
curl -s -c "$JAR" -b "$JAR" -I "$BASE/jobs/$JOB_ID/output"

# Download the output
curl -s -c "$JAR" -b "$JAR" -L -o edited.mp4 "$BASE/jobs/$JOB_ID/download"
```

Cancellation works while a job is `queued` or `processing`:

```bash
curl -s -c "$JAR" -b "$JAR" -X POST -H "X-CSRF-Token: $CSRF" "$BASE/jobs/$JOB_ID/cancel"
```

When SMTP is not configured and `DEV_EMAIL_OUTBOX=true`, verification and reset links are written to `data/outbox.log` for local testing.

The multitrack editor's own endpoints can be exercised the same way (all require the CSRF header shown above):

```bash
# Preview assets for a video
curl -s -c "$JAR" -b "$JAR" "$BASE/api/videos/$VIDEO_ID/thumbnails"
curl -s -c "$JAR" -b "$JAR" -o sprite.jpg "$BASE/api/videos/$VIDEO_ID/thumbnails/sprite"
curl -s -c "$JAR" -b "$JAR" "$BASE/api/videos/$VIDEO_ID/waveform"

# Create a timeline, then render it
curl -s -c "$JAR" -b "$JAR" -X POST -H "Content-Type: application/json" -H "X-CSRF-Token: $CSRF" \
  -d '{"name":"Smoke","schemaVersion":1,"tracks":[{"id":"t1","type":"video","order":0,"visible":true,"clips":[{"id":"c1","kind":"video","assetId":"'"$VIDEO_ID"'","sourceIn":0,"sourceOut":2,"timelineStart":0}]}]}' \
  "$BASE/api/timeline-projects"

curl -s -c "$JAR" -b "$JAR" -X POST -H "Content-Type: application/json" -H "X-CSRF-Token: $CSRF" \
  -d '{"projectId":"<timeline-id>"}' "$BASE/api/timeline/render"
```

The complete route and payload reference is in [API.md](API.md).

## Inspecting state

The application does not include an admin UI for the database. SQLite's CLI is optional but useful:

```bash
sqlite3 data/app.db "SELECT id, name, status, progress, created_at FROM videos ORDER BY created_at DESC;"
sqlite3 data/app.db "SELECT id, video_id, status, progress, created_at FROM jobs ORDER BY created_at DESC LIMIT 20;"
sqlite3 data/app.db "SELECT * FROM schema_migrations;"
```

Stop external writers before relying on ad-hoc queries; the application uses WAL and a single database connection.

## Troubleshooting

### FFmpeg or FFprobe is missing

Symptoms:

- Upload returns HTTP 500 with a generic `failed to process upload` error because probing infrastructure is unavailable.
- Jobs fail with a processing error.
- The server log mentions `ffprobe metadata` or an `exec` failure.

Check:

```bash
command -v ffmpeg
command -v ffprobe
ffmpeg -version
ffprobe -version
```

Ensure the same `PATH` is visible to the process that starts the Go server (for example, a service manager may not inherit your shell `PATH`).

### Upload validation failures

Client upload errors are returned as `{"error":"..."}` with HTTP 400 (or 413 for the request-size limit). Common causes:

- Missing or unsupported extension. The backend accepts only `.mp4`, `.m4v`, `.mov`, `.mkv`, `.webm`, `.avi`, `.mpeg`, `.mpg`, `.3gp`.
- The file is corrupt or has no video stream.
- Duration is greater than 300 seconds.
- Width is greater than 1920 or height is greater than 1080. A portrait 1080×1920 video is rejected because height exceeds 1080.
- The request uses the wrong form field. The field must be named `video`; a field named `file` fails.

Inspect the file:

```bash
ffprobe -v error -show_format -show_streams sample.mp4
```

A file just under 150 MiB can still hit HTTP 413 because the limit covers multipart overhead. Retry with a smaller file.

Storage failures, an FFprobe executable that cannot start, malformed probe output, and database insert failures return HTTP 500 with `failed to process upload`. The response intentionally omits paths and internal details; the server log contains the underlying error.

### Processing failures

Inspect:

- The job's `error` field via `GET /jobs/{id}`.
- The video's `error` field via `GET /videos/{id}`.
- The server log. FFmpeg's compiled command and stderr are emitted there.

Typical causes:

- `to_audio` on a source with no audio track. The job reports `this video has no audio track to extract`.
- `volume` on a source with no audio track. The job reports `this video has no audio track to adjust`.
- A stream-copy combination that cannot be placed in MP4. The service may copy unmodified video/audio, so an input codec/container can fail even though validation passed.
- `trim`, `speed`, and `flip` are valid for video-only sources and should complete without an audio stream.
- A codec not available in the installed FFmpeg build.
- The source file was deleted; a new process request returns 409, and recovery can fail an in-flight job.

There is no retry endpoint. Fix the pipeline or source and submit a new processing request; terminal jobs do not block a new attempt.

### Job stuck in queued, processing, or cancel_requested

- `queued`: workers are busy or not running, or the job was queued after the manager stopped. Workers poll every 2 seconds when idle. Restart the app if the process is no longer serving.
- `processing`: check whether FFmpeg is still running and whether logs show progress. A crash leaves it `processing` until the next startup, where recovery requeues it (or fails it if the source/operations are invalid).
- `cancel_requested`: the worker is stopping FFmpeg and will mark it `cancelled`. If the process crashed at this point, recovery resolves it to `cancelled` on the next startup.

Recovery only repairs `processing` and `cancel_requested`. Queued jobs are intentionally left queued.

### SQLite permission or locking errors

Symptoms: startup errors opening the database, or HTTP 500 errors mentioning a database failure.

Check:

```bash
ls -ld data data/app.db
df -h .
```

The process needs write access to `data/` for the database and its WAL sidecars. The app sets WAL, `busy_timeout=5000`, foreign keys, and a single connection. Long-running external writers can still cause lock contention. Stop ad-hoc writers while the app is running.

### Missing output or 404 on preview/download

- Output routes return 404 unless the job/video is `completed` and has a non-empty output path.
- The file may have been deleted externally or by cleanup.
- Cleanup deletes terminal videos only when the video and every associated job are at least 24 hours old; it runs hourly. Active jobs and recently created or finished jobs keep the video alive.
- Check the filesystem and database after stopping the app:

```bash
ls -la outputs uploads
sqlite3 data/app.db "SELECT id, status, output_path FROM jobs ORDER BY created_at DESC;"
```

### Port conflict

The port comes from `PORT` (default `:8080`); set `PORT=9090` (or any free port) to change it. The server binds all interfaces, not just localhost.

Identify the listener:

```bash
ss -ltnp | grep 8080
# or
lsof -i :8080
```

Stop the other process, or start Motion on a different port with the `PORT` environment variable.

### Frontend/API integration issues

- Start the server from the repository root so `./web` is found. Opening `web/index.html` directly as a `file://` URL will not work because the API requests are relative and no backend is served.
- Use the browser developer console/network panel to inspect failed requests.
- If `/health` is reachable but the library fails, check `/videos` and `/jobs` responses. The frontend treats job history as supplemental; if `/jobs` fails, the library renders without history and retries on the next load.
- API errors are JSON. Static-file 404s and router-generated 405s are plain text; do not assume every error is JSON.
- The frontend polls while a job is active. There is no WebSocket/SSE, so progress depends on polling and database updates.

### Resetting local state

This is destructive. Stop the server first, then back up anything you need:

```bash
rm -f data/app.db data/app.db-wal data/app.db-shm
rm -f uploads/* outputs/*
```

The directories are recreated on the next start. Migrations will rebuild an empty database. Never do this while the server is running.

## Suggested checks before submitting changes

The repository has no contribution guide, CI configuration, or license file. A reasonable local check sequence is:

```bash
go fmt ./...
go vet ./...
go test ./...
git diff --check
```

Do not commit `data/`, uploaded media, generated outputs, or other runtime files. `.gitignore` only covers `*.db*`, `*.mp4`, and `*.mp3`, so uploads in other accepted formats may appear as untracked files.
