# Motion API Reference

Base URL for local development:

```text
http://localhost:8080
```

The API is implemented with Go's `net/http` router in `internal/api/router.go`. Except for static frontend files and router-generated method errors, responses are JSON.

## General behavior

- API errors have the shape `{"error":"message"}`.
- `POST /upload` accepts `multipart/form-data` with a file field named `video`.
- Processing requests are JSON. The handler does not require a `Content-Type` header, but clients should send `application/json`.
- There is no authentication, API key, cookie, or authorization header.
- CORS headers are `Access-Control-Allow-Origin: *`, `Access-Control-Allow-Methods: *`, and `Access-Control-Allow-Headers: *`.
- Every `OPTIONS` request returns `200 OK` with an empty body because of the CORS middleware.
- `GET` route patterns also accept `HEAD`, handled by Go's router and `http.ServeFile`.
- Unsupported methods return `405 Method Not Allowed` (plain text, not the JSON error shape).
- Unknown paths fall through to the static file server for `./web`; they may return a plain-text 404 rather than JSON.
- File-serving endpoints support HTTP range requests through `http.ServeFile`.
- Video-ID handlers map every store error (including unexpected database failures) to `404 {"error":"video not found"}`. Job handlers distinguish `404` not-found from `500` database failures.

Representative IDs used in examples:

```text
VIDEO_ID=41eb958a-8d33-40c5-830e-53478ca6a304
JOB_ID=a79d9bb0-b404-4ce5-9b1d-4afb16043626
```

## Shared schemas

### Video response

Returned by upload, video listing, and single-video lookup.

```json
{
  "id": "41eb958a-8d33-40c5-830e-53478ca6a304",
  "name": "sample.mp4",
  "status": "uploaded",
  "progress": 0,
  "duration": 30.348481,
  "width": 640,
  "height": 480,
  "fileSize": 4550103,
  "createdAt": "2026-10-26T17:58:03Z",
  "hasOriginal": true,
  "hasOutput": false
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `id` | string | Video UUID |
| `name` | string | Original client filename, or the basename of the stored path if absent |
| `status` | string | Video status: `uploaded`, `queued`, `processing`, `cancel_requested`, `cancelled`, `completed`, or `failed` |
| `progress` | number | Clamped to 0–100 |
| `duration` | number | Seconds from FFprobe |
| `width`, `height` | number | Pixel dimensions from the first video stream |
| `fileSize` | number | Bytes written for the original upload |
| `error` | string | Omitted when empty |
| `createdAt` | string | RFC 3339 timestamp |
| `hasOriginal` | boolean | True when the database has a non-empty original path; does not stat the file |
| `hasOutput` | boolean | True only when status is `completed` and an output path exists in the database; does not stat the file |
| `outputType` | string | Omitted without an output path; `"audio"` for `.mp3`, otherwise `"video"` |

Filesystem paths are intentionally not exposed.

### Job response

Returned by processing, job lookup, job listing, and cancellation.

```json
{
  "id": "a79d9bb0-b404-4ce5-9b1d-4afb16043626",
  "videoId": "41eb958a-8d33-40c5-830e-53478ca6a304",
  "videoName": "sample.mp4",
  "status": "queued",
  "progress": 0,
  "operations": [
    {"type": "trim", "start": "00:00:00", "end": "00:00:02"},
    {"type": "speed", "factor": 1.5}
  ],
  "createdAt": "2026-10-26T17:58:03.415Z",
  "hasSource": true,
  "hasOutput": false
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `id` | string | Job UUID |
| `videoId` | string | Source video ID |
| `videoName` | string | Filename snapshot when the job was created |
| `status` | string | `queued`, `processing`, `cancel_requested`, `cancelled`, `completed`, or `failed` |
| `progress` | number | Clamped to 0–100 |
| `error` | string | Omitted when empty |
| `operations` | array | Parsed from the stored request; an empty array if stored JSON cannot be parsed |
| `createdAt` | string | RFC 3339 timestamp |
| `startedAt` | string | Omitted until claimed |
| `finishedAt` | string | Omitted until terminal |
| `cancelRequestedAt` | string | Omitted unless cancellation of a running job was requested |
| `hasSource` | boolean | True when the snapshotted source path currently exists |
| `hasOutput` | boolean | True only when status is `completed` and an output path exists in the database; does not stat the file |
| `outputType` | string | Omitted without an output path; `"audio"` for `.mp3`, otherwise `"video"` |

### Operation object

```json
{
  "type": "trim",
  "start": "00:00:00",
  "end": "00:00:02",
  "factor": 1.5,
  "orientation": 0
}
```

Only the fields relevant to `type` are used. Unknown JSON fields are ignored by the decoder. See the root README or [ARCHITECTURE.md](ARCHITECTURE.md) for validation rules.

### Process request

```json
{
  "operations": [
    {"type": "trim", "start": "00:00:00", "end": "00:00:02"},
    {"type": "speed", "factor": 1.5}
  ]
}
```

Constraints:

- At least one operation is required.
- At most 10 operations.
- `to_audio` may appear only once and must be last.
- `volume.factor` must be finite and greater than zero; zero is not treated as mute. The same rule is enforced by the service layer.
- The request body is limited to 1 MiB.
- The source is probed before processing. `trim`, `speed`, and `flip` work for video-only sources. `volume` and `to_audio` require audio and fail the job with an explicit missing-audio error after the request has been accepted.

---

## `GET /health`

Purpose: liveness check.

Response `200 OK`:

```json
{"status":"ok"}
```

Example:

```bash
curl -s http://localhost:8080/health
```

No error responses are produced by the handler.

---

## `POST /upload`

Purpose: validate, store, probe, and persist a video upload.

Request:

- Content type: `multipart/form-data`
- Required file field: `video`
- Maximum request body: 150 MiB (`157,286,400` bytes)
- Allowed lowercase/uppercase extensions: `.mp4`, `.m4v`, `.mov`, `.mkv`, `.webm`, `.avi`, `.mpeg`, `.mpg`, `.3gp`
- The file must contain a video stream, last at most 300 seconds, and have width ≤ 1920 and height ≤ 1080.

Example:

```bash
curl -sS -F "video=@sample.mp4" http://localhost:8080/upload
```

Success `201 Created` with a [video response](#video-response), status `uploaded`.

| Status | Body | Cause |
| --- | --- | --- |
| `201` | video response | Upload accepted |
| `400` | `{"error":"..."}` | Missing/invalid `video` field, malformed multipart data, unsupported or missing extension, genuinely corrupt/unsupported media, duration > 300 s, dimensions > 1920×1080, or no video stream |
| `413` | `{"error":"file exceeds the 150 MB limit"}` | HTTP body exceeds the limit |
| `500` | `{"error":"failed to process upload"}` | The server could not save the file, FFprobe could not start, metadata parsing failed, or the database insert failed |
| `405` | plain text | Method other than `POST` |

The upload is written to `uploads/<video-id><lowercase-extension>`. Failed uploads remove the saved file and do not create a database row. Client mistakes return a useful `400` message. Storage, probing-infrastructure, metadata-parser, and database failures return a generic `500` without filesystem paths or internal error details; the server log retains the diagnostic cause.

---

## `GET /videos`

Purpose: list all videos, newest first.

Example:

```bash
curl -s http://localhost:8080/videos
```

Success `200 OK` with an array of [video responses](#video-response). An empty library returns `[]`.

| Status | Body | Cause |
| --- | --- | --- |
| `200` | array | Success |
| `500` | `{"error":"failed to load video library"}` | Database query failure |

No pagination or query parameters are supported.

---

## `GET /videos/{id}`

Purpose: fetch one video's public metadata.

Path parameter: `id` — video UUID.

Example:

```bash
curl -s http://localhost:8080/videos/41eb958a-8d33-40c5-830e-53478ca6a304
```

Success `200 OK` with a [video response](#video-response).

| Status | Body | Cause |
| --- | --- | --- |
| `200` | video response | Success |
| `404` | `{"error":"video not found"}` | No row matches the ID; any store error is also mapped to 404 |

---

## `GET /videos/{id}/original`

Purpose: serve the uploaded original inline.

Path parameter: `id` — video UUID.

Example:

```bash
curl -I http://localhost:8080/videos/41eb958a-8d33-40c5-830e-53478ca6a304/original
```

Success `200 OK` with the original file. `Content-Disposition` is `inline` with the original filename. Range requests are supported by `http.ServeFile`.

| Status | Body | Cause |
| --- | --- | --- |
| `200` | file bytes | Success |
| `404` | `{"error":"video not found"}` | No video row |
| `404` | `{"error":"original file is not available"}` | Empty original path |
| `404` | `{"error":"file is no longer available"}` | File is missing or is a directory |

---

## `GET /videos/{id}/output`

Purpose: serve the latest completed output inline.

Path parameter: `id` — video UUID.

Example:

```bash
curl -I http://localhost:8080/videos/41eb958a-8d33-40c5-830e-53478ca6a304/output
```

Success `200 OK` with the file. The download name is based on the original name: `<base>-edited<ext>`.

| Status | Body | Cause |
| --- | --- | --- |
| `200` | file bytes | Success |
| `404` | `{"error":"video not found"}` | No video row |
| `404` | `{"error":"processed output is not available"}` | Video is not `completed` or has no output path |
| `404` | `{"error":"file is no longer available"}` | Output file is missing |

---

## `GET /videos/{id}/download`

Purpose: download the latest completed output as an attachment.

Path parameter: `id` — video UUID.

Example:

```bash
curl -L -o edited.mp4 \
  http://localhost:8080/videos/41eb958a-8d33-40c5-830e-53478ca6a304/download
```

Success `200 OK` with the file and `Content-Disposition: attachment`.

Error responses are the same as [`GET /videos/{id}/output`](#get-videosidoutput).

---

## `POST /videos/{id}/process`

Purpose: validate operations, create a persistent queued job, and notify the manager.

Path parameter: `id` — video UUID.

Request body: [process request](#process-request), maximum 1 MiB.

Example:

```bash
curl -sS -X POST \
  -H "Content-Type: application/json" \
  -d '{"operations":[{"type":"trim","start":"00:00:00","end":"00:00:02"},{"type":"speed","factor":1.5}]}' \
  http://localhost:8080/videos/41eb958a-8d33-40c5-830e-53478ca6a304/process
```

Success `202 Accepted` with a [job response](#job-response), status `queued`.

| Status | Body | Cause |
| --- | --- | --- |
| `202` | job response | Job queued |
| `400` | `{"error":"invalid request body"}` | Body is not valid JSON or exceeds the 1 MiB limit |
| `400` | `{"error":"..."}` | Validation failure; messages identify the operation index, e.g. `operation 1 flip invalid: ...` |
| `404` | `{"error":"video not found"}` | No video row |
| `409` | `{"error":"the original file is no longer available"}` | Original path exists in the database but the file is gone |
| `409` | `{"error":"video already has an active job"}` | Another queued/processing/cancel-requested job exists for the video |
| `500` | `{"error":"failed to save processing request"}` | Stored job JSON could not be marshaled |
| `500` | `{"error":"failed to queue video"}` | Database failure while creating the job |

Validation details are in `internal/validation/validate.go`. A terminal job does not block a new attempt; only an active job does.

---

## `GET /jobs`

Purpose: list recent jobs or all jobs for one video.

Query parameter:

- `videoId` (optional) — return only jobs for this video.

Without `videoId`, returns the 200 newest jobs ordered by `created_at DESC, id DESC`. With `videoId`, returns every job for that video in the same order. There is no pagination.

Example:

```bash
curl -s http://localhost:8080/jobs
curl -s "http://localhost:8080/jobs?videoId=41eb958a-8d33-40c5-830e-53478ca6a304"
```

Success `200 OK` with an array of [job responses](#job-response). An unknown `videoId` returns `[]`.

| Status | Body | Cause |
| --- | --- | --- |
| `200` | array | Success |
| `500` | `{"error":"failed to load job history"}` | Database failure |

---

## `GET /jobs/{id}`

Purpose: fetch one job and its progress.

Path parameter: `id` — job UUID.

Example:

```bash
curl -s http://localhost:8080/jobs/a79d9bb0-b404-4ce5-9b1d-4afb16043626
```

Success `200 OK` with a [job response](#job-response).

| Status | Body | Cause |
| --- | --- | --- |
| `200` | job response | Success |
| `404` | `{"error":"job not found"}` | No job row |
| `500` | `{"error":"failed to load job"}` | Other database failure |

---

## `POST /jobs/{id}/cancel`

Purpose: cancel a job.

Path parameter: `id` — job UUID.

No request body is required.

Behavior:

- A `queued` job becomes `cancelled` immediately, gets `finished_at`, and its video becomes `cancelled`.
- A `processing` job becomes `cancel_requested`; the manager interrupts the running FFmpeg context. The worker later removes partial artifacts and marks the job `cancelled`.
- A terminal job (`completed`, `failed`, `cancelled`) or a job already in `cancel_requested` cannot be cancelled again.

Example:

```bash
curl -sS -X POST \
  http://localhost:8080/jobs/a79d9bb0-b404-4ce5-9b1d-4afb16043626/cancel
```

Success `200 OK` with the updated [job response](#job-response); status is `cancelled` or `cancel_requested`.

| Status | Body | Cause |
| --- | --- | --- |
| `200` | job response | Cancellation applied or requested |
| `404` | `{"error":"job not found"}` | No job row |
| `409` | `{"error":"job has already finished"}` | Job is no longer queued or processing |
| `500` | `{"error":"failed to cancel job"}` | Database failure |

---

## `GET /jobs/{id}/output`

Purpose: preview a specific completed job's output inline. This is how earlier attempts remain accessible after a newer job has run.

Path parameter: `id` — job UUID.

Example:

```bash
curl -I http://localhost:8080/jobs/a79d9bb0-b404-4ce5-9b1d-4afb16043626/output
```

Success `200 OK` with the file. The download name is `<video-base>-edited-<first-8-job-id><ext>`.

| Status | Body | Cause |
| --- | --- | --- |
| `200` | file bytes | Success |
| `404` | `{"error":"job not found"}` | No job row |
| `404` | `{"error":"processed output is not available"}` | Job is not `completed` or has no output path |
| `404` | `{"error":"file is no longer available"}` | Output file is missing |
| `500` | `{"error":"failed to load job"}` | Database failure while loading the job |

---

## `GET /jobs/{id}/download`

Purpose: download a specific completed job's output as an attachment.

Path parameter: `id` — job UUID.

Example:

```bash
curl -L -o edited.mp4 \
  http://localhost:8080/jobs/a79d9bb0-b404-4ce5-9b1d-4afb16043626/download
```

Success `200 OK` with `Content-Disposition: attachment`. Error responses are the same as [`GET /jobs/{id}/output`](#get-jobsidoutput).

---

## Static frontend

When the server is created with a non-empty static directory (the normal application passes `./web`), `GET /` serves `web/index.html` and other files by path. There is no SPA fallback: requests for nonexistent files return the file server's 404.

The frontend calls the API from the same origin by default. The permissive CORS policy would also allow a separate frontend origin, but no such deployment is configured.
