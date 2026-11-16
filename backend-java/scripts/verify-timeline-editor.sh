#!/usr/bin/env bash
# Packaged-JAR runtime evidence for the Phase 6 timeline editor: previews, immutable rendering,
# compose, encryption, cancellation, restart recovery, and cleanup. All paths are disposable.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/motion-java-0.1.0-PHASE4.jar"
EVIDENCE="$ROOT/TIMELINE_EDITOR_RUNTIME_EVIDENCE.log"

if [[ ! -f "$JAR" ]]; then
  echo "missing packaged JAR: $JAR (run ./mvnw -B clean verify first)" >&2
  exit 1
fi
for tool in java curl ffmpeg ffprobe sqlite3 python3; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done

WORK="$(mktemp -d /tmp/opencode/motion-timeline-XXXXXX)"
PORT="${VERIFY_TIMELINE_PORT:-18081}"
BASE="http://127.0.0.1:${PORT}"
DB="$WORK/data/app.db"
COOKIES="$WORK/cookies.txt"
BODY="$WORK/body"
PAYLOAD="$WORK/payload"
APP_LOG="$WORK/app.log"
APP_PID=""
KEY="$(python3 -c 'import base64,os;print(base64.b64encode(os.urandom(32)).decode())')"

: >"$EVIDENCE"
exec > >(tee -a "$EVIDENCE") 2>&1

say() { printf '\n[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
ok() { echo "  PASS: $*"; }
fail() { echo "  FAIL: $*" >&2; exit 1; }
check() { local message="$1"; shift; if eval "$*"; then ok "$message"; else fail "$message"; fi; }

stop_app() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 40); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.25; done
    kill -9 "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  APP_PID=""
}
trap 'stop_app; rm -rf "$WORK"' EXIT

start_app() {
  say "starting packaged JAR on port $PORT (disposable data dir: $WORK)"
  java -jar "$JAR" \
    --server.port="$PORT" \
    --spring.profiles.active=local \
    --spring.datasource.url="jdbc:sqlite:${DB}?busy_timeout=5000&journal_mode=WAL" \
    --motion.storage.data-dir="$WORK/data" \
    --motion.storage.upload-dir="$WORK/uploads" \
    --motion.storage.output-dir="$WORK/outputs" \
    --motion.storage.temp-dir="$WORK/data/tmp" \
    --motion.storage.preview-dir="$WORK/data/previews" \
    --motion.static-dir="$WORK/web" \
    --motion.encryption.key="$KEY" \
    --motion.rate-limit.register=10000 \
    --motion.rate-limit.login=10000 \
    --motion.quota.min-free-disk-bytes=0 \
    --motion.quota.max-active-uploads-per-user=20 \
    --motion.quota.max-source-files-per-user=100 \
    --motion.quota.max-outputs-per-user=100 \
    --motion.jobs.worker-count=2 \
    --motion.jobs.poll-interval=100ms \
    --motion.jobs.progress-interval=25ms \
    --motion.jobs.max-concurrent-jobs-per-user=1 \
    --motion.jobs.max-global-concurrent-jobs=3 \
    --motion.jobs.max-queued-jobs=20 \
    --cleanup.retention=0 \
    >"$APP_LOG" 2>&1 &
  APP_PID=$!
  local ready=0
  for _ in $(seq 1 120); do
    if curl -fsS "$BASE/health" >/dev/null 2>&1; then ready=1; break; fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      tail -80 "$APP_LOG" >&2
      fail "packaged JAR exited during startup"
    fi
    sleep 0.5
  done
  [[ "$ready" == 1 ]] || { tail -80 "$APP_LOG" >&2; fail "packaged JAR did not become healthy"; }
  ok "health endpoint responds from the packaged JAR"
}

csrf_token() {
  awk -F '\t' '$6 == "motion_csrf" {print $7}' "$COOKIES" 2>/dev/null | tail -1
}

request() {
  local method="$1" path="$2" payload="${3:-}" content_type="${4:-}"
  local -a args=(-sS -b "$COOKIES" -c "$COOKIES" -o "$BODY" -w '%{http_code}' -X "$method")
  local token
  token="$(csrf_token)"
  [[ -n "$token" ]] && args+=(-H "X-CSRF-Token: $token")
  [[ -n "$content_type" ]] && args+=(-H "Content-Type: $content_type")
  [[ -n "$payload" ]] && args+=(--data-binary "$payload")
  curl "${args[@]}" "$BASE$path"
}

register() {
  local email="timeline-$(python3 -c 'import uuid;print(uuid.uuid4())')@example.com"
  : >"$COOKIES"
  local code
  code="$(request POST /api/auth/register \
    "{\"email\":\"$email\",\"password\":\"password123\",\"passwordConfirm\":\"password123\"}" \
    application/json)"
  [[ "$code" == 201 ]] || fail "register returned $code: $(cat "$BODY")"
  code="$(request GET /api/auth/csrf)"
  [[ "$code" == 200 ]] || fail "csrf returned $code: $(cat "$BODY")"
  [[ -n "$(csrf_token)" ]] || fail "csrf cookie was not set"
  echo "$email"
}

json_field() { python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$BODY" "$1"; }

db() { sqlite3 "$DB" "$1"; }
job_field() { db "SELECT $1 FROM jobs WHERE id = '$2'"; }
failed_job_count() { db "SELECT COUNT(*) FROM jobs WHERE status = 'failed'"; }
scratch_count() { find "$WORK/data/tmp" -maxdepth 1 -name 'processing-*' 2>/dev/null | wc -l; }

upload_video() {
  local file="$1" name="$2" code
  code="$(curl -sS -b "$COOKIES" -c "$COOKIES" -o "$BODY" -w '%{http_code}' \
    -H "X-CSRF-Token: $(csrf_token)" -F "video=@$file;filename=$name" "$BASE/api/upload")"
  [[ "$code" == 201 ]] || fail "upload returned $code: $(cat "$BODY")"
  json_field id
}

wait_terminal() {
  local job_id="$1" timeout="${2:-180}" elapsed=0 status
  while (( elapsed < timeout )); do
    status="$(job_field status "$job_id")"
    case "$status" in
      completed|failed|cancelled) echo "$status"; return 0 ;;
    esac
    sleep 0.1
    elapsed=$((elapsed + 1))
  done
  echo "timeout waiting for terminal, last='$status'" >&2
  return 1
}

wait_for_status() {
  local job_id="$1" wanted="$2" timeout="${3:-60}" elapsed=0 status
  while (( elapsed < timeout )); do
    status="$(job_field status "$job_id")"
    [[ "$status" == "$wanted" ]] && { echo "$status"; return 0; }
    sleep 0.1
    elapsed=$((elapsed + 1))
  done
  echo "timeout waiting for '$wanted', last='$status'" >&2
  return 1
}

ffmpeg_pids_for_workdir() { pgrep -f "ffmpeg.*$WORK/data/tmp/processing-" || true; }

download_output() {
  local job_id="$1" target="$2" code
  code="$(curl -sS -b "$COOKIES" -o "$target" -w '%{http_code}' "$BASE/api/jobs/$job_id/output")"
  echo "$code"
}

probe_duration() { ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$1"; }
probe_stream() {
  local key="$1" file="$2"
  ffprobe -v error -select_streams "$3" -show_entries "stream=$key" \
    -of default=nw=1:nk=1 "$file" | head -1
}

simple_project_json() {
  local asset_id="$1" name="$2" source_out="$3" render="$4"
  python3 - "$asset_id" "$name" "$source_out" "$render" <<'PY' >"$PAYLOAD"
import json, sys
asset, name, out, render = sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4]
project = {
    "name": name,
    "schemaVersion": 1,
    "tracks": [{
        "id": "v1", "type": "video", "order": 0, "visible": True, "muted": False,
        "clips": [{
            "id": "c1", "kind": "video", "assetId": asset,
            "sourceIn": 0, "sourceOut": out, "timelineStart": 0,
            "volume": 1, "opacity": 1, "scale": 1,
        }],
    }],
    "render": json.loads(render),
}
print(json.dumps(project if render != "saved" else project))
PY
}

render_body() {
  python3 - "$PAYLOAD" <<'PY'
import json, sys
project = json.load(open(sys.argv[1]))
print(json.dumps({"project": project}))
PY
}

say "Phase 0: disposable generated media"
mkdir -p "$WORK/data" "$WORK/uploads" "$WORK/outputs" "$WORK/web"
ffmpeg -y -v error -f lavfi -i 'testsrc=size=320x240:rate=15' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' \
  -t 3 -c:v mpeg4 -q:v 5 -c:a aac -b:a 64k -shortest "$WORK/audio.mp4"
ffmpeg -y -v error -f lavfi -i 'testsrc=size=160x120:rate=10' \
  -t 2 -c:v mpeg4 -q:v 5 "$WORK/overlay.mp4"
ffmpeg -y -v error -f lavfi -i 'testsrc=size=640x360:rate=15' \
  -t 45 -c:v mpeg4 -q:v 5 "$WORK/long.mp4"
ok "generated audio.mp4, overlay.mp4, and long.mp4 with FFmpeg"

start_app

say "Phase 1: encrypted ownership-aware cached previews"
register >/dev/null
AUDIO_ID="$(upload_video "$WORK/audio.mp4" "audio.mp4")"
OVERLAY_ID="$(upload_video "$WORK/overlay.mp4" "overlay.mp4")"
LONG_ID="$(upload_video "$WORK/long.mp4" "long.mp4")"
STORED_SOURCE="$(db "SELECT original_path FROM videos WHERE id = '$AUDIO_ID'")"
echo "  uploaded ids: audio=$AUDIO_ID overlay=$OVERLAY_ID long=$LONG_ID"
check "uploaded source is MOTIONE1 encrypted" '[[ "$(head -c 8 "$STORED_SOURCE")" == MOTIONE1 ]]'

CODE="$(request GET "/api/videos/$AUDIO_ID/thumbnails")"
[[ "$CODE" == 200 ]] || fail "thumbnails returned $CODE: $(cat "$BODY")"
echo "  thumbnails json=$(cat "$BODY")"
check "thumbnail metadata uses Go defaults" \
  'python3 -c "import json,sys;d=json.load(open(sys.argv[1]));sys.exit(0 if d[\"count\"]==12 and d[\"tileHeight\"]==72 and d[\"duration\"]>0 and d[\"spriteUrl\"].startswith(\"/api/videos/\") else 1)" "$BODY"'
STRIP="$WORK/data/previews/strip_${AUDIO_ID}_12_72.jpg"
check "strip cache filename is exact" '[[ -f "$STRIP" ]]'
CODE="$(curl -sS -b "$COOKIES" -o "$WORK/sprite.jpg" -w '%{http_code}' \
  "$BASE/api/videos/$AUDIO_ID/thumbnails/sprite?count=12")"
check "sprite is served as JPEG" '[[ "$CODE" == 200 && "$(head -c 2 "$WORK/sprite.jpg" | xxd -p)" == ffd8 ]]'

CODE="$(request GET "/api/videos/$AUDIO_ID/waveform?buckets=200")"
[[ "$CODE" == 200 ]] || fail "waveform returned $CODE: $(cat "$BODY")"
echo "  waveform buckets=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["buckets"])' "$BODY") max_peak=$(python3 -c 'import json,sys;print(max(json.load(open(sys.argv[1]))["peaks"]))' "$BODY")"
check "waveform cache filename is exact" \
  '[[ -f "$WORK/data/previews/wave_'"$AUDIO_ID"'_200.json" ]]'
check "waveform peaks are normalised 0..1" \
  'python3 -c "import json,sys;p=json.load(open(sys.argv[1]))[\"peaks\"];sys.exit(0 if len(p)==200 and max(p)>0 and max(p)<=1 else 1)" "$BODY"'
cp "$COOKIES" "$WORK/owner-cookies.txt"

say "Phase 2: saved project renders from an immutable submission snapshot"
simple_project_json "$AUDIO_ID" "Snapshot" 2.5 '{}'
CODE="$(request POST /api/timeline-projects "$(cat "$PAYLOAD")" application/json)"
[[ "$CODE" == 201 ]] || fail "save project returned $CODE: $(cat "$BODY")"
PROJECT_ID="$(json_field id)"
echo "  saved timeline id=$PROJECT_ID"
CODE="$(request POST /api/timeline/render "{\"projectId\":\"$PROJECT_ID\"}" application/json)"
[[ "$CODE" == 202 ]] || fail "timeline render returned $CODE: $(cat "$BODY")"
JOB_SIMPLE="$(json_field id)"
echo "  submitted job=$JOB_SIMPLE status=$(json_field status)"

simple_project_json "$AUDIO_ID" "Mutated" 0.5 '{}'
CODE="$(request PUT "/api/timeline-projects/$PROJECT_ID" "$(cat "$PAYLOAD")" application/json)"
[[ "$CODE" == 200 ]] || fail "project mutation returned $CODE: $(cat "$BODY")"
OPS="$(job_field operations "$JOB_SIMPLE")"
echo "  job operations after project mutation=$OPS"
check "saved-project mutation did not change queued operations" \
  '[[ "$OPS" == *"\"end\":\"2.5\""* && "$OPS" != *"\"end\":\"0.5\""* ]]'

TERMINAL="$(wait_terminal "$JOB_SIMPLE" 180)"
check "simple timeline render completed" '[[ "$TERMINAL" == completed && "$(job_field progress "$JOB_SIMPLE")" == 100.0 ]]'
OUTPUT_PATH="$(job_field output_path "$JOB_SIMPLE")"
check "rendered output is encrypted at rest" '[[ "$(head -c 8 "$OUTPUT_PATH")" == MOTIONE1 ]]'
DL_CODE="$(download_output "$JOB_SIMPLE" "$WORK/simple.mp4")"
check "render output downloads decrypted" \
  '[[ "$DL_CODE" == 200 && "$(dd if="$WORK/simple.mp4" bs=1 skip=4 count=4 status=none)" == ftyp ]]'
echo "  probe duration=$(probe_duration "$WORK/simple.mp4") video=$(probe_stream codec_name "$WORK/simple.mp4" v:0)"
check "downloaded render is probeable and decodes" \
  'python3 -c "import sys;sys.exit(0 if float(sys.argv[1])>2 else 1)" "$(probe_duration "$WORK/simple.mp4")"'
ffmpeg -v error -i "$WORK/simple.mp4" -f null -

say "Phase 3: compose plan produces requested visual and audio shape"
python3 - "$AUDIO_ID" "$OVERLAY_ID" <<'PY' >"$PAYLOAD"
import json, sys
base, overlay = sys.argv[1:]
project = {
    "name": "Compose", "schemaVersion": 1,
    "render": {"width": 320, "height": 240, "fps": 15},
    "tracks": [
        {"id": "v1", "type": "video", "order": 0, "visible": True, "clips": [
            {"id": "c1", "kind": "video", "assetId": base, "sourceIn": 0,
             "sourceOut": 3, "timelineStart": 0, "volume": 1}]},
        {"id": "i1", "type": "image", "order": 1, "visible": True, "clips": [
            {"id": "c2", "kind": "image", "assetId": overlay, "sourceIn": 0,
             "sourceOut": 1.5, "timelineStart": 0.3, "x": 20, "y": 20, "scale": 0.5}]},
        {"id": "a1", "type": "audio", "order": 2, "visible": True, "clips": [
            {"id": "c3", "kind": "audio", "assetId": base, "sourceIn": 0,
             "sourceOut": 3, "timelineStart": 0, "volume": 1}]},
    ],
}
print(json.dumps({"project": project}))
PY
CODE="$(request POST /api/timeline/render "$(cat "$PAYLOAD")" application/json)"
[[ "$CODE" == 202 ]] || fail "compose render returned $CODE: $(cat "$BODY")"
JOB_COMPOSE="$(json_field id)"
COMPOSE_OPS="$(job_field operations "$JOB_COMPOSE")"
echo "  compose job operations=$COMPOSE_OPS"
check "worker snapshot contains one compose operation" \
  'python3 -c "import json,sys;o=json.loads(sys.argv[1])[\"operations\"];sys.exit(0 if len(o)==1 and o[0][\"type\"]==\"compose\" else 1)" "$COMPOSE_OPS"'
TERMINAL="$(wait_terminal "$JOB_COMPOSE" 180)"
check "compose render completed" '[[ "$TERMINAL" == completed ]]'
DL_CODE="$(download_output "$JOB_COMPOSE" "$WORK/compose.mp4")"
WIDTH="$(probe_stream width "$WORK/compose.mp4" v:0)"
HEIGHT="$(probe_stream height "$WORK/compose.mp4" v:0)"
AUDIO_CODEC="$(probe_stream codec_name "$WORK/compose.mp4" a:0)"
echo "  compose probe width=$WIDTH height=$HEIGHT audio=$AUDIO_CODEC duration=$(probe_duration "$WORK/compose.mp4")"
check "compose output has requested shape and audio" \
  '[[ "$DL_CODE" == 200 && "$WIDTH" == 320 && "$HEIGHT" == 240 && -n "$AUDIO_CODEC" ]]'
ffmpeg -v error -i "$WORK/compose.mp4" -f null -

say "Phase 4: cross-user assets are rejected before queueing"
cp "$WORK/owner-cookies.txt" "$COOKIES"
register >/dev/null
simple_project_json "$AUDIO_ID" "Stolen" 1 '{}'
CODE="$(request POST /api/timeline/render "$(render_body)" application/json)"
ERROR="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["validationErrors"][0]["code"])' "$BODY")"
echo "  other user render http=$CODE code=$ERROR"
check "cross-user asset returns Go validation error" \
  '[[ "$CODE" == 400 && "$ERROR" == asset_unavailable ]]'

say "Phase 5: running cancellation stops the timeline FFmpeg process"
register >/dev/null
LONG_CANCEL="$(upload_video "$WORK/long.mp4" "timeline-cancel.mp4")"
simple_project_json "$LONG_CANCEL" "Cancel" 30 '{}'
CODE="$(request POST /api/timeline/render "$(render_body)" application/json)"
[[ "$CODE" == 202 ]] || fail "cancel render returned $CODE: $(cat "$BODY")"
JOB_CANCEL="$(json_field id)"
wait_for_status "$JOB_CANCEL" processing 60 >/dev/null || fail "cancel job never reached processing"
FIRST_PID=""
for _ in $(seq 1 200); do
  FIRST_PID="$(ffmpeg_pids_for_workdir | head -1)"
  [[ -n "$FIRST_PID" ]] && break
  sleep 0.1
done
if [[ -z "$FIRST_PID" ]]; then
  echo "  cancel job status=$(job_field status "$JOB_CANCEL") error=$(job_field error "$JOB_CANCEL")" >&2
  tail -40 "$APP_LOG" >&2
  fail "no FFmpeg process found for running timeline render"
fi
echo "  attached FFmpeg pid=$FIRST_PID for job=$JOB_CANCEL"
CODE="$(request POST "/api/jobs/$JOB_CANCEL/cancel" '{}')"
echo "  cancel http=$CODE status=$(json_field status)"
check "running cancel returns cancel_requested" \
  '[[ "$CODE" == 200 && "$(json_field status)" == cancel_requested ]]'
for _ in $(seq 1 150); do kill -0 "$FIRST_PID" 2>/dev/null || break; sleep 0.1; done
check "cancelled FFmpeg process disappeared" '! kill -0 "$FIRST_PID" 2>/dev/null'
check "cancelled render has no output" \
  '[[ "$(wait_terminal "$JOB_CANCEL" 60)" == cancelled && -z "$(job_field output_path "$JOB_CANCEL")" ]]'

say "Phase 6: abrupt restart recovery reruns the immutable snapshot"
register >/dev/null
LONG_RECOVER="$(upload_video "$WORK/audio.mp4" "timeline-recovery.mp4")"
simple_project_json "$LONG_RECOVER" "Recover" 2.5 '{}'
CODE="$(request POST /api/timeline/render "$(render_body)" application/json)"
[[ "$CODE" == 202 ]] || fail "recovery render returned $CODE: $(cat "$BODY")"
JOB_RECOVER="$(json_field id)"
wait_for_status "$JOB_RECOVER" processing 60 >/dev/null || fail "recovery job never reached processing"
OLD_TOKEN="$(job_field execution_token "$JOB_RECOVER")"
OLD_ATTEMPT="$(job_field attempt "$JOB_RECOVER")"
echo "  before kill: status=$(job_field status "$JOB_RECOVER") token=${OLD_TOKEN:0:8} attempt=$OLD_ATTEMPT"
kill -9 "$APP_PID"
wait "$APP_PID" 2>/dev/null || true
APP_PID=""
# A real restart also terminates the old process group; remove any orphan left by kill -9.
for pid in $(ffmpeg_pids_for_workdir); do kill -9 "$pid" 2>/dev/null || true; done
check "DB retains interrupted lease after hard kill" \
  '[[ "$(job_field status "$JOB_RECOVER")" == processing && -n "$OLD_TOKEN" ]]'
start_app
RECOVERED=0
NEW_TOKEN=""
NEW_ATTEMPT="$OLD_ATTEMPT"
for _ in $(seq 1 150); do
  STATUS="$(job_field status "$JOB_RECOVER")"
  NEW_TOKEN="$(job_field execution_token "$JOB_RECOVER")"
  NEW_ATTEMPT="$(job_field attempt "$JOB_RECOVER")"
  # The recovered job can finish before the first poll; a new token/attempt proves recovery ran.
  if [[ "$NEW_TOKEN" != "$OLD_TOKEN" && "$NEW_ATTEMPT" -gt "$OLD_ATTEMPT" ]]; then
    RECOVERED=1
    echo "  after recovery: status=$STATUS token=${NEW_TOKEN:0:8} attempt=$NEW_ATTEMPT"
    break
  fi
  sleep 0.1
done
check "startup recovery requeued the interrupted lease" '[[ "$RECOVERED" == 1 ]]'
check "recovery invalidates the stale token" '[[ "$NEW_TOKEN" != "$OLD_TOKEN" ]]'
check "recovered render completed with a fresh lease" \
  '[[ "$(wait_terminal "$JOB_RECOVER" 180)" == completed && "$NEW_ATTEMPT" -gt "$OLD_ATTEMPT" ]]'

say "Summary"
check "no failed jobs in runtime evidence" '[[ "$(failed_job_count)" == 0 ]]'
check "no private workspace scratch remains" '[[ "$(scratch_count)" == 0 ]]'
echo "  packaged JAR: $JAR"
echo "  evidence log: $EVIDENCE"
echo "ALL PACKAGED-JAR TIMELINE EDITOR RUNTIME CHECKS PASSED"
