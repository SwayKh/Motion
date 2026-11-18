# HTTP API Reference

All endpoints are JSON (unless streaming binary) and require a session cookie except where noted. State-changing requests made with a session require the `X-CSRF-Token` header matching the `motion_csrf` cookie (the frontend does this automatically). Errors are `{"error":"<message>"}`.

Base path: everything lives under `/api` (plus `/health`).

## Health & config (public)

| Method | Path | Response |
|---|---|---|
| GET | `/health` | `{"status":"ok"}` |
| GET | `/api/config` | `{"maxDurationSeconds":300,"maxUploadBytes":157286400,"requireEmailVerification":false}` |

## Authentication

| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | `{email,password,passwordConfirm}` | 201 with user; creates session |
| POST | `/api/auth/login` | `{email,password}` | 200 with user; rotates session |
| POST | `/api/auth/logout` | — | Revokes session |
| GET | `/api/auth/me` | — | Current user (401 anonymous) |
| GET | `/api/auth/csrf` | — | Ensures CSRF cookie; `{"status":"ok"}` |
| POST | `/api/auth/password/change` | `{currentPassword,newPassword}` | Authenticated |
| POST | `/api/auth/password/reset/request` | `{email}` | Always `{"status":"ok"}` (no account enumeration) |
| POST | `/api/auth/password/reset` | `{token,newPassword,newPasswordConfirm}` | Consumes hashed token atomically |
| POST | `/api/auth/verify` | `{token}` | Public; rate-limited |
| POST | `/api/auth/verify/resend` | — | Authenticated; rate-limited |

## Media

| Method | Path | Notes |
|---|---|---|
| GET | `/api/videos` | List the caller's videos |
| POST | `/api/upload` | Multipart field `video`; validated + probed; 201 with metadata; rate-limited |
| GET | `/api/videos/{id}` | Metadata |
| GET | `/api/videos/{id}/original` | Stream original (range/conditional supported) |
| GET | `/api/videos/{id}/output` | Stream latest output; 404 until one exists |
| GET | `/api/videos/{id}/download` | Attachment download of latest output |
| DELETE | `/api/videos/{id}` | 409 while an active job exists |

## Processing & jobs

| Method | Path | Notes |
|---|---|---|
| POST | `/api/videos/{id}/process` | `{operations:[...]}`; 202 with job; 400 validation; 409 active job; 429 quota |
| GET | `/api/jobs?videoId=` | Job history |
| GET | `/api/jobs/{id}` | Job status/progress/error |
| POST | `/api/jobs/{id}/cancel` | Queued→cancelled; running→`cancel_requested`; 409 when terminal |
| DELETE | `/api/jobs/{id}` | 409 while active; removes artifacts and resets video output |
| GET | `/api/jobs/{id}/output` | Inline stream of that job's output |
| GET | `/api/jobs/{id}/download` | Attachment download |

Operations (`type`): `trim` (start/end), `slice`, `remove_section`, `speed` (factor), `flip` (orientation 0/1), `volume`, `to_audio`, `concat`, `add_audio`/`replace_audio` (audioSourceId, audioVolume, offset, audioStart/audioEnd). Text/video overlay composition is produced by the timeline renderer.

## Previews

| Method | Path | Notes |
|---|---|---|
| GET | `/api/videos/{id}/thumbnails` | Filmstrip metadata + sprite URL |
| GET | `/api/videos/{id}/thumbnails/sprite` | JPEG sprite (range-supported) |
| GET | `/api/videos/{id}/waveform?buckets=` | Normalized audio peaks |

## Timeline projects

| Method | Path | Notes |
|---|---|---|
| GET/POST | `/api/timeline-projects` | List / create (revision 1) |
| GET/PUT/DELETE | `/api/timeline-projects/{id}` | Read / update (revision-checked, 409 on stale) / delete |
| POST | `/api/timeline/render` | `{projectId}` or inline `{project}`; 202 with job; validation errors as 400 with `validationErrors[]` |

Projects store tracks/clips/render settings as a document; rendering uses an immutable snapshot taken at submission.

## Response shapes (examples)

```json
// GET /api/videos/{id}
{"id":"…","name":"clip.mp4","status":"uploaded","progress":0,"duration":2.0,
 "width":320,"height":240,"fileSize":43972,"createdAt":"…","hasOutput":false}

// GET /api/jobs/{id}
{"id":"…","videoId":"…","videoName":"clip.mp4","status":"processing","progress":42.5,
 "operations":[…],"error":null,"createdAt":"…","startedAt":"…","hasOutput":false}
```

Job statuses: `queued`, `processing`, `cancel_requested` (wire state), `completed`, `failed`, `cancelled`.
