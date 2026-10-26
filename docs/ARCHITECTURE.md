# Architecture and Lifecycle

This document describes the current implementation in the repository. It is intentionally more detailed than the root README.

## High-level components

| Component | Package/File | Responsibility |
| --- | --- | --- |
| Entry point | `main.go` | Create runtime directories, open the database, build stores, start workers, recover interrupted jobs, start the cleaner, serve HTTP, shut down |
| HTTP router | `internal/api/router.go` | Register method/path routes, serve static frontend, wrap everything in CORS |
| Handlers | `internal/api/handlers.go` | Upload, video/job reads, processing requests, cancellation, file serving |
| Response mapping | `internal/api/response.go` | Convert database models to path-free public JSON; clamp progress |
| Middleware | `internal/api/middleware.go` | Permissive CORS and `OPTIONS` handling |
| Upload service | `internal/service/process.go` | Validate extension/size, save upload, probe metadata, enforce duration/resolution/stream rules, persist video |
| Processing service | `internal/service/process.go` | Build the FFmpeg pipeline, choose output type, run to a temporary file, verify/rename output |
| FFmpeg builder | `internal/ffmpeg/ffmpeg.go` | Compose filter streams, codec copy decisions, output context, progress wiring |
| FFprobe wrapper | `internal/ffmpeg/ffprobe.go` | Call `ffprobe` through `ffmpeg-go` |
| Progress parser | `internal/ffmpeg/progress.go` | Parse `out_time_ms=` lines from FFmpeg stdout |
| Metadata parser | `internal/media/metadata.go` | Extract duration, dimensions, codecs, frame rate, audio presence, format from FFprobe JSON |
| Validation | `internal/validation/validate.go` | Operation count/type/parameter/order rules |
| Models | `internal/models/video.go` | Video/Job structs, operation request shape, status constants |
| Database setup | `internal/db/db.go` | SQLite pragmas, migration table, versioned migrations, legacy backfill |
| Video persistence | `internal/db/video.go` | Video CRUD, status/progress updates, expiration query |
| Job persistence | `internal/db/job.go` | Job creation, claiming, progress, cancellation, terminal transitions, listing |
| Job manager | `internal/job/queue.go` | Worker lifecycle, wake notifications, per-job cancellation map, artifact removal |
| Worker loop | `internal/job/worker.go` | Claim/run jobs, recover interrupted work, resolve cancellation/shutdown/error races |
| Cleaner | `internal/job/cleanup.go` | Hourly deletion of old terminal videos, their jobs, and files |
| Logger | `internal/logger/logger.go` | Informational stdout logger and error stderr logger |
| Frontend | `web/` | Static HTML/CSS/JS dashboard; polls the API |

## Startup sequence

1. `logger.Init()` initializes stdout/stderr loggers.
2. `ensureDirectories()` creates `uploads/`, `outputs/`, and `data/` with mode `0755`.
3. `db.Open("data/app.db")` opens SQLite, sets WAL journaling, a 5-second busy timeout, foreign keys on, and a single database connection.
4. Migrations are applied: videos table, then jobs table and legacy backfill.
5. `VideoStore` and `JobStore` are constructed.
6. `job.NewManager(jobStore, 3)` creates a manager with three workers.
7. `manager.Recover()` repairs jobs left in `processing` or `cancel_requested` from a previous process.
8. A signal-aware context listens for `SIGINT` and `SIGTERM`.
9. `manager.Start(ctx)` launches the worker goroutines.
10. `job.StartCleaner(videoStore)` starts the hourly cleanup goroutine.
11. `http.Server` listens on `:8080`, serves `./web`, and uses a 10-second header timeout.
12. On signal or server failure, HTTP shutdown is given 15 seconds, then `manager.Stop()` cancels workers and waits for them.

The cleaner is not tied to the shutdown context. The process exits after `main` returns, so this is not a long-running leak in normal operation.

## Upload flow

1. `POST /upload` wraps the body in `http.MaxBytesReader` at 150 MiB and calls `ParseMultipartForm` with 32 MiB in-memory storage.
2. The handler requires the form field `video`.
3. `VideoService.Upload`:
   - Takes `filepath.Base` of the client filename.
   - Requires an extension and checks it against the allowlist.
   - Rejects `FileHeader.Size > 150 MiB`.
   - Generates a UUID and writes to `uploads/<uuid><lowercase-ext>`.
   - Runs FFprobe and parses the JSON metadata.
   - Rejects duration > 300 seconds, width > 1920, or height > 1080.
   - Requires a video stream (`meta.Width != 0`).
   - Inserts a `videos` row with status `uploaded`, progress 0, original path/name, bytes written, duration, width, and height.
   - Removes the stored file if probing, validation, or persistence fails.
   - Classifies failures as client (`400`) or internal (`500`). Corrupt/unsupported media, oversized files, and missing video streams are client errors. File-write failures, an FFprobe executable that cannot start, malformed probe output, and database failures are internal errors with generic HTTP messages.
4. The handler returns `201` with the path-free video response.

The upload is not transcoded. The stored original keeps its extension and original bytes.

## Processing request flow

1. `POST /videos/{id}/process` loads the video row.
2. It verifies the original file still exists; otherwise it returns `409`.
3. It decodes a `ProcessRequest` from a body limited to 1 MiB.
4. `ValidateProcessRequest` enforces operation count, types, parameters, and ordering.
5. The request is marshaled to JSON and passed to `JobStore.CreateQueued`.
6. `CreateQueued` runs in a transaction:
   - Reads `original_path` and `original_name` from the video (snapshot into the job).
   - Counts active jobs for the video and rejects with `ErrActiveJob` if any exist.
   - Inserts a job with a new UUID, status `queued`, progress 0, and `created_at` from SQLite.
   - Updates the video to `queued`, stores operations, resets progress/error, and clears the latest `output_path`.
   - The partial unique index `idx_jobs_one_active_per_video` is a second guard against concurrent creation.
7. The handler calls `manager.Notify()` and returns `202` with the job.

## Worker claim and execution

Each worker loops:

1. Call `JobStore.ClaimNext()`.
2. If no queued job exists (`sql.ErrNoRows`), sleep up to 2 seconds or until `Notify()` wakes it.
3. On a claimed job, run it.
4. On database errors, log and sleep before retrying.

`ClaimNext` is one transaction:

- Finds the oldest queued job (`created_at ASC, id ASC`).
- Updates it to `processing`, resets progress, sets `started_at`, and clears `finished_at`, cancel timestamp, output path, and error.
- Uses `RETURNING` to read the claimed row.
- Updates the video to `processing` with the same operations and reset fields.
- Commits.

Because SQLite is configured with a single connection, workers serialize their database operations. Multiple workers can still run FFmpeg concurrently.

## Processing pipeline

`service.ProcessJob` receives the job, parsed operations, and a progress callback.

1. If any operation is `to_audio`, output is `.mp3` and type `audio`; otherwise output is `.mp4` and type `video`.
2. It checks the context and source file existence.
3. It probes the source once and parses its metadata. `to_audio` fails early when the source has no audio. `volume` also requires audio. Other operations proceed without an audio stream.
4. It creates an FFmpeg `Processor` with audio mapping enabled only when the source has audio, then applies operations in request order:
   - `trim` — `trim`/`atrim` and timestamp resets.
   - `speed` — `setpts` on video and `atempo` on audio, with `fps_mode=passthrough`.
   - `volume` — validates a finite factor greater than zero, requires audio, then applies the `volume` filter.
   - `flip` — `vflip` or `hflip` on video only.
   - `to_audio` — drops video, forces MP3 (`libmp3lame`, `q:a=2`).
   - Unknown types are only logged here; validation should prevent them.
5. If no operation was applied, it fails.
6. It creates `outputs/`, removes any existing temp/final file for the job, and runs FFmpeg to `outputs/.<job-id>.part.<ext>`.
7. FFmpeg is started with `-progress pipe:1` and `-nostats`. The source duration is probed first; if available, a goroutine parses progress lines. If not, stdout is drained to avoid blocking the process.
8. On FFmpeg error, the temp file is removed and a generic processing error is returned. The worker stores the error on the job/video.
9. After success, it checks the context again, verifies the temp file exists and is non-empty, then renames it to `outputs/<job-id>.<ext>`.
10. The worker reloads the job and resolves races before committing a terminal state.

### Codec-copy behavior

The processor tracks whether video and audio streams were modified. At output time:

- If video was not modified and no explicit video codec was set, `-c:v copy` is used.
- If audio was not modified and no explicit audio codec was set, `-c:a copy` is used.
- Modified streams are encoded by FFmpeg's defaults for the output container.
- `to_audio` forces an MP3 audio encode and disables video.

So the `.mp4` extension is not a guarantee that every stream was re-encoded. Source codecs that cannot be copied into MP4 can cause processing failures.

### Progress

`parseProgress` reads lines beginning with `out_time_ms=`. Despite the name, FFmpeg reports microseconds here; the code divides by 1,000,000 to get seconds, divides by the source duration, multiplies by 100, and clamps to 100.

`JobStore.UpdateProgress` only updates a job while it is `processing`, then mirrors the value into the video row in the same transaction. Completion explicitly sets job and video progress to 100. Failures and cancellations do not compute a final percentage.

Progress is based on the source duration, not the trimmed or speed-adjusted output duration, so intermediate values can be misleading for those pipelines. Progress writes occur for every parsed progress line, which can be frequent.

## Job and video state transitions

```mermaid
stateDiagram-v2
    [*] --> uploaded: upload
    uploaded --> queued: process request
    queued --> processing: worker claim
    queued --> cancelled: cancel queued job
    processing --> completed: FFmpeg success
    processing --> failed: FFmpeg/service error
    processing --> cancel_requested: cancel running job
    cancel_requested --> cancelled: worker stops
    processing --> queued: graceful shutdown or crash recovery
    processing --> failed: recovery with missing source/invalid operations
    cancel_requested --> cancelled: startup recovery
    completed --> queued: new attempt
    failed --> queued: new attempt
    cancelled --> queued: new attempt
```

The `videos` row mirrors the latest job state. A new attempt clears the video's latest output path but does not delete earlier job outputs. The video-level output routes serve only the latest completed output; job-level routes serve each attempt's own output.

### Active-job constraint

The `jobs` table has a partial unique index:

```sql
CREATE UNIQUE INDEX idx_jobs_one_active_per_video
ON jobs(video_id)
WHERE status IN ('queued', 'processing', 'cancel_requested');
```

This prevents two active attempts for one video even if two requests race. Terminal jobs do not block a new attempt.

## Cancellation

`JobStore.RequestCancel` is a conditional update:

- `queued` → `cancelled`, sets `finished_at`, and clears error.
- `processing` → `cancel_requested`, sets `cancel_requested_at` and leaves `finished_at` unset.
- The video mirrors the corresponding status. Queued cancellation also resets video progress/output.

The manager's `Cancel` method calls `RequestCancel`, then, if the job was running:

- Looks up the per-job context cancel function.
- Calls it immediately if the worker has registered it.
- Otherwise records a pending cancellation so `registerCancel` can trigger it when the worker starts the job.

The worker registers its cancel function before processing and unregisters it afterward. The pending map closes the race where cancellation arrives between claim and registration.

If cancellation and completion race:

- `Complete` only transitions from `processing`. If cancellation wins, completion affects zero rows.
- The worker then removes artifacts and, if the job is `cancel_requested`, marks it `cancelled`.
- `Fail` is also conditional on `processing`, so a stale failure cannot overwrite a cancelled job.

Cancellation of an already terminal or already cancel-requested job returns `409`.

## Shutdown behavior

`main` stops accepting HTTP requests first (with a 15-second shutdown context), then calls `manager.Stop()`.

`manager.Stop()` cancels the manager context and waits for workers. FFmpeg receives the canceled context. After the processing call returns, the worker reloads the job:

- If status is `cancel_requested`, it removes artifacts and marks the job `cancelled`.
- If status is `cancelled`, it removes artifacts.
- If the manager context is canceled, it removes artifacts and requeues the job unless cancellation was requested.
- If processing failed, it removes artifacts and marks `failed`.
- Otherwise it attempts to complete.

This means work that finishes just as shutdown begins may be discarded and requeued rather than saved. That is intentional in the current implementation.

## Recovery

`Manager.Recover()` runs before workers start. It loads jobs in `processing` and `cancel_requested`:

- Removes known artifacts for every such job (`output_path`, standard `.mp4`/`.mp3` outputs, and `.part` files).
- `cancel_requested` jobs move to `cancelled`.
- Processing jobs with a missing source fail with `source file is no longer available`.
- Processing jobs whose saved operations are malformed or empty fail with `invalid saved operations`.
- Other processing jobs are requeued (status `queued`, timestamps reset).

Queued jobs are left alone and are claimed normally. Recovery does not reconcile arbitrary filesystem orphans outside these known paths.

## Cleanup

`StartCleaner` creates a one-hour ticker with no shutdown context. On each tick:

1. Compute `now - 24h`.
2. Query eligible videos: status `completed`, `failed`, or `cancelled`, `videos.created_at < cutoff`, and no associated job that is non-terminal, created at/after the cutoff, or finished at/after the cutoff.
3. For each candidate, call `VideoStore.DeleteExpiredVideo`:
   - Begin a transaction and reload the video row.
   - Revalidate terminal status and `created_at < cutoff`.
   - Load all jobs and revalidate that every job is terminal, `created_at < cutoff`, and `finished_at` is either null or `< cutoff`.
   - Return without deleting if any condition fails.
   - Delete the video row, cascading all job rows through the foreign key, and commit.
4. After the commit, remove every job artifact, the original upload, and the latest video output.

Cleanup is best effort after the database commit: individual file errors are logged. Because rows are deleted before files, a database failure leaves both rows and files intact for a later attempt. A filesystem failure can leave orphaned files, but it does not leave database rows pointing at files that cleanup already removed.

Expiration protects active jobs and recently created or finished job history. A just-queued, just-updated, or just-finished job keeps the video alive until it has been quiet for 24 hours. Uploaded, queued, processing, and cancel-requested videos are never selected. Jobs are not retained independently of their video, so job history disappears when the video expires.

### Cleanup race

The cleaner still starts from a query snapshot, but `DeleteExpiredVideo` rechecks eligibility inside a transaction immediately before deletion. A concurrent queue request either commits first, causing cleanup to skip the now-active/recent job, or loses to the committed delete and receives `ErrVideoNotFound`. Completion and cancellation transitions use the same single SQLite connection, so cleanup cannot delete a terminal video based on a stale status while a finish transition is in progress.

## Database schema

Migrations are recorded in `schema_migrations(version, applied_at)`.

### Migration 1 — `videos`

```sql
CREATE TABLE videos (
    id TEXT PRIMARY KEY,
    original_path TEXT NOT NULL,
    original_name TEXT,
    file_size INTEGER,
    output_path TEXT,
    status TEXT NOT NULL,
    progress REAL DEFAULT 0,
    operations TEXT,
    duration REAL,
    width INTEGER,
    height INTEGER,
    error TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_videos_status ON videos(status);
```

The migration also adds `original_name` and `file_size` if an older database predates those columns.

### Migration 2 — `jobs`

```sql
CREATE TABLE jobs (
    id TEXT PRIMARY KEY,
    video_id TEXT NOT NULL,
    video_name TEXT,
    source_path TEXT NOT NULL,
    operations TEXT NOT NULL,
    status TEXT NOT NULL,
    progress REAL NOT NULL DEFAULT 0,
    output_path TEXT,
    error TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME,
    finished_at DATETIME,
    cancel_requested_at DATETIME,
    FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE
);
CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_jobs_video ON jobs(video_id);
CREATE INDEX idx_jobs_created ON jobs(created_at);
CREATE UNIQUE INDEX idx_jobs_one_active_per_video
ON jobs(video_id)
WHERE status IN ('queued', 'processing', 'cancel_requested');
```

Migration 2 also backfills jobs from legacy videos in `queued`, `processing`, `cancel_requested`, `cancelled`, `completed`, or `failed` states that have no job row. Backfilled IDs are prefixed with `legacy-` (for example `legacy-<video-id>`), and timestamps are derived from the video creation time. An uploaded video is not backfilled.

## File layout

```text
uploads/<video-id><lowercase-ext>      # original upload
outputs/<job-id>.mp4                   # completed video output
outputs/<job-id>.mp3                   # completed audio output
outputs/.<job-id>.part.mp4             # temporary video output
outputs/.<job-id>.part.mp3             # temporary audio output
data/app.db                            # SQLite database
data/app.db-wal / data/app.db-shm      # SQLite WAL sidecars when active
```

The temp and final paths are in the same `outputs/` directory, so `os.Rename` is normally atomic. The service removes any pre-existing temp/final file for the job before starting.

All paths are relative to the process working directory. Starting the server from a different directory changes where `web/`, `uploads/`, `outputs/`, and `data/` resolve.

## Known limitations and race conditions

- No authentication, authorization, rate limiting, quotas, or per-IP limits.
- CORS is fully permissive.
- The server binds all interfaces and there is no TLS.
- Extension and header-size checks happen before saving, while duration, resolution, and stream checks happen after upload and probing. There is no pre-upload reservation or streaming quota system beyond the HTTP body limit.
- FFmpeg runs unsandboxed with no CPU, memory, or wall-clock limit.
- Progress is approximate for trim/speed pipelines.
- Progress writes can be frequent (one transaction per FFmpeg progress line).
- Completion/cancellation races are handled atomically in the database, but a cancellation request that arrives just after completion returns a conflict and does not undo the output.
- Graceful shutdown can discard work that finishes during shutdown and requeue it.
- Cleanup deletes job history and every job output along with the video; there is no independent job retention.
- Filesystem deletion after the cleanup transaction is best effort. A file-permission or I/O failure can leave orphaned files in `uploads/` or `outputs/`; it does not leave a database row for the deleted video.
- Several `VideoStore` methods (`Queue`, `QueueIfUploaded`, `ResetToUploaded`, `ListQueued`, `ListProcessing`) are currently unused by the active request/worker path.
- Video-only files can use `trim`, `speed`, and `flip`; `volume` and `to_audio` fail with explicit missing-audio errors.
- Audio-only files are recognized by the metadata parser but rejected by upload because upload requires a video stream. The frontend contains audio-only preview branches that are unreachable through normal uploads.
