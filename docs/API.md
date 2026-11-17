# Motion API Reference

Base URL for local development:

```text
http://localhost:8080
```

The API is implemented with Go's `net/http` router in `internal/api/router.go`. Except for static frontend files and router-generated method errors, responses are JSON.

## General behavior

- API errors have the shape `{"error":"message"}`.
- Authentication uses a session cookie (`motion_session`, `HttpOnly`, `SameSite=Lax`). All video, job, and edit-project routes require a valid session and return `401 {"error":"unauthorized"}` otherwise.
- Cookie-authenticated state-changing requests (`POST`/`PUT`/`DELETE`) require CSRF protection: a `motion_csrf` cookie plus a matching `X-CSRF-Token` header. `GET /api/auth/csrf` sets the cookie.
- Resources are scoped to the authenticated user. A request for another user's video/job/edit-project returns `404` rather than revealing existence.
- When `REQUIRE_EMAIL_VERIFICATION=true`, video/job/edit-project routes additionally require a verified email and return `403 {"error":"email verification required"}` otherwise.
- `POST /upload` accepts `multipart/form-data` with a file field named `video`.
- Processing requests are JSON. The handler does not require a `Content-Type` header, but clients should send `application/json`.
- Every video/job/edit-project route is also available under an `/api` prefix (for example `GET /api/videos`).
- CORS is restricted to the origins in `TRUSTED_ORIGINS`; credentials are only allowed for trusted origins. `OPTIONS` requests return `200 OK` with an empty body.
- `GET` route patterns also accept `HEAD`, handled by Go's router and the file-serving path.
- Unsupported methods return `405 Method Not Allowed` (plain text, not the JSON error shape).
- Unknown paths fall through to the static file server for `./web`; they may return a plain-text 404 rather than JSON.
- File-serving endpoints support HTTP range requests, including when media is encrypted at rest.
- Video-ID handlers map missing rows to `404 {"error":"video not found"}`. Job handlers distinguish `404` not-found from `500` database failures.

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
  "orientation": 0,
  "audioSourceId": "VIDEO_ID",
  "audioVolume": 1.0,
  "offset": "0",
  "audioStart": "0",
  "audioEnd": "5",
  "clips": [{"videoId": "VIDEO_ID", "start": "0", "end": "2"}]
}
```

Only the fields relevant to `type` are used. Unknown JSON fields are ignored by the decoder. See the root README or [ARCHITECTURE.md](ARCHITECTURE.md) for validation rules. Supported types are `trim`, `slice`, `remove_section`, `speed`, `volume`, `flip`, `to_audio`, `add_audio`, `replace_audio`, and `concat`.

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
- At most 20 operations.
- `to_audio` may appear only once and must be last.
- `concat` must be the only operation and requires 2–20 clips.
- `volume.factor` must be finite and greater than zero; zero is not treated as mute. The same rule is enforced by the service layer.
- `add_audio`/`replace_audio` require `audioSourceId` to reference a video owned by the same user.
- The request body is limited to 1 MiB.
- The source is probed before processing. `trim`, `slice`, `remove_section`, `speed`, `flip`, and `concat` work for video-only sources. `volume`, `to_audio`, `add_audio`, and `replace_audio` require audio and fail the job with an explicit missing-audio error after the request has been accepted.

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
- Maximum request body: `MAX_UPLOAD_BYTES` (default 150 MiB / `157,286,400` bytes)
- Allowed lowercase/uppercase extensions: `.mp4`, `.m4v`, `.mov`, `.mkv`, `.webm`, `.avi`, `.mpeg`, `.mpg`, `.3gp`
- The file must contain a video stream, last at most `MAX_DURATION_SECONDS` (default 300 s), and have width ≤ `MAX_WIDTH` (default 1920) and height ≤ `MAX_HEIGHT` (default 1080).
- Per-user quotas apply: active uploads, source-file count, stored bytes, and the free-disk guard.

Example:

```bash
curl -sS -F "video=@sample.mp4" http://localhost:8080/upload
```

Success `201 Created` with a [video response](#video-response), status `uploaded`.

| Status | Body | Cause |
| --- | --- | --- |
| `201` | video response | Upload accepted |
| `400` | `{"error":"..."}` | Missing/invalid `video` field, malformed multipart data, unsupported or missing extension, genuinely corrupt/unsupported media, duration/resolution/stream failure |
| `401` | `{"error":"unauthorized"}` | No session |
| `403` | `{"error":"verify your email address to continue"}` | Verification required and the account is unverified |
| `413` | `{"error":"file exceeds the 150 MB limit"}` | HTTP body exceeds the limit |
| `429` | `{"error":"..."}` | Per-user upload/storage quota or rate limit exceeded |
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
| `400` | `{"error":"a referenced video is not available"}` | An `audioSourceId`/clip references a video that does not exist or is not owned by the user |
| `401` | `{"error":"unauthorized"}` | No session |
| `403` | `{"error":"verify your email address to continue"}` | Verification required and the account is unverified |
| `404` | `{"error":"video not found"}` | No video row |
| `409` | `{"error":"the original file is no longer available"}` | Original path exists in the database but the file is gone |
| `409` | `{"error":"video already has an active job"}` | Another queued/processing/cancel-requested job exists for the video |
| `429` | `{"error":"too many active jobs; try again later"}` | The user's concurrent-job quota is reached |
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

## `GET /api/config`

Purpose: expose non-sensitive client configuration so the frontend can render correctly.

Public (no authentication). Success `200 OK`:

```json
{
  "requireEmailVerification": false,
  "maxUploadBytes": 157286400,
  "maxDurationSeconds": 300
}
```

---

## Authentication

Session-cookie authentication with CSRF for state changes. Representative endpoints:

| Method | Path | Purpose | Notable responses |
| --- | --- | --- | --- |
| `POST` | `/api/auth/register` | Create an account | `201` created; `400` invalid body/weak password; `409` email in use; `429` rate-limited |
| `POST` | `/api/auth/login` | Start a session | `200`; `401` invalid credentials; `429` rate-limited |
| `POST` | `/api/auth/logout` | End the session | `200` |
| `GET` | `/api/auth/me` | Current user | `200` `{"user":{...}}`; `401` |
| `POST` | `/api/auth/password/change` | Change password (authenticated) | `200`; `400`; `401` |
| `POST` | `/api/auth/password/reset/request` | Request reset email | always `200 {"status":"ok"}` (does not reveal account existence) |
| `POST` | `/api/auth/password/reset` | Complete reset with token | `200`; `400` invalid/expired token |
| `POST` | `/api/auth/verify` | Verify email with token | `200`; `400` missing/invalid/expired token |
| `POST` | `/api/auth/verify/resend` | Resend verification (authenticated) | `200`; `401`; `429` |
| `GET` | `/api/auth/csrf` | Ensure a CSRF cookie is set | `200 {"status":"ok"}` |

When `REQUIRE_EMAIL_VERIFICATION=true`, unverified accounts receive `403 {"error":"verify your email address to continue"}` on video/job/edit-project routes.

---

## `DELETE /videos/{id}`

Purpose: delete a video, all of its jobs, and every associated file.

Path parameter: `id` — video UUID.

Behavior: the database rows are deleted transactionally, then the original, latest output, and per-job artifacts are removed from storage. An active job blocks deletion.

Example:

```bash
curl -sS -X DELETE \
  -H "X-CSRF-Token: <token>" \
  http://localhost:8080/videos/41eb958a-8d33-40c5-830e-53478ca6a304
```

| Status | Body | Cause |
| --- | --- | --- |
| `200` | `{"status":"ok"}` | Deleted |
| `401` | `{"error":"unauthorized"}` | No session |
| `404` | `{"error":"video not found"}` | No video row for this user |
| `409` | `{"error":"cancel the active job before deleting this video"}` | A queued/processing/cancel-requested job exists |
| `500` | `{"error":"failed to delete video"}` | Database failure |

---

## `DELETE /jobs/{id}`

Purpose: delete a job and its output, resetting the video to `uploaded`.

Path parameter: `id` — job UUID.

Example:

```bash
curl -sS -X DELETE \
  -H "X-CSRF-Token: <token>" \
  http://localhost:8080/jobs/a79d9bb0-b404-4ce5-9b1d-4afb16043626
```

| Status | Body | Cause |
| --- | --- | --- |
| `200` | `{"status":"ok"}` | Deleted |
| `401` | `{"error":"unauthorized"}` | No session |
| `404` | `{"error":"job not found"}` | No job row for this user |
| `409` | `{"error":"cancel the job before deleting it"}` | Job is active |
| `500` | `{"error":"failed to delete job"}` | Database failure |

---

## Edit projects

Reusable editing sessions. A project stores the source video and the operations to re-apply.

Schemas:

```json
{
  "id": "PROJECT_ID",
  "videoId": "VIDEO_ID",
  "name": "First cut",
  "request": {"operations": [{"type": "flip", "orientation": 1}]},
  "createdAt": "2026-11-16T00:00:00Z",
  "updatedAt": "2026-11-16T00:00:00Z"
}
```

| Method | Path | Purpose | Notable responses |
| --- | --- | --- | --- |
| `GET` | `/api/edit-projects` | List the user's projects | `200` array |
| `POST` | `/api/edit-projects` | Create a project | `201`; `400` missing name/invalid request; `404` video not found |
| `GET` | `/api/edit-projects/{id}` | Get one project | `200`; `404` |
| `PUT` | `/api/edit-projects/{id}` | Update name and/or operations | `200`; `400`; `404`; `409` source video unavailable |
| `DELETE` | `/api/edit-projects/{id}` | Delete a project | `200 {"status":"ok"}`; `404` |

Create/update request body: `{"videoId":"...","name":"...","request":{"operations":[...]}}` (`videoId` only on create). Names are limited to 200 characters.

---

## Timeline projects

Multitrack timeline projects used by the dedicated editor page (`/editor.html`). A
project stores tracks, clips and render settings as one JSON document plus a
monotonic `revision` used for optimistic concurrency control.

Schemas:

```json
{
  "id": "PROJECT_ID",
  "name": "First cut",
  "schemaVersion": 1,
  "revision": 3,
  "render": {"width": 1920, "height": 1080, "fps": 30},
  "tracks": [
    {
      "id": "track-id",
      "type": "video",
      "name": "Video 1",
      "order": 0,
      "visible": true,
      "muted": false,
      "clips": [
        {
          "id": "clip-id",
          "kind": "video",
          "assetId": "VIDEO_ID",
          "sourceIn": 5.0,
          "sourceOut": 15.0,
          "timelineStart": 0.0,
          "volume": 1.0,
          "opacity": 1.0,
          "x": 0.0,
          "y": 0.0,
          "scale": 1.0
        }
      ]
    }
  ]
}
```

Track types are `video`, `audio`, `image` and `text`; clip kinds must match the
track type. `sourceIn`/`sourceOut` select the asset range and `timelineStart` is
where the clip begins on the timeline. Omitted `volume`, `opacity` and `scale`
default to `1`, and an omitted track is visible and unmuted. Text clips carry a
`text` field instead of an asset.

| Method | Path | Purpose | Notable responses |
| --- | --- | --- | --- |
| `GET` | `/api/timeline-projects` | List the user's timelines | `200` array |
| `POST` | `/api/timeline-projects` | Create a timeline | `201`; `400` invalid (missing name, unsupported schema, too many tracks/clips) |
| `GET` | `/api/timeline-projects/{id}` | Get one timeline | `200`; `404` |
| `PUT` | `/api/timeline-projects/{id}` | Save a timeline | `200`; `400`; `404`; `409` stale `revision` |
| `DELETE` | `/api/timeline-projects/{id}` | Delete a timeline | `200 {"status":"ok"}`; `404` |

Create/update request body: the project object above. The server assigns `id`,
`revision`, `createdAt` and `updatedAt`. Saving is lenient about in-progress
structure (empty tracks/clips, overlaps) so an editor never loses work, but
rejects missing names, unsupported schema versions, and over-limit track/clip
counts. A `PUT` whose `revision` no longer matches the stored row returns `409`.

### `POST /api/timeline/render`

Validates a timeline and queues a normal render job, reusing the existing queue,
progress, cancellation and history machinery.

Request body: `{"projectId":"..."}` to render a saved timeline, or
`{"project": { ... }}` to render an unsaved one.

Responses:

- `202` — the created job (same schema as `POST /videos/{id}/process`).
- `400` — validation problem; body includes `validationErrors` with `code`,
  `message`, `trackId` and `clipId` for each issue (bad timestamps, overlaps,
  missing/unowned assets, unsupported track configuration).
- `404` — `projectId` not found.
- `409` — the primary video already has an active job, or its original file is gone.
- `422` — the timeline is valid but cannot be expressed (for example no video
  clip starts at the timeline origin). The message states which construct is
  unsupported.

The renderer picks one of two shapes automatically:

- **Simple path** (proven pipeline): one visible video track whose clips start at
  the origin and are contiguous. One clip becomes a `slice`, several become a
  `concat`, and audio clips become `add_audio` operations.
- **Compose path**: anything else — several video tracks (picture-in-picture),
  text overlays, timeline gaps, or a multi-clip join that also carries audio. The
  job then contains a single `compose` operation describing the ordered visual
  layers and the audio mix; the worker turns it into one FFmpeg
  `filter_complex` graph (black canvas + `overlay` layers + `drawtext` + `amix`).

Compose operation shape (abridged):

```json
{
  "type": "compose",
  "compose": {
    "width": 1920, "height": 1080, "fps": 30, "duration": "12.5",
    "layers": [
      {"kind": "video", "videoId": "BASE_ID", "base": true,
       "sourceIn": "1", "sourceOut": "6", "timelineStart": "0", "timelineEnd": "5",
       "scale": 1, "opacity": 1},
      {"kind": "video", "videoId": "PIP_ID", "sourceIn": "0", "sourceOut": "3",
       "timelineStart": "1", "timelineEnd": "4", "x": 380, "y": 20,
       "scale": 0.35, "opacity": 0.9},
      {"kind": "text", "text": "Title", "timelineStart": "0", "timelineEnd": "5",
       "x": 20, "y": 20, "scale": 1.2, "opacity": 1}
    ],
    "audio": [
      {"videoId": "BASE_ID", "sourceIn": "0", "sourceOut": "5",
       "timelineStart": "0", "volume": 0.5}
    ],
    "hasAudio": true
  }
}
```

Every referenced asset is resolved against the requesting user, so a crafted
project can never read another account's media.

## Preview assets

Derived, cacheable media used by the editor timeline. Each endpoint requires
authentication and ownership of the video.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/videos/{id}/thumbnails` | Filmstrip metadata: `count`, `tileWidth`, `tileHeight`, `width`, `height`, `duration`, `spriteUrl` |
| `GET` | `/api/videos/{id}/thumbnails/sprite?count=N` | Single-row JPEG contact sheet of `N` frames |
| `GET` | `/api/videos/{id}/waveform?buckets=N` | JSON `{buckets,duration,audio,peaks[]}`; `audio:false` when the source has no audio |

The `count`/`buckets` query parameters are clamped to safe ranges. Results are
written under `MOTION_PREVIEW_DIR` and reused on later requests; deleting a video
removes its cached previews. Non-`/api` aliases exist for all three routes.

---

## Static frontend

When the server is created with a non-empty static directory (the normal application passes `./web`), `GET /` serves `web/index.html` and other files by path. There is no SPA fallback: requests for nonexistent files return the file server's 404.

The frontend calls the API from the same origin by default. Cross-origin access is limited to the origins listed in `TRUSTED_ORIGINS`; a separate frontend origin must be added there explicitly.
