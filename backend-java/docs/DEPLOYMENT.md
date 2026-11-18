# Deployment Guide

Motion (Java) is a single Spring Boot application packaged as a Docker image. This guide covers installation, reverse-proxy setup, email, backups, and upgrades.

## Requirements

- Docker + Docker Compose
- A reverse proxy terminating TLS (Caddy, nginx, …) on a shared Docker network
- FFmpeg and FFprobe are built into the image; nothing extra to install

## Install

```bash
# once: shared network for the proxy
docker network create homelab

cp backend-java/.env.example .env     # then edit (see below)
docker compose -f docker-compose.java.yml --profile java up -d --build
docker compose -f docker-compose.java.yml logs -f motion-java   # watch startup
```

First boot: Flyway creates the schema on the empty `motion_java_*` volumes automatically. Verify:

```bash
curl http://<container>/health            # -> {"status":"ok"}  (or via the proxy)
docker compose -f docker-compose.java.yml ps   # health: healthy
```

`docker compose ... down` stops the app but keeps the named volumes. **Never run `down -v`** — that deletes your data.

## Configuration

Copy `backend-java/.env.example` to `.env` next to the compose file and adjust. The most important variables:

| Variable | Notes |
|---|---|
| `BASE_URL` | Public HTTPS URL, e.g. `https://motion.example.com` (used in emails/links) |
| `MOTION_ENCRYPTION_KEY` | **Set before first upload.** `openssl rand -base64 32`. Back it up with your data. |
| `SECURE_COOKIES` | `true` in production (the image enforces it) |
| `TRUSTED_ORIGINS` | Browser origins allowed for credentialed requests (usually your `BASE_URL` origin) |
| `TRUSTED_PROXIES` | Proxy IP/CIDR so rate limits see real client IPs, e.g. the Caddy container's address or `172.16.0.0/12` |
| `SMTP_*` | See [Email / Gmail](#email--gmail-app-passwords) |

Full variable reference: [CONFIGURATION.md](CONFIGURATION.md).

## Reverse proxy

Motion speaks plain HTTP on port 8080 and expects TLS to end at the proxy. Caddy example:

```
motion.example.com {
    reverse_proxy motion-java:8080
}
```

Because the proxy owns HTTPS, keep `SECURE_COOKIES=true` (default in the image) so session cookies are only sent over secure connections. Set `TRUSTED_PROXIES` to the proxy's address/CIDR so login/upload rate limits are enforced per real client rather than per proxy.

## Email / Gmail App Passwords

Account verification and password-reset emails need SMTP. With Gmail:

1. Enable 2-Step Verification on the Google account.
2. Create an **App Password** (Google Account → Security → 2-Step Verification → App passwords).
3. Set in `.env`:
   ```
   SMTP_HOST=smtp.gmail.com
   SMTP_PORT=587
   SMTP_USER=you@gmail.com
   SMTP_PASS=<app password>      # 16 characters; never your account password
   SMTP_FROM=you@gmail.com
   ```
4. Decide whether new accounts must verify: `REQUIRE_EMAIL_VERIFICATION=true/false`.

With no `SMTP_HOST`, no mail is sent: registration works without verification (unless required), and password reset is unavailable. `DEV_EMAIL_OUTBOX` writes emails to a file instead — development only, never production.

## Backup and restore

Two scripts produce and restore a complete backup set: consistent database snapshot (`sqlite3`-safe; the app may stay running with `--online`), a media archive, the environment file **including the encryption key**, and a SHA-256 manifest.

```bash
# Docker installation:
backend-java/scripts/backup.sh -o /var/backups/motion/$(date -u +%Y%m%dT%H%M%SZ)
# keep the app running during backup:
backend-java/scripts/backup.sh --online -o /var/backups/motion/...

# Restore (stops the app, restores env + media + database, restarts, waits for health):
backend-java/scripts/restore.sh /var/backups/motion/<backup-dir>
```

Host (non-Docker) installations set `MOTION_HOME` to the application directory instead.

**A backup without the encryption key is incomplete.** Encrypted media cannot be recovered without the key, and there is no key-rotation or re-wrap tooling.

Recommended cadence and a verified restore drill:

1. Back up at whatever interval you can afford to lose (daily is typical).
2. Periodically restore a backup into a **disposable** installation and log in, stream a video, and download an output — the scripts' behavior was verified end-to-end this way during release testing (total data loss simulated, encrypted media readable after restore).
3. Store backups somewhere other than the Motion host.

## Upgrades

```bash
git pull                      # or otherwise update the source
docker compose -f docker-compose.java.yml --profile java up -d --build
```

Schema changes ship as Flyway migrations and apply automatically on first boot after the upgrade. Take a backup before upgrading. Rollback of application versions is safe (restore the pre-upgrade backup if a new version misbehaves).

## Health, shutdown, restart behavior

- Health: `GET /health` → `{"status":"ok"}`; the Docker healthcheck polls it every 30 s.
- Shutdown is graceful: in-flight HTTP requests finish, running FFmpeg jobs are stopped (their processes are terminated), interrupted jobs are requeued automatically on next startup with a fresh lease.
- All state lives in the three named volumes and survives container recreation.

## Verification checklist for a new installation

- [ ] `docker compose ps` reports healthy
- [ ] Register an account, log out, log in
- [ ] Upload a small MP4; it appears in the library and plays
- [ ] Submit a "flip" edit; job completes; output downloads
- [ ] Open the timeline editor, save a project, render it
- [ ] `docker compose restart motion-java` — library, projects, and outputs persist
- [ ] Run `backup.sh` once and store the output set safely

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
