# Motion — Complete Home Server Deployment Guide

**You have SSHed into your Debian home server. What next?**

This guide takes you from an SSH prompt to Motion running at `https://motion.swaykh.com`,
behind your existing Docker + Caddy + Cloudflare Tunnel + Tailscale homelab, without
disrupting anything that is already running.

Commands are labelled:

- **[server]** — run on the Debian home server (SSH session)
- **[dev]** — run on your Arch development machine
- **[container]** — run inside the Motion container

Everything below was written against the actual repository files
(`backend-java/Dockerfile`, `docker-compose.java.yml`, `backend-java/.env.example`,
`backend-java/scripts/backup.sh`, `restore.sh`). Where something depends on your
server and cannot be known from the repository, it is marked **[check manually]**.

> **Read this first — protect your homelab**
>
> Your server already runs services. The following are **forbidden** at every step of
> this guide:
>
> - `docker system prune`, `docker volume prune`, `docker image prune -a` — these touch **other services'** data
> - `docker compose down -v` on **any** project — `-v` deletes named volumes. Motion's data lives in its volumes
> - removing/recreating containers, volumes or networks that are not Motion's
> - overwriting your existing Caddyfile (we back it up and *append* a site block)
> - reusing the old Go application's volumes or database — Motion Java creates **fresh** volumes (`motion_java_*`) and a **new** database; the old Go stack is untouched
> - exposing Motion publicly before Step 7's private tests pass
> - regenerating or losing `MOTION_ENCRYPTION_KEY` after the first upload — encrypted media is **permanently unreadable** without it, and there is no key-rotation tool

---

## Step 1 — Server preflight

You are checking what exists before changing anything. Every command here is read-only.

**[server]** What Debian are you on?

```bash
cat /etc/debian_version && cat /etc/os-release | head -2
```

Expected: `12.x` (bookworm) or similar. Anything Debian 11+ is fine for Docker.

**[server]** CPU, RAM, disk:

```bash
nproc
free -h
df -h /
```

What to look for:

- Motion transcodes video with FFmpeg inside the container — 2+ cores is comfortable.
- RAM: the Java app idles around 450–500 MB and peaks near 1 GB during concurrent renders. 2 GB free RAM headroom is a good minimum.
- Disk: you need ~3 GB for the Docker image + build cache, **plus room for media**. The app itself refuses uploads when free disk drops below `MIN_FREE_DISK_BYTES` (default 1 GiB).

**[server]** Docker and Compose:

```bash
docker --version
docker compose version
docker info --format '{{.ServerVersion}}'
```

Expected: Docker 24+ and `Docker Compose version v2.x`. If `docker: command not found` —
see Step 3 for installation. If `docker compose` prints v1 (`1.29.x`), the compose plugin
is too old for this project; install the `docker-compose-plugin` package (Step 3).

**[server]** What is already running?

```bash
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'
docker network ls
docker volume ls | head -30
ss -ltnp | awk 'NR>1 {print $4}' | sort -u
```

What to look for:

- Existing container names — Motion's container is hard-coded to `motion-java`. Check nothing already uses that name:
  ```bash
  docker ps -a --format '{{.Names}}' | grep -x motion-java && echo "NAME CONFLICT" || echo "name free"
  ```
- The `homelab` network (Motion's Compose file expects an **external** network named exactly `homelab`):
  ```bash
  docker network ls --format '{{.Name}}' | grep -x homelab && echo "network exists" || echo "MISSING - create in Step 3"
  ```
- Existing volumes — note whether anything resembling `motion_*` (the **old Go stack**) exists. Those are a **different** application's volumes; do not touch them, and do not confuse them with the new `motion_motion_java_*` volumes this deployment creates.
- Listening ports — pick a free high port for private testing (Step 6). Note the `homelab` subnet for Step 4:
  ```bash
  docker network inspect homelab --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
  docker network inspect homelab --format '{{range .Containers}}{{.Name}} {{.IPv4Address}}{{"\n"}}{{end}}'
  ```
  Write down the subnet (typically something like `172.18.0.0/16`) and the name/IP of your **Caddy** container.

**[server]** Where does Caddy run? Determine now — Step 10 depends on it:

```bash
# Option A: Caddy is a container?
docker ps --format '{{.Names}} {{.Image}}' | grep -i caddy

# Option B: Caddy is a systemd service on the host?
systemctl status caddy --no-pager | head -5

# If it is a container, find how its config is provided:
docker inspect caddy --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}' 2>/dev/null
docker inspect caddy --format '{{.Config.Cmd}}' 2>/dev/null
```

- If you see a caddy **container** → follow **Case A** in Step 10. Find its Caddyfile (one of the mounted paths above).
- If you see `active (running)` from systemd → **Case B**; the config is almost certainly `/etc/caddy/Caddyfile`.

**[server]** Cloudflare Tunnel (read-only check — do not change anything yet):

```bash
docker ps --format '{{.Names}}' | grep -i cloudflared
systemctl status cloudflared --no-pager | head -3
ps aux | grep -v grep | grep cloudflared | head -2
```

Just note whether cloudflared runs as a container or a host service, and where its
config lives (`/etc/cloudflared/config.yml`, a Docker volume, or Zero Trust dashboard
remote-managed). **[check manually]**: whether its ingress rules route per-hostname or
by catch-all to Caddy — Step 11 tells you how to decide.

**[server]** Storage for media:

```bash
df -h /var/lib/docker
```

Motion stores uploads and outputs in Docker named volumes under `/var/lib/docker/volumes/`.
The default application quota is `MAX_TOTAL_STORAGE_BYTES=53687091200` (50 GiB all users,
5 GiB per user). Make sure the filesystem holding `/var/lib/docker` has that much headroom,
or lower the quotas in `.env` (Step 4).

**Safe to proceed when:** Docker v2 compose works, `homelab` network exists (or you will
create it), the `motion-java` name is free, you know your Caddy type, and you have a free
port picked for private testing.

---

## Step 2 — Get the project onto the server

We transfer only the files the deployment needs: the committed source tree plus the two
deployment docs and the fixed backup scripts. We exclude `.git`, `.env`, local databases,
media, build output and all internal development notes — the repository `.gitignore`
already describes exactly this set, and rsync can use it directly.

**[dev]** From `/home/sumit/Projects/Go/Motion-Java`:

```bash
rsync -av --delete \
  --exclude '.git' \
  --filter=':- .gitignore' \
  ./ youruser@yourserver:/tmp/motion-upload/
```

Notes:

- `--filter=':- .gitignore'` makes rsync skip everything Git ignores: `data/`, `uploads/`,
  `outputs/`, `backend-java/target/`, all internal `*.md` reports, and — critically — any
  local `.env` with secrets. Nothing secret leaves your machine.
- Trailing slashes matter: source `./` and destination ending in `/`.
- `youruser@yourserver` — your SSH target as you normally use it (Tailscale name or LAN IP).

If you prefer exact-revision tarballs instead of rsync, `git archive` also works but would
miss uncommitted documentation — rsync is the recommended path here.

**[server]** Move it into place:

```bash
sudo mkdir -p /opt/motion
sudo chown "$USER":"$USER" /opt/motion
mv /tmp/motion-upload/* /opt/motion/
rmdir /tmp/motion-upload
cd /opt/motion
```

**[server]** Verify the right things arrived:

```bash
cd /opt/motion
ls docker-compose.java.yml backend-java/Dockerfile backend-java/.env.example \
   backend-java/scripts/backup.sh backend-java/scripts/restore.sh web/index.html \
   backend-java/src/main/resources/db/migration/V1__initial_schema.sql \
   HOME_SERVER_DEPLOYMENT_GUIDE.md
```

Expected: all eight paths listed, no "No such file". Also confirm the Go/Java mix is right:

```bash
ls backend-java/pom.xml main.go 2>/dev/null
```

Both should exist (`backend-java/` is the product; the Go tree is reference material that
rides along and is needed as the Docker build context includes the whole repo).

**Safe to proceed when:** the verification `ls` prints all paths without error.

---

## Step 3 — Configure the server (dependencies and permissions)

**[server]** Is Docker already installed? You checked in Step 1. If it works:

```bash
docker compose version
```

Skip installation. **Do not reinstall Docker on a live homelab.**

If (and only if) Docker is missing, install it from Docker's official apt repository
(official procedure for Debian):

```bash
# [server] — ONLY if docker is not installed
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

Verify: `docker compose version` → v2.x.

**[server]** Can your user run Docker without sudo? (Needed for every later step.)

```bash
docker ps >/dev/null && echo "docker OK without sudo" || { echo "adding yourself to the docker group"; sudo usermod -aG docker "$USER"; }
```

If you just added yourself, log out and back in (or `newgrp docker`) before continuing.

**[server]** The external network (only if Step 1 said it is missing):

```bash
docker network ls --format '{{.Name}}' | grep -x homelab || docker network create homelab
```

If it already exists — leave it completely alone.

**[server]** Container user and volume ownership.

The Docker image creates a non-root user `motion` with **UID/GID 10001** (verified in
`backend-java/Dockerfile`: `useradd --create-home --uid 10001 ... motion`, then
`mkdir -p /app/data ... && chown -R motion:motion /app`).

You do **not** create any host directories for data. Motion uses three **named volumes**
(`motion_java_data`, `motion_java_uploads`, `motion_java_outputs`) that Docker manages
under `/var/lib/docker/volumes/`. On first start, Docker copies the image's directory
permissions (owner 10001) into the fresh volumes, so the container can write them
immediately. You will verify this empirically in Step 7 by uploading a file.

If you ever restore into those volumes manually (Step 9's restore script handles this
itself with `chown -R 10001:10001`), ownership must stay `10001:10001`.

**Safe to proceed when:** `docker compose version` works without sudo and `homelab` exists.

---

## Step 4 — Configure environment variables

**[server]** Create `.env` from the template:

```bash
cd /opt/motion
cp backend-java/.env.example .env
chmod 600 .env
```

Now edit it: `nano .env`. The file is grouped into sections; almost everything keeps its
default. The variables that **must** be decided are below. Two Compose-level variables
(not application variables) are added at the top for safety and convenience.

### Compose-level additions (add these lines at the very top of `.env`)

```ini
COMPOSE_PROJECT_NAME=motion
COMPOSE_PROFILES=java
```

- `COMPOSE_PROJECT_NAME=motion` — pins the project name so volumes are always exactly
  `motion_motion_java_data`, `motion_motion_java_uploads`, `motion_motion_java_outputs`,
  regardless of the directory you run Compose from. The backup/restore scripts rely on this.
- `COMPOSE_PROFILES=java` — the service is guarded by the `java` profile; this makes plain
  `docker compose -f docker-compose.java.yml ...` commands work without typing `--profile java`
  every time (you can still use the flag; both work).
- Neither is read by the application; they only configure `docker compose` itself.

### Required application variables

| Variable | Value for your deployment | Mandatory? | Secret? |
|---|---|---|---|
| `BASE_URL` | `https://motion.swaykh.com` | Yes | No — public URL, used in email links |
| `MOTION_ENCRYPTION_KEY` | generate below, **before first upload** | Strongly recommended | **YES** |
| `TRUSTED_ORIGINS` | `https://motion.swaykh.com` | Recommended | No |
| `TRUSTED_PROXIES` | homelab subnet from Step 1, e.g. `172.18.0.0/16` (Case A) or `127.0.0.1` (Case B) | Required behind proxy | No |

**Generate the encryption key [server]:**

```bash
openssl rand -base64 32
```

Put the output in `.env`:

```ini
MOTION_ENCRYPTION_KEY=<paste the base64 string>
```

- Media is encrypted with AES-256-GCM per file; an invalid key **aborts startup** (fail-closed).
- **Store a copy outside the server now** — password manager, and ideally a second offline
  location. Losing this key means every uploaded video is **permanently unreadable**; the
  backup script includes the key file, but the key must also exist independently of any
  single machine.
- There is **no key rotation**. Do not regenerate it later "to be safe" — that would orphan
  all existing encrypted media.
- Leaving it empty disables encryption — fine for a throwaway test, not for real media.

**Set the retention window — important product default:**

```ini
RETENTION=0
```

**Read this carefully:** the default `RETENTION=24h` means **videos in a terminal state are
deleted — with their files — 24 hours after creation** (that is the behavior Motion
inherited from the original application: old completed media is reaped). For a personal
instance where you want to keep your uploads, set `RETENTION=0` (disables the sweep) or a
long window like `720h` (30 days). This is the single most consequential default in the file.

**Private testing vs public:** the same `.env` serves both stages; only `BASE_URL` and
`TRUSTED_ORIGINS` are written for the final public URL from the start, which is harmless
during private testing (they are only compared against incoming `Origin` headers and used
in email links).

### Optional variables you may want

| Variable | Default | When to change |
|---|---|---|
| `SMTP_HOST/PORT/USER/PASS/FROM` | empty | Enable password-reset / verification emails. With Gmail: `smtp.gmail.com:587` + an **App Password** (Google Account → Security → 2-Step Verification → App passwords). **[check manually]** — credentials are yours; never commit them |
| `REQUIRE_EMAIL_VERIFICATION` | `false` | `true` if you want new accounts to verify email first (needs SMTP) |
| `MAX_UPLOAD_BYTES` | `157286400` (150 MB) | **Cloudflare consideration below** |
| `MOTION_MULTIPART_MAX_FILE_SIZE` / `MOTION_MULTIPART_MAX_REQUEST_SIZE` | `150MB` | Keep ≥ `MAX_UPLOAD_BYTES` |
| `MAX_TOTAL_STORAGE_BYTES` / `MAX_STORED_BYTES_PER_USER` | 50 GiB / 5 GiB | Size to your disk |
| `MIN_FREE_DISK_BYTES` | 1 GiB | Uploads are refused below this |
| `MOTION_JOB_WORKERS` | `3` | How many FFmpeg renders run in parallel |
| `MOTION_FFMPEG_TIMEOUT` | `2h` | Hard wall-clock limit per FFmpeg run |
| `DEV_EMAIL_OUTBOX` | `false` | **Leave false in production** (writes emails incl. tokens to a file) |

**Cloudflare upload-size warning:** Cloudflare's edge enforces a request-body limit on
proxied traffic (100 MB on Free/Pro plans). Motion allows up to 150 MB — uploads between
100–150 MB will fail at Cloudflare with a 413/520-class error regardless of Motion's
settings. **[check manually]** which plan you are on. If Free/Pro, set:

```ini
MAX_UPLOAD_BYTES=99000000
MOTION_MULTIPART_MAX_FILE_SIZE=99MB
MOTION_MULTIPART_MAX_REQUEST_SIZE=99MB
```

so users get Motion's clean "file too large" message instead of a Cloudflare error page.
Large-file uploads can also always be done over Tailscale/LAN later.

### The `.env` safety rules

- `.env` is already excluded by `.gitignore` — never move it somewhere tracked.
- `chmod 600` (already applied above); it contains the encryption key and SMTP credentials.
- It is passed to the container via Compose `env_file`; the app never reads it directly.

**[server]** Confirm the file is sane (no stray spaces around `=`, key present):

```bash
grep -E '^(BASE_URL|MOTION_ENCRYPTION_KEY|TRUSTED_ORIGINS|TRUSTED_PROXIES|RETENTION|COMPOSE_PROJECT_NAME|COMPOSE_PROFILES)=' .env
```

Expected: seven lines, with real values (the encryption key line long and base64-ish).

**Safe to proceed when:** the grep shows all seven configured lines.

---

## Step 5 — Build the Docker image

**[server]** Validate the Compose configuration first (catches `.env`/network problems before any build):

```bash
cd /opt/motion
docker compose -f docker-compose.java.yml config --quiet && echo "compose config OK"
```

Expected: `compose config OK`. Common failures:

- `network homelab declared as external, but could not be found` → create it (Step 3), do not remove `external: true`.
- `env file /opt/motion/.env not found` → Step 4 skipped.
- `service motion-java is disabled (profile java)` → you typed the command without `--profile java` and without `COMPOSE_PROFILES=java` in `.env`; either fix `.env` or add the flag.

**[server]** Build (needs internet; first build pulls Maven dependencies, ~5 minutes):

```bash
docker compose -f docker-compose.java.yml --profile java build
```

Expected: ending in `=> => naming to docker.io/library/motion-motion-java` and exit code 0.

Build failure troubleshooting:

- `failed to solve: failed to fetch ... 503/timeout` during `mvn dependency:go-offline` → transient Maven Central issue; rerun the same command (layers cache progress).
- `no space left on device` → `df -h /var/lib/docker`; free space by removing *unrelated dangling layers you know are safe* — never `docker system prune -a` on a shared server without reviewing what it would delete (`docker system df -v` first).
- `ERROR: failed to solve: ... context not found` → you are not in `/opt/motion` (build context is the repo root).

**[server]** Verify the image exists:

```bash
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep motion
```

Expected: `motion-motion-java:latest` (~420 MB).

**[server]** Inspect the runtime user and in-image tooling — all three must pass before continuing:

```bash
docker run --rm --entrypoint sh motion-motion-java:latest -c \
  'id && java -version 2>&1 | head -1 && ffmpeg -version 2>&1 | head -1 && ffprobe -version 2>&1 | head -1 && ls -ld /app/data /app/uploads /app/outputs'
```

Expected output:

```
uid=10001(motion) gid=10001(motion) groups=10001(motion)
openjdk version "21..." 
ffmpeg version 4.x ... (Ubuntu jammy package)
ffprobe version 4.x ...
drwxr-xr-x 1 10001 10001 ... /app/data
```

`id` **must** show uid 10001 — that matches the Dockerfile's non-root user, and confirms
who must own the volume files. If ffmpeg is missing, the image build skipped the apt step —
rebuild cleanly.

**Safe to proceed when:** config validates, image exists, and the four checks above pass.

---

## Step 6 — First deployment (private only)

Motion's Compose file deliberately publishes **no host port** (the reverse proxy reaches it
over the `homelab` network). For private testing you add a small override file that binds
the container to **localhost only**. Nothing is exposed to your LAN or the internet.

**[server]** Pick an unused port (checked in Step 1 with `ss -ltnp`). This guide uses
`8091` — substitute your own if taken:

```bash
ss -ltn | grep -q ':8091 ' && echo "PORT 8091 BUSY - choose another" || echo "8091 free"
```

**[server]** Create the private override file `/opt/motion/compose.private.yml`:

```bash
cat > /opt/motion/compose.private.yml <<'EOF'
# PRIVATE TESTING ONLY - binds Motion to localhost and relaxes cookie security
# for plain-HTTP browser access through the SSH tunnel.
# Remove this file (and recreate the container) before public deployment.
services:
  motion-java:
    ports:
      - "127.0.0.1:8091:8080"
    environment:
      SECURE_COOKIES: "false"
EOF
```

Why `SECURE_COOKIES: "false"`: production sets `Secure` cookie flags, which browsers
normally refuse to send over plain `http://`. Modern browsers make an exception for
`localhost`, but the tunnel makes the exception flaky in practice — disabling the flag for
the private stage gives you a faithful test without cookie mysteries. The base compose file
forces `true`; this override wins during private testing, and is discarded afterwards.

**[server]** Start Motion:

```bash
cd /opt/motion
docker compose -f docker-compose.java.yml -f compose.private.yml --profile java up -d
```

Expected: container created, image started. Watch it become healthy:

```bash
docker compose -f docker-compose.java.yml --profile java ps
```

Expected: `(health: starting)` → **`(healthy)`** within ~60 seconds (30 s start period + a
few seconds of Spring Boot). On very first boot Flyway initializes the database on the
empty volume — it happens in well under a second.

**[server]** Health check and first logs:

```bash
curl -s http://127.0.0.1:8091/health
docker compose -f docker-compose.java.yml --profile java logs --tail 50 motion-java
```

Expected: `{"status":"ok"}` and clean startup logs (no stack traces; `Flyway` lines
reporting schema `up to date` or the initial migration on first boot).

If unhealthy:

```bash
docker compose -f docker-compose.java.yml --profile java logs motion-java | grep -iE 'error|exception|failed'
```

- `invalid MOTION_ENCRYPTION_KEY` → the key is not a valid base64 32-byte value; regenerate with `openssl rand -base64 32`.
- Container exits instantly → `docker logs motion-java`; most likely a `.env` syntax error.

**[dev]** Open the app through an SSH tunnel:

```bash
ssh -N -L 8091:127.0.0.1:8091 youruser@yourserver
```

Keep this SSH session open. In your browser on the dev machine, visit:

```
http://localhost:8091
```

You should see Motion's landing page. (If you use Tailscale instead, you can equally browse
the server's Tailscale IP with a published port — but the SSH tunnel keeps it strictly
localhost-only, which is what this stage requires.)

Useful lifecycle commands (all preserve data):

```bash
# [server] stop (container kept, volumes kept):
docker compose -f docker-compose.java.yml --profile java stop motion-java
# [server] start again:
docker compose -f docker-compose.java.yml --profile java start motion-java
# [server] remove the container but NOT the volumes (safe):
docker compose -f docker-compose.java.yml --profile java down
# DANGEROUS - never run with this project:
#   docker compose -f docker-compose.java.yml down -v   (deletes motion_java_* volumes)
```

**Safe to proceed when:** the container is `(healthy)`, `/health` returns `{"status":"ok"}`,
and the landing page loads through the tunnel.

---

## Step 7 — Test the application

Do all of this from your dev machine through the SSH tunnel at `http://localhost:8091`.
This mirrors the automated end-to-end suite that the project was validated with — here as a
manual checklist:

**Accounts and sessions**

- [ ] Register an account (`test@yourdomain` + a password you will not cry over).
- [ ] You land on the main page; refresh — you stay logged in (session survives reload).
- [ ] Log out; try to open `http://localhost:8091/app.html` directly → redirected to login.
- [ ] Log back in.

**Uploads**

- [ ] Upload a small MP4 via the upload button — the progress bar advances to 100% then the
      video appears in the library list and is auto-selected.
- [ ] Upload a second, larger MP4 (tens of MB) to watch meaningful progress.
- [ ] Try a `.txt` file → rejected with "Unsupported file type…".
- [ ] Select the video → the preview `<video>` plays.

**Processing**

- [ ] Pick the **flip** tool, click *Add*, then *Process video* → job appears in *History*.
- [ ] Expand the History panel; wait until status shows `completed` with Download/Preview buttons.
- [ ] Click **Preview** — the flipped video plays. Click **Download** — the file downloads.
- [ ] Start another job on the bigger video, click **Cancel** quickly in History → status
      becomes `cancelled`, no output is published.
- [ ] Try a trim with start > end (e.g. 5 → 1) → the UI shows the backend validation error,
      no job is created.

**Timeline editor**

- [ ] Open the timeline editor (button on the main page). Create a new project.
- [ ] The asset picker lists your uploads. Add a video clip.
- [ ] Add a **text** clip (type something) — a text track appears.
- [ ] Add an **audio track**, then click a clip and set its **volume** in the inspector.
- [ ] Click on the ruler to move the playhead; with the clip under it, press **Split** —
      the clip splits into two.
- [ ] Name the project, **Save**. Reload the page — the project is still in the list;
      reopen it: clips and edits are restored.
- [ ] Press **Render timeline** → progress advances → "Render complete." with a playable
      output video. Download it.

**Persistence across restart**

- [ ] On the server: `docker restart motion-java` (equivalently the compose `restart` from
      Step 6). Wait for healthy.
- [ ] Refresh the browser (re-login if your session aged) — your uploaded videos, rendered
      outputs, and the saved timeline project are all still there.

**Inspecting a failure**

```bash
# [server] application logs:
docker compose -f docker-compose.java.yml --profile java logs --tail 100 motion-java

# [server] FFmpeg processes while a render runs (ps is not installed in the image; docker top works from the host):
docker top motion-java | grep -c ffmpeg || echo "no ffmpeg running"

# [server] follow live:
docker compose -f docker-compose.java.yml --profile java logs -f motion-java
```

If a job fails, its error message is visible in the History panel and stored in the jobs
table; the log line above it in the container output shows the FFmpeg stderr Motion
captured.

**Safe to proceed when:** every checkbox above passes. If anything fails, fix before
proceeding — do not expose a broken instance.

---

## Step 8 — Test recovery and persistence

All of these use **disposable or live-safe** operations only.

**[server]** 1. Restart the container:

```bash
docker restart motion-java
docker exec motion-java curl -s http://localhost:8080/health
```

Expected: `{"status":"ok"}` after ~30–45 s. Uploaded data intact (you already verified in Step 7).

**[server]** 2. Recreate the container **without deleting volumes**:

```bash
cd /opt/motion
docker compose -f docker-compose.java.yml -f compose.private.yml --profile java up -d --force-recreate
```

Expected: a fresh container, `(healthy)` again, and — as before — your media, projects and
login still work. The named volumes survive container recreation by design.

**[server]** 3. Interrupted-job recovery. Submit a render on a large video in the UI, then
kill the container mid-render:

```bash
sleep 5 && docker restart motion-java
```

When it comes back, open the job in the UI: its status returns to `queued`/`processing` and
it **completes with a fresh execution lease** — Motion requeues interrupted work
automatically at startup (verified during the project's own validation with `kill -9`).

**[server]** 4. Confirm media/database live in the named volumes:

```bash
docker volume ls --format '{{.Name}}' | grep motion_motion_java
sudo du -sh /var/lib/docker/volumes/motion_motion_java_*
```

Expected: exactly three volumes (`..._data`, `..._uploads`, `..._outputs`), with `data`
holding `app.db` and `outputs` holding rendered files.

**[server]** 5. Create your first backup (full drill in Step 9; here just prove it runs):

```bash
cd /opt/motion
backend-java/scripts/backup.sh -o ./test-backup
```

Expected: `[backup] volumes: motion_motion_java_data ... (project=motion)` — **check the
project name says `motion`** — then `app.db`, `media.tgz`, `env`, `SHA256SUMS` listed and
the app restarted.

**[server]** 6. Verify backup integrity:

```bash
cd ./test-backup && sha256sum -c SHA256SUMS && cd ..
```

Expected: every file `OK`.

**[server]** 7. Restore drill into a **disposable** environment — never over live data:

```bash
# [server] separate project = separate volumes = zero risk to the real ones
mkdir -p /tmp/motion-restore-drill && cd /tmp/motion-restore-drill
cp /opt/motion/docker-compose.java.yml ./drill-compose.yml
sed -i 's/container_name: motion-java$/container_name: motion-java-restore-test/' drill-compose.yml

export COMPOSE_PROJECT_NAME=motion-restore-test
export SERVICE=motion-java-restore-test
export COMPOSE_FILE=drill-compose.yml

docker volume create motion-restore-test_motion_java_data >/dev/null
docker volume create motion-restore-test_motion_java_uploads >/dev/null
docker volume create motion-restore-test_motion_java_outputs >/dev/null
docker volume ls --format '{{.Name}}' | grep motion-restore-test

/opt/motion/backend-java/scripts/restore.sh /opt/motion/test-backup
```

Expected: `[restore] target volumes: motion-restore-test_... (project=motion-restore-test)`
— **read that line carefully; it must say motion-restore-test, not motion** — then
"healthy — restore complete". Login to the restored instance on the `homelab` network (or
add the same private port override to `drill-compose.yml` with a different port to test
through a tunnel). Verify media plays and the timeline project exists.

Cleanup of the drill (deletes only the disposable copies):

```bash
docker rm -f motion-java-restore-test
docker volume rm motion-restore-test_motion_java_data motion-restore-test_motion_java_uploads motion-restore-test_motion_java_outputs
unset COMPOSE_PROJECT_NAME SERVICE COMPOSE_FILE
cd /opt/motion
```

**Safe to proceed when:** restart, recreate, recovery, backup and the isolated restore all pass.

---

## Step 9 — Configure backups

The repository ships `backend-java/scripts/backup.sh` and `restore.sh`, which handle all
four things a complete backup needs: the SQLite database (consistent snapshot),
the encrypted media, the **`.env` containing the encryption key**, and a SHA-256
manifest. A backup without the key is not complete — encrypted media is unrestorable
without it.

**[server]** Regular backup (stops the app for a few seconds by default, safest):

```bash
cd /opt/motion
backend-java/scripts/backup.sh -o /var/backups/motion/motion-backup-$(date -u +%Y%m%dT%H%M%SZ)
```

Or keep the app running (DB snapshot stays consistent; media files are moved atomically
into place, so live archives are safe):

```bash
backend-java/scripts/backup.sh --online -o /var/backups/motion/...
```

Each backup set contains: `app.db` (snapshot), `media.tgz` (all three volumes' contents),
`env` (your `.env`, mode 600), `SHA256SUMS`.

**Verify a backup:**

```bash
cd /var/backups/motion/<set> && sha256sum -c SHA256SUMS
```

**Scheduling** — daily at 03:00 as root:

```bash
sudo tee /etc/cron.d/motion-backup >/dev/null <<'EOF'
0 3 * * * root cd /opt/motion && backend-java/scripts/backup.sh --online -o /var/backups/motion/motion-backup-$(date -u +\%Y\%m\%d) >> /var/log/motion-backup.log 2>&1
EOF
```

(The escaped `%` is cron syntax.) Watch the first run: `sudo tail -f /var/log/motion-backup.log`.

**Retention:** keep at least 7 daily + a few weekly sets, e.g.:

```bash
# [server] keep newest 14 sets only (arranged as motion-backup-YYYYMMDD...):
find /var/backups/motion -maxdepth 1 -type d -name 'motion-backup-*' | sort | head -n -14 | xargs -r rm -rf
```

**Where to store backups:** `/var/backups/motion` is on the same disk — also copy one set
off-machine regularly (your other machine over Tailscale, NAS, or object storage). A backup
that dies with the server is not a backup. The encryption key additionally lives in every
`env` file — treat backup directories as secrets (`chmod 700 /var/backups/motion`).

**Restore (real disaster recovery):** run from `/opt/motion` with nothing overridden:

```bash
docker compose -f docker-compose.java.yml --profile java stop motion-java
backend-java/scripts/restore.sh /var/backups/motion/<set>
```

It verifies checksums first (refuses corrupted/tampered/incomplete sets), restores
`.env` → media → database in the safe order, fixes `10001` ownership, starts the app and
waits for health. Only ever run this when you actually mean to replace live data.

**Honest caveat:** the scripts' Docker-mode paths (including this exact flow) were fixed
during the writing of this guide and have not yet been executed against a real Docker
daemon anywhere (no Docker was available in the development environment; the equivalent
host-mode flow passed a 20-point disaster drill). Treat the Step 8 drill as their
production rehearsal. If anything misbehaves, the script prints the exact volume names it
is about to touch before doing anything.

**Safe to proceed when:** a scheduled backup exists, one set verified, one off-machine copy made.

---

## Step 10 — Configure Caddy

Only after Steps 6–8 pass. Your first task is confirming which of the two Caddy setups you
have (from Step 1) — the upstream address differs completely between them.

**Back up the existing Caddyfile before touching anything:**

```bash
# [server] container case (adjust 'caddy' to your container name; find the mounted path first):
docker inspect caddy --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
cp <the-mounted-Caddyfile> <the-mounted-Caddyfile>.bak-$(date +%Y%m%d-%H%M%S)

# [server] systemd case:
sudo cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-$(date +%Y%m%d-%H%M%S)
```

### Case A — Caddy runs as a Docker container on `homelab`

Motion already joins the same external `homelab` network, and its container is named
`motion-java`, so Caddy reaches it by container DNS name. **`127.0.0.1` inside the Caddy
container is the Caddy container itself — never use it for Motion in this case.**

Add this site block to your Caddyfile:

```
motion.swaykh.com {
    reverse_proxy motion-java:8080
}
```

That is all Motion needs. Notes:

- **Body size:** Caddy streams request/response bodies without a size cap by default —
  no `client_max_body_size`-style directive needed for 150 MB uploads.
- **Timeouts:** Caddy has no default proxy timeout; Motion's uploads/downloads stream and
  its renders are asynchronous (the API returns 202 immediately), so no timeout tuning is
  required.
- **TLS:** `motion.swaykh.com` gets a certificate automatically the same way your existing
  sites do (Caddy's automatic HTTPS). Follow the same pattern your other site blocks use
  (if your tunnel terminates TLS upstream of Caddy with `tls` disabled per site, mirror
  that pattern — check an existing block before inventing one).

### Case B — Caddy runs directly on the Debian host (systemd)

Keep a **permanent** localhost port binding for Motion (rename the private-test override
into a permanent one, keep the port you tested with, but drop the cookie override):

```bash
cd /opt/motion
cat > compose.host-caddy.yml <<'EOF'
# Permanent: publish Motion on localhost for host-level Caddy.
services:
  motion-java:
    ports:
      - "127.0.0.1:8091:8080"
EOF
docker compose -f docker-compose.java.yml -f compose.host-caddy.yml --profile java up -d
```

Add to the host Caddyfile:

```
motion.swaykh.com {
    reverse_proxy 127.0.0.1:8091
}
```

Here `127.0.0.1` is correct — Caddy is on the host, and the port is bound to the host's
loopback.

### Trusted proxies — make rate limiting work correctly

Motion identifies clients by `X-Forwarded-For`, but only from IPs listed in
`TRUSTED_PROXIES`. Set it per case:

- **Case A:** the homelab subnet you recorded in Step 1, e.g.:
  ```ini
  TRUSTED_PROXIES=172.18.0.0/16
  ```
  This trusts both the Caddy container and (if applicable) a containerized cloudflared on
  that network, so the resolver walks the forwarded chain to the real visitor IP. If your
  cloudflared runs on the **host** or elsewhere, add its source address as seen by Caddy
  too. **[check manually]** with:
  ```bash
  docker network inspect homelab --format '{{range .Containers}}{{.Name}} {{.IPv4Address}}{{"\n"}}{{end}}'
  ```
- **Case B:** `TRUSTED_PROXIES=127.0.0.1`

Then apply it (recreates the container):

```bash
cd /opt/motion
# Case A (Caddy in Docker - no host port):
docker compose -f docker-compose.java.yml --profile java up -d
# Case B (Caddy on host - keep the permanent localhost binding):
docker compose -f docker-compose.java.yml -f compose.host-caddy.yml --profile java up -d
```

**Verification [server]** — from a **LAN address that is NOT in TRUSTED_PROXIES**:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://motion-java:8080/health   # from inside the homelab network, e.g. via another container — direct check
```

Practical check after going live (Step 11): log in from two different devices/networks —
if both suddenly hit `429 too many requests` together, trust is misconfigured and everyone
is being counted as one client; re-check the subnet.

### Validate and reload Caddy — never overwrite the running config

```bash
# [server] container case (validate + reload inside the container):
docker exec caddy caddy validate --config /etc/caddy/Caddyfile
docker exec caddy caddy reload --config /etc/caddy/Caddyfile

# [server] systemd case:
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

`validate` failing changes nothing — fix the block and re-validate. `reload` applies the
new site with zero downtime for your other sites. Avoid `restart` unless reload is
impossible — it briefly drops all sites.

**Safe to proceed when:** `caddy validate` passes and reload succeeds. Do not expect the
hostname to work publicly yet — that is Step 11 (DNS/tunnel may not route it yet, which is
fine at this point).

---

## Step 11 — Final public deployment

Order matters: Caddy is ready, now route the hostname through Cloudflare, then flip Motion
into its production posture.

**1. Cloudflare tunnel routing — decide based on what you found in Step 1:**

- **If your tunnel config has a catch-all ingress to Caddy** (a rule whose hostname is `*`
  or ends in a catch-all mapping to your Caddy service): nothing to change — motion.swaykh.com
  already flows to Caddy; skip to the DNS check.
- **If your tunnel uses per-hostname ingress rules:** add a rule for
  `motion.swaykh.com` pointing at the same service your other `*.swaykh.com` hostnames use
  (e.g. `service: https://caddy:443` or your equivalent), then restart/reload cloudflared
  the way you normally manage it.
- **DNS record:** the hostname must resolve to the tunnel. With the CLI-managed tunnel:
  ```bash
  # [server] only if a motion.swaykh.com DNS record does not already exist:
  cloudflared tunnel route dns <your-tunnel-name> motion.swaykh.com
  # check existing records in the Cloudflare dashboard first
  ```
  If the record already exists (CNAME to `<tunnel-id>.cfargotunnel.com`) — leave it alone.

**[check manually]:** these steps depend entirely on how your tunnel is managed
(dashboard vs config file); the principle is only "route motion.swaykh.com to the same place
your other hostnames go".

**2. Flip Motion to production posture [server]:**

```bash
cd /opt/motion
docker compose -f docker-compose.java.yml --profile java down   # container only - volumes kept
rm compose.private.yml                                          # remove the localhost/cookie override (if Case B, keep compose.host-caddy.yml instead)
docker compose -f docker-compose.java.yml --profile java up -d
docker compose -f docker-compose.java.yml --profile java ps
```

Now there is no published port at all (Case A) — Motion is reachable only through Caddy —
and `SECURE_COOKIES` is back to the production `true` (cookies carry the `Secure` flag;
your HTTPS site serves them fine, and they will not work over plain HTTP anymore, which is
the point).

**3. External verification — from outside your home network (phone on mobile data):**

- [ ] `https://motion.swaykh.com/health` → `{"status":"ok"}`
- [ ] Register a real account; log out; log in
- [ ] Upload a small video; it appears and plays
- [ ] Run a flip job; download the output
- [ ] Save and reopen a timeline project
- [ ] `https://` padlock valid (certificate issued — check the browser certificate viewer)
- [ ] Log in from a second device/network to confirm no shared rate-limit lockouts

**4. Logs [server]:**

```bash
docker compose -f docker-compose.java.yml --profile java logs --tail 200 motion-java
docker logs caddy --tail 50 2>&1 | grep -i motion   # or journalctl -u caddy | grep -i motion
```

Look for proxy-level errors (`502` from Caddy usually means Motion was down/restarting;
`413`/`52x` on big uploads usually means the Cloudflare body limit — see Step 4).

**5. Confirm the rest of the homelab still works:** visit one or two of your other
hostnames. A Caddy reload that validated cleanly cannot break other sites, but verify
anyway — that is the point of this step.

**Safe to proceed when:** every external checkbox passes and other services are unaffected.

---

## Step 12 — Maintenance

**Safe commands (data preserved):**

```bash
# [server] logs:
docker compose -f docker-compose.java.yml --profile java logs -f --tail 100 motion-java

# [server] restart the app:
docker compose -f docker-compose.java.yml --profile java restart motion-java

# [server] stop without deleting anything (container kept):
docker compose -f docker-compose.java.yml --profile java stop motion-java
# or remove the container but keep volumes:
docker compose -f docker-compose.java.yml --profile java down

# [server] health + database status:
docker exec motion-java curl -s http://localhost:8080/health
docker compose -f docker-compose.java.yml --profile java ps

# [server] disk usage:
df -h /var/lib/docker
sudo du -sh /var/lib/docker/volumes/motion_motion_java_*
docker system df   # look only - never prune blindly

# [server] backup + verify:
cd /opt/motion && backend-java/scripts/backup.sh -o /var/backups/motion/manual-$(date -u +%Y%m%d)
(cd /var/backups/motion/manual-* && sha256sum -c SHA256SUMS) | tail -1

# [server] restore (replaces live data - only on purpose):
backend-java/scripts/restore.sh /var/backups/motion/<set>
```

**Update to a new version:**

```bash
# [server] 1. protect yourself: tag the current image and back up
docker tag motion-motion-java:latest motion-rollback:$(date +%Y%m%d)
cd /opt/motion && backend-java/scripts/backup.sh -o /var/backups/motion/pre-update-$(date +%Y%m%d)
# 2. bring the new code. Same rsync as Step 2 PLUS protection for the server-only
#    compose override files (Case B) so --delete cannot remove them:
#      rsync -av --delete --exclude '.git' --filter=':- .gitignore' \
#        --exclude 'compose.host-caddy.yml' --exclude 'compose.private.yml' \
#        ./ youruser@yourserver:/opt/motion/        # [dev] then on the server:
docker compose -f docker-compose.java.yml --profile java up -d --build
# 3. if a schema migration ships, Flyway applies it on first boot - watch:
docker compose -f docker-compose.java.yml --profile java logs -f motion-java
```

**Rollback to the previous version:**

```bash
docker compose -f docker-compose.java.yml --profile java stop motion-java
docker tag motion-rollback:<date> motion-motion-java:latest
docker compose -f docker-compose.java.yml --profile java up -d --no-build
```

(If the newer version already ran a schema migration, prefer restoring the pre-update
backup set rather than running older code against a newer schema.)

**Dangerous commands — know them on sight:**

| Command | Effect |
|---|---|
| `docker compose -f docker-compose.java.yml down -v` | **Deletes all Motion data** (the three named volumes) |
| `docker volume rm motion_motion_java_*` | **Deletes Motion's database/media** |
| `docker volume prune` / `docker system prune` | Touches **other services'** unused resources too — never on this shared server without case-by-case review |
| Replacing `.env`'s `MOTION_ENCRYPTION_KEY` after uploads exist | Orphans all encrypted media — unrecoverable |
| Restoring a backup set whose `env` file has a different key than the media | Same as above |

---

## Appendix A — Values you must determine on your server

| Value | How to find it | Used in |
|---|---|---|
| Free private-test port | `ss -ltnp` (Step 1) | Step 6 |
| `homelab` subnet | `docker network inspect homelab` (Step 1) | Step 4 (`TRUSTED_PROXIES`) |
| Caddy type (container vs systemd) + Caddyfile path | Step 1 commands | Step 10 |
| cloudflared location + tunnel management style | Step 1 + your Zero Trust dashboard | Step 11 |
| Cloudflare plan (100 MB vs higher body limit) | Cloudflare dashboard | Step 4 upload limits |
| SMTP credentials (if email wanted) | Your mail provider (Gmail App Password) | Step 4 |
| Off-machine backup destination | Your NAS/other machine | Step 9 |

## Appendix B — What remains unverified until you deploy

Written honestly, per the project's verification standard:

1. **The Docker-mode branches of `backup.sh`/`restore.sh`** — fixed for volume prefixes and
   profiles during this guide's preparation, but never executed against a real Docker
   daemon (the development environment has no Docker). The Step 8 restore drill is their
   production rehearsal.
2. **The image build on Debian** — the Dockerfile and its health check were static-audited
   repeatedly and the application itself passed 213 automated tests plus a 39-point browser
   suite, but the actual `docker build` has never run on any machine.
3. **Caddy/Cloudflare specifics** — the site block is standard Caddy; your exact Caddy
   deployment (TLS mode, tunnel ingress style) is only knowable on your server.
4. **Cloudflare body-size limit for your plan** and its practical effect on uploads near
   100 MB through the tunnel.
5. **Server-hardware performance** — measured on the dev machine (1080p transcode ≈ 34 s,
   3 concurrent renders ≈ 1 GB RAM); your CPU may differ.

Everything else in this guide was verified against the repository's actual files: image
user UID 10001, container/service names, port 8080, `/health` endpoint, the complete
environment-variable list, volume names, retention semantics, and the backup/restore
behavior.
