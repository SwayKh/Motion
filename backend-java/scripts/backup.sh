#!/usr/bin/env bash
# Motion (Java) backup — creates a complete, restorable backup set:
#   1. consistent database snapshot (SQLite VACUUM INTO, or sqlite3 .backup)
#   2. all media volumes/directories (uploads, outputs, data incl. previews)
#   3. the environment file containing MOTION_ENCRYPTION_KEY
#
# A backup WITHOUT the encryption key is not complete: encrypted media is
# permanently unreadable without it.
#
# Modes (auto-detected):
#   docker : uses docker compose against docker-compose.java.yml
#   host   : runs against a direct installation directory (MOTION_HOME)
#
# The application is briefly STOPPED by default so media files are guaranteed
# consistent (zero risk of catching partially written files). Use --online to
# keep the app running: the DB snapshot stays consistent (VACUUM INTO), and
# media files are atomically renamed into place, so a live archive is safe for
# completed files.
#
# Usage:
#   ./backup.sh [--online] [-o output-dir]
# Environment:
#   MOTION_HOME   host-mode application directory containing data/, uploads/,
#                 outputs/ and .env (default: /opt/motion)
#   COMPOSE_FILE  docker-mode compose file (default: docker-compose.java.yml)
set -euo pipefail

ONLINE=0
OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --online) ONLINE=1; shift ;;
    -o|--output) OUT="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${OUT:-./motion-backup-$STAMP}"
MODE="host"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  MODE="docker"
fi
MOTION_HOME="${MOTION_HOME:-/opt/motion}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.java.yml}"
SERVICE="motion-java"
VOLS=(motion_java_data motion_java_uploads motion_java_outputs)

mkdir -p "$OUT"
chmod 700 "$OUT"

stop_app()   { [[ $MODE == docker ]] && docker compose -f "$COMPOSE_FILE" stop "$SERVICE" >/dev/null; }
start_app()  { [[ $MODE == docker ]] && docker compose -f "$COMPOSE_FILE" start "$SERVICE" >/dev/null; }

echo "[backup] mode=$MODE output=$OUT online=$ONLINE"

# 1. Database snapshot.
if [[ $MODE == docker ]]; then
  if [[ $ONLINE -eq 0 ]]; then stop_app; fi
  if docker compose -f "$COMPOSE_FILE" ps --status running -q "$SERVICE" | grep -q .; then
    docker compose -f "$COMPOSE_FILE" exec -T "$SERVICE" \
      java -jar /app/app.jar backup-db /app/data/backup.db
    docker cp "$SERVICE:/app/data/backup.db" "$OUT/app.db"
    docker compose -f "$COMPOSE_FILE" exec -T "$SERVICE" rm -f /app/data/backup.db
  else
    # App stopped: snapshot with a transient sqlite container against the data volume.
    docker run --rm -v "${VOLS[0]}:/data" alpine sh -c \
      "apk add --no-cache sqlite >/dev/null 2>&1 && sqlite3 /data/app.db \".backup '/data/backup.db'\""
    docker run --rm -v "${VOLS[0]}:/data" -v "$(cd "$OUT" && pwd):/out" alpine cp /data/backup.db /out/app.db
    docker run --rm -v "${VOLS[0]}:/data" alpine rm -f /data/backup.db
  fi
else
  if [[ $ONLINE -eq 0 ]] && pgrep -f "motion.*\.jar" >/dev/null 2>&1; then
    echo "[backup] WARNING: a host-mode Motion process appears to be running;" >&2
    echo "[backup] stopping it is not automated — use --online or stop it yourself." >&2
  fi
  if command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$MOTION_HOME/data/app.db" ".backup '$OUT/app.db'"
  else
    ( cd "$MOTION_HOME" && java -jar app.jar backup-db "$OUT/app.db" )
  fi
fi
[[ -s "$OUT/app.db" ]] || { echo "[backup] database snapshot missing" >&2; exit 1; }

# 2. Media + data archive.
if [[ $MODE == docker ]]; then
  docker run --rm \
    -v "${VOLS[0]}:/v/data" -v "${VOLS[1]}:/v/uploads" -v "${VOLS[2]}:/v/outputs" \
    -v "$(cd "$OUT" && pwd):/out" alpine \
    tar czf "/out/media.tgz" -C /v data uploads outputs
  if [[ $ONLINE -eq 0 ]]; then start_app; fi
else
  ( cd "$MOTION_HOME" && tar czf "$(cd "$OUT" && pwd)/media.tgz" data uploads outputs )
fi

# 3. Environment / secrets (contains MOTION_ENCRYPTION_KEY).
ENV_SRC="$([[ $MODE == docker ]] && echo .env || echo "$MOTION_HOME/.env")"
if [[ -f "$ENV_SRC" ]]; then
  cp "$ENV_SRC" "$OUT/env"
  chmod 600 "$OUT/env"
else
  echo "[backup] WARNING: no .env found; encrypted media may be unrestorable without the key!" >&2
fi

# 4. Manifest for integrity verification.
if [[ -f "$ENV_SRC" ]]; then
  ( cd "$OUT" && sha256sum app.db media.tgz env > SHA256SUMS )
else
  ( cd "$OUT" && sha256sum app.db media.tgz > SHA256SUMS )
fi
echo "[backup] complete: $OUT"
( cd "$OUT" && ls -la && cat SHA256SUMS )
