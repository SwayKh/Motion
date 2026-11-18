# Configuration Reference

All configuration is environment-driven. The Docker image sets sensible production defaults; the `.env` file (see `.env.example`) supplies the rest through Compose `env_file`.

## Application

| Variable | Default | Meaning |
|---|---|---|
| `APP_ENV` | `production` (image) | Environment label |
| `SPRING_PROFILES_ACTIVE` | `production` (image) | `local`, `test`, or `production` |
| `PORT` | `8080` | HTTP listen port |
| `BASE_URL` | `http://localhost:8080` | Public base URL used in emails |
| `SPRINGDOC_ENABLED` | `false` | Opt-in API docs UI (development only) |

## Database

| Variable | Default | Notes |
|---|---|---|
| `MOTION_JDBC_URL` | `jdbc:sqlite:${MOTION_DATA_DIR}/app.db?busy_timeout=5000&journal_mode=WAL` | Leave default |
| `MOTION_DB_POOL_SIZE` | `1` | Keep 1: SQLite is single-writer |
| `MOTION_FLYWAY_ENABLED` | `true` | Schema migrations on startup |

The database is **single-instance**. Never point two application instances at one database.

## Sessions & cookies

| Variable | Default |
|---|---|
| `SESSION_TTL` | `24h` (absolute expiry) |
| `SESSION_IDLE_TTL` | `2h` (inactivity expiry) |
| `SECURE_COOKIES` | `true` in the image |

## CORS / proxies

| Variable | Default | Notes |
|---|---|---|
| `TRUSTED_ORIGINS` | *(empty)* | Comma-separated `scheme://host[:port]` origins allowed for credentialed browser requests |
| `TRUSTED_PROXIES` | *(empty)* | Comma-separated IPs/CIDRs whose `X-Forwarded-For` is trusted for rate limiting |

## Rate limits (per client IP, per window)

| Variable | Default | Window |
|---|---|---|
| `RATE_LIMIT_LOGIN` | 10 | 1 min |
| `RATE_LIMIT_REGISTER` | 5 | 1 min |
| `RATE_LIMIT_RESET` | 3 | 1 h |
| `RATE_LIMIT_VERIFY` | 5 | 1 h (covers verify and resend) |
| `RATE_LIMIT_UPLOAD` | 30 | 1 min |

## Email

| Variable | Default | Notes |
|---|---|---|
| `SMTP_HOST` | *(empty = no mail)* | e.g. `smtp.gmail.com` |
| `SMTP_PORT` | `587` | |
| `SMTP_USER` / `SMTP_PASS` | *(empty)* | Gmail: use an App Password |
| `SMTP_FROM` | *(empty)* | |
| `REQUIRE_EMAIL_VERIFICATION` | `false` | New accounts must verify before upload/process |
| `EMAIL_VERIFICATION_TTL` | `24h` | |
| `DEV_EMAIL_OUTBOX` | `false` (production) | Writes emails to `data/outbox.log` — development only |

## Storage

| Variable | Default (image) | Notes |
|---|---|---|
| `MOTION_DATA_DIR` | `/app/data` | Database, previews, temp scratch |
| `MOTION_UPLOAD_DIR` | `/app/uploads` | Original media |
| `MOTION_OUTPUT_DIR` | `/app/outputs` | Processed outputs |
| `MOTION_TEMP_DIR` | `<data>/tmp` | Private scratch (0700) |
| `MOTION_PREVIEW_DIR` | `<data>/previews` | Thumbnail/waveform cache |
| `MOTION_STATIC_DIR` | `/app/web` | Bundled frontend |
| `MOTION_MULTIPART_MAX_FILE_SIZE` | `150MB` | Multipart ceiling |

## Encryption at rest

| Variable | Notes |
|---|---|
| `MOTION_ENCRYPTION_KEY` | Base64 32-byte key (`openssl rand -base64 32`). Empty disables encryption. Invalid values abort startup. Media is AES-256-GCM encrypted per file with per-file keys wrapped by this master key. **Back it up with your data; losing it makes encrypted media unreadable. No rotation tooling.** |

## Retention / cleanup

| Variable | Default | Notes |
|---|---|---|
| `RETENTION` | `24h` | Terminal videos older than this are deleted with their media; `0` disables the sweep |
| `CLEANUP_INTERVAL` | `1h` | Sweep cadence |

## Upload validation & quotas

| Variable | Default |
|---|---|
| `MAX_UPLOAD_BYTES` | `157286400` (150 MB) |
| `MAX_DURATION_SECONDS` | `300` |
| `MAX_WIDTH` / `MAX_HEIGHT` | `1920` / `1080` |
| `MAX_ACTIVE_UPLOADS_PER_USER` | `3` |
| `MAX_SOURCE_FILES_PER_USER` | `50` |
| `MAX_OUTPUTS_PER_USER` | `200` |
| `MAX_STORED_BYTES_PER_USER` | `5368709120` (5 GiB) |
| `MAX_CONCURRENT_JOBS_PER_USER` | `2` |
| `MAX_GLOBAL_CONCURRENT_JOBS` | `3` |
| `MAX_TOTAL_STORAGE_BYTES` | `53687091200` (50 GiB) |
| `MIN_FREE_DISK_BYTES` | `1073741824` |

## Job workers

| Variable | Default | Notes |
|---|---|---|
| `MOTION_JOB_WORKERS` | `3` | Concurrent FFmpeg workers |
| `MOTION_JOB_QUEUE_CAPACITY` | `100` | Bounded internal wake queue |
| `MOTION_MAX_QUEUED_JOBS` | `200` | Durable queue high-water mark |
| `MOTION_JOB_POLL_INTERVAL` | `2s` | |
| `MOTION_JOB_PROGRESS_INTERVAL` | `500ms` | |
| `MOTION_JOB_SHUTDOWN_TIMEOUT` | `30s` | Graceful-stop budget for workers |
| `MOTION_FFMPEG_TIMEOUT` | `2h` | Wall-clock limit for a single FFmpeg execution |
