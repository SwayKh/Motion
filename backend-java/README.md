# Motion Java backend

The Java/Spring Boot implementation of Motion. Serves the static frontend from `../web/`
and implements the full application: authentication, uploads, media streaming with HTTP
range support, FFmpeg processing jobs, the timeline editor's projects and rendering, and
optional media encryption at rest. The earlier Go implementation in `../internal/` is kept
only as a reference.

## Build and test

```bash
./mvnw -B clean verify      # runs the full test suite (needs ffmpeg + ffprobe on PATH)
./mvnw -DskipTests package  # produces target/motion-java-0.1.0-PHASE4.jar
```

Run the jar from the repository root so it finds the `web/` frontend:

```bash
cd ..
java -jar backend-java/target/motion-java-0.1.0-PHASE4.jar
# http://localhost:8080
```

## Documentation

- `docs/CONFIGURATION.md` — every environment variable
- `docs/DEPLOYMENT.md` — Docker setup, reverse proxy, email, backups
- `docs/API.md` — HTTP API reference
- `docs/SECURITY.md` — security notes and known limitations
- `docs/TROUBLESHOOTING.md` — common problems

Configuration comes entirely from environment variables (`../.env` via Compose, or the
`MOTION_*` variables documented in `../backend-java/.env.example`). The application never
loads `.env` itself.
