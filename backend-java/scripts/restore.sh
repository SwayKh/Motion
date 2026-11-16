#!/usr/bin/env bash
# Motion (Java) restore — restores a backup set produced by backup.sh.
#
# Restore ordering (enforced below):
#   1. stop the application
#   2. restore the environment file FIRST (the app validates MOTION_ENCRYPTION_KEY
#      at startup and must find media decryptable)
#   3. restore media directories
#   4. restore the database
#   5. fix ownership (container runs as uid/gid 10001)
#   6. start the application and wait for /health
#
# Usage:
#   ./restore.sh <backup-dir>   # e.g. ./restore.sh ./motion-backup-20261118T...
# Environment:
#   MOTION_HOME   host-mode application directory (default /opt/motion)
#   COMPOSE_FILE  docker-mode compose file (default docker-compose.java.yml)
set -euo pipefail

BACKUP_DIR="${1:?usage: restore.sh <backup-dir>}"
[[ -f "$BACKUP_DIR/app.db" && -f "$BACKUP_DIR/media.tgz" ]] || {
  echo "restore: $BACKUP_DIR is not a complete backup set (need app.db + media.tgz)" >&2
  exit 1
}
( cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS ) || {
  echo "restore: integrity check FAILED — refusing to restore" >&2
  exit 1
}

MODE="host"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then MODE="docker"; fi
MOTION_HOME="${MOTION_HOME:-/opt/motion}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.java.yml}"
SERVICE="motion-java"
VOLS=(motion_java_data motion_java_uploads motion_java_outputs)

echo "[restore] mode=$MODE backup=$BACKUP_DIR"

if [[ $MODE == docker ]]; then
  docker compose -f "$COMPOSE_FILE" stop "$SERVICE" >/dev/null 2>&1 || true
  [[ -f "$BACKUP_DIR/env" ]] && cp "$BACKUP_DIR/env" .env && chmod 600 .env
  docker run --rm \
    -v "${VOLS[0]}:/v/data" -v "${VOLS[1]}:/v/uploads" -v "${VOLS[2]}:/v/outputs" \
    -v "$(cd "$BACKUP_DIR" && pwd):/in" alpine sh -c '
      rm -rf /v/data/* /v/uploads/* /v/outputs/*
      tar xzf /in/media.tgz -C /v
      rm -f /v/data/app.db /v/data/app.db-wal /v/data/app.db-shm
      cp /in/app.db /v/data/app.db
      chown -R 10001:10001 /v'
  docker compose -f "$COMPOSE_FILE" up -d "$SERVICE"
  echo "[restore] waiting for health..."
  for _ in $(seq 1 60); do
    if docker exec "$SERVICE" curl -fsS http://localhost:8080/health >/dev/null 2>&1; then
      echo "[restore] healthy — restore complete"
      exit 0
    fi
    sleep 2
  done
  echo "[restore] WARNING: health check did not pass within 120s; inspect logs" >&2
  exit 1
else
  if [[ ! -d "$MOTION_HOME" ]]; then
    echo "[restore] host mode: MOTION_HOME ($MOTION_HOME) does not exist" >&2
    exit 1
  fi
  if pgrep -f "motion.*\.jar" >/dev/null 2>&1; then
    echo "[restore] host mode: stop the running Motion process first" >&2
    exit 1
  fi
  if [[ -f "$BACKUP_DIR/env" ]]; then
    cp "$BACKUP_DIR/env" "$MOTION_HOME/.env"
    chmod 600 "$MOTION_HOME/.env"
  fi
  ( cd "$MOTION_HOME"
    rm -rf data.bak uploads.bak outputs.bak
    # Preserve whatever (if anything) is left of the old data, then lay down
    # the backup archive, which contains data/, uploads/ and outputs/ wholesale.
    for d in data uploads outputs; do
      if [[ -d "$d" ]]; then mv "$d" "$d.bak"; fi
    done
    tar xzf "$(cd "$BACKUP_DIR" && pwd)/media.tgz"
    rm -f data/app.db data/app.db-wal data/app.db-shm
    cp "$(cd "$BACKUP_DIR" && pwd)/app.db" data/app.db )
  echo "[restore] host restore complete. Previous (partial) data kept in $MOTION_HOME/{data,uploads,outputs}.bak if any."
  echo "[restore] start the application and check /health"
fi
