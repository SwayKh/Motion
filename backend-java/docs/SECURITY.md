# Security Notes

## Implemented protections

- **Passwords**: bcrypt, default cost 10; policy-checked (length, character classes).
- **Sessions**: random 256-bit tokens stored **hashed**; `HttpOnly`, `SameSite=Lax`, `Secure` in production; idle + absolute expiry; rotation on login; revocation on logout and password change.
- **Reset/verification tokens**: random, stored hashed, single-use, expiring, consumed atomically; reset requests do not reveal whether an account exists.
- **CSRF**: double-submit cookie (`motion_csrf` + `X-CSRF-Token` header) enforced on state-changing requests made with a session; safe methods ensure the cookie exists.
- **CORS**: credentialed requests only from configured `TRUSTED_ORIGINS`; untrusted preflights rejected.
- **Ownership**: every media, job, preview, and project operation is scoped to the authenticated owner; other users' resources return 404 (no existence leak).
- **Uploads**: extension allowlist, size/duration/dimension limits, real FFprobe validation, per-user and global quotas; uploads are rate-limited (`RATE_LIMIT_UPLOAD`).
- **Filesystem**: all stored/served/deleted paths are containment-checked against the managed roots **including symlink resolution** (a symlinked file or directory cannot escape `uploads/`/`outputs/`); scratch dirs are 0700; media files 0600.
- **FFmpeg**: invoked through `ProcessBuilder` argument arrays (never a shell); wall-clock timeout (default 2 h, `MOTION_FFMPEG_TIMEOUT`); process trees terminated on cancel/shutdown; stderr captured and truncated before logging.
- **Encryption at rest**: AES-256-GCM (chunked, authenticated; header bound as AAD), per-file data keys wrapped by the master key, seekable authenticated reads, fail-closed on invalid key. See [STORAGE_ENCRYPTION.md](STORAGE_ENCRYPTION.md).
- **Errors**: generic messages, no stack traces or internal paths in responses; logs avoid secrets.
- **Rate limits**: login/register/reset/verify/upload per client IP with trusted-proxy resolution.
- **Container**: non-root UID 10001, minimal runtime image, no host port published (proxy-only).

## Operational requirements

- Terminate TLS at the reverse proxy; keep `SECURE_COOKIES=true` and set `TRUSTED_PROXIES`.
- Keep `.env` (contains `MOTION_ENCRYPTION_KEY` and SMTP credentials) out of version control; it is already excluded via `.gitignore` practices — verify yours.
- Back up the encryption key together with the data (see DEPLOYMENT.md).
- Single instance only: SQLite plus the lease-based job queue are not designed for multi-node operation.

## Known limitations

- **Dependency scan status (2026-11-18, OSV-Scanner 2.2.3 over a CycloneDX SBOM of all 95
  runtime dependencies):** all 20 reachable findings were remediated by patch-level upgrades
  (Tomcat 10.1.55→10.1.59, Jackson 2.21.4→2.21.7, log4j-api 2.24.3→2.25.5, commons-lang3
  3.17.0→3.18.0; full 213-test suite + 39/39 browser E2E re-verified afterwards). Two
  critical-severity advisories remain on `spring-webmvc 6.2.19` with fixes only in Spring
  Framework 7.0.9: CVE-2026-47890 (SSE stream corruption while rendering fragments) and
  CVE-2026-47884 (XsltView improper path limitation). **Neither is reachable in Motion's
  configuration** (Motion serves no Server-Sent Events and no XSLT views); remediation
  requires the Spring Boot 4 / Spring Framework 7 migration and is scheduled work, not a
  configuration change. Re-scan after that migration.
- No account deletion / data-export tooling yet.
- No CAPTCHA or 2FA; the product targets small self-hosted deployments behind a private or invite-only surface.
- Key rotation is not supported; plan key handling before first production upload.
- The dev email outbox (`DEV_EMAIL_OUTBOX=true`) writes password-reset tokens to a file — development convenience only; never enable in production.
