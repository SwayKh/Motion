#!/usr/bin/env bash
# Packaged-JAR runtime evidence for the persistent asynchronous job system.
# Everything is disposable: a random port, a temporary SQLite DB, and generated media.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/motion-java-0.1.0-PHASE4.jar"
EVIDENCE="$ROOT/JOB_SYSTEM_RUNTIME_EVIDENCE.log"

if [[ ! -f "$JAR" ]]; then
  echo "missing packaged JAR: $JAR (run ./mvnw -B clean verify first)" >&2
  exit 1
fi
for tool in java curl ffmpeg ffprobe sqlite3 python3; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done

WORK="$(mktemp -d /tmp/opencode/motion-async-XXXXXX)"
PORT="${VERIFY_PORT:-18080}"
BASE="http://127.0.0.1:${PORT}"
DB="$WORK/data/app.db"
COOKIES="$WORK/cookies.txt"
BODY="$WORK/body"
APP_PID=""
APP_LOG="$WORK/app.log"
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
  local email="runtime-$(python3 -c 'import uuid;print(uuid.uuid4())')@example.com"
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

declare -A JOB_COOKIE
remember_job_cookie() {
  local job_id="$1"
  local jar="$WORK/cookies-$job_id.txt"
  cp "$COOKIES" "$jar"
  JOB_COOKIE["$job_id"]="$jar"
}

cancel_job() {
  local job_id="$1"
  local jar="${JOB_COOKIE[$job_id]:-$COOKIES}"
  local current="$COOKIES"
  local code
  COOKIES="$jar"
  code="$(request POST "/jobs/$job_id/cancel" '{}')"
  COOKIES="$current"
  echo "$code"
}

upload_video() {
  local file="$1" name="$2" code
  code="$(curl -sS -b "$COOKIES" -c "$COOKIES" -o "$BODY" -w '%{http_code}' \
    -H "X-CSRF-Token: $(csrf_token)" -F "video=@$file;filename=$name" "$BASE/upload")"
  [[ "$code" == 201 ]] || fail "upload returned $code: $(cat "$BODY")"
  json_field id
}

submit_flip() {
  local video_id="$1" code
  code="$(request POST "/videos/$video_id/process" \
    '{"operations":[{"type":"flip","orientation":1}]}' application/json)"
  echo "$code"
}

db() { sqlite3 "$DB" "$1"; }

job_field() { db "SELECT $1 FROM jobs WHERE id = '$2'"; }
job_exists() { [[ -n "$(job_field id "$1")" ]]; }
video_field() { db "SELECT $1 FROM videos WHERE id = '$2'"; }
failed_job_count() { db "SELECT COUNT(*) FROM jobs WHERE status = 'failed'"; }

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

ffmpeg_pids_for_workdir() {
  pgrep -f "ffmpeg.*$WORK/data/tmp/processing-" || true
}

say "Phase 0: disposable fixtures"
mkdir -p "$WORK/data" "$WORK/uploads" "$WORK/outputs" "$WORK/web"
ffmpeg -y -v error -f lavfi -i 'testsrc=size=1280x720:rate=30' \
  -t 180 -c:v mpeg4 -q:v 5 "$WORK/long.mp4"
ok "generated $(du -h "$WORK/long.mp4" | cut -f1) disposable source video"

start_app

say "Phase 1: submit is durable and asynchronous; progress is observable"
register >/dev/null
VIDEO_A="$(upload_video "$WORK/long.mp4" "async-a.mp4")"
START_NS=$(date +%s%N)
SUBMIT_CODE="$(submit_flip "$VIDEO_A")"
SUBMIT_MS=$(( ($(date +%s%N) - START_NS) / 1000000 ))
[[ "$SUBMIT_CODE" == 202 ]] || fail "submit returned $SUBMIT_CODE: $(cat "$BODY")"
JOB_A="$(json_field id)"
remember_job_cookie "$JOB_A"
SUBMIT_STATE="$(json_field status)"
echo "  submit http=202 id=$JOB_A response_status=$SUBMIT_STATE elapsed=${SUBMIT_MS}ms"
check "submit returns before FFmpeg finishes" '[[ "$SUBMIT_MS" -lt 5000 && "$SUBMIT_STATE" == "queued" ]]'
check "job is persisted before execution" 'job_exists "$JOB_A"'

SAW_PROCESSING=0
SAW_PROGRESS=0
MAX_PROGRESS=0
for _ in $(seq 1 600); do
  status="$(job_field status "$JOB_A")"
  progress="$(job_field progress "$JOB_A")"
  [[ "$status" == processing ]] && SAW_PROCESSING=1
  if [[ "$status" != completed && "$status" != failed && "$status" != cancelled ]]; then
    python3 -c "import sys;p=float(sys.argv[1]);sys.exit(0 if 0 < p < 100 else 1)" "$progress" && SAW_PROGRESS=1
    MAX_PROGRESS="$(python3 -c 'import sys;print(max(float(sys.argv[1]),float(sys.argv[2])))' "$MAX_PROGRESS" "$progress")"
  fi
  [[ "$status" == completed || "$status" == failed || "$status" == cancelled ]] && break
  sleep 0.1
done
TERMINAL_A="$(wait_terminal "$JOB_A")"
echo "  polling samples: saw_processing=$SAW_PROGRESS/$SAW_PROCESSING max_nonterminal_progress=$MAX_PROGRESS terminal=$TERMINAL_A"
check "polling observes processing" '[[ "$SAW_PROCESSING" == 1 ]]'
check "polling observes 0 < progress < 100" '[[ "$SAW_PROGRESS" == 1 ]]'
check "job completes with 100 percent" '[[ "$TERMINAL_A" == completed && "$(job_field progress "$JOB_A")" == 100.0 ]]'

OUTPUT_PATH="$(job_field output_path "$JOB_A")"
RAW_MAGIC="$(head -c 8 "$OUTPUT_PATH")"
DL_CODE="$(curl -sS -b "$COOKIES" -o "$WORK/download.mp4" -w '%{http_code}' "$BASE/jobs/$JOB_A/output")"
DL_MAGIC="$(dd if="$WORK/download.mp4" bs=1 skip=4 count=4 status=none)"
echo "  stored magic=$RAW_MAGIC downloaded box=$DL_MAGIC http=$DL_CODE bytes=$(stat -c%s "$WORK/download.mp4")"
check "stored output is MOTIONE1 encrypted" '[[ "$RAW_MAGIC" == MOTIONE1 ]]'
check "download decrypts to a playable MP4" '[[ "$DL_CODE" == 200 && "$DL_MAGIC" == ftyp ]]'
ffprobe -v error -show_entries format=duration -of default=nw=1 "$WORK/download.mp4" | sed 's/^/  /'
ok "downloaded output is probeable after decryption"

say "Phase 2: running cancellation terminates FFmpeg and leaves no output"
register >/dev/null
VIDEO_B="$(upload_video "$WORK/long.mp4" "cancel-running.mp4")"
SUBMIT_CODE="$(submit_flip "$VIDEO_B")"
[[ "$SUBMIT_CODE" == 202 ]] || fail "cancel target submit returned $SUBMIT_CODE"
JOB_B="$(json_field id)"
remember_job_cookie "$JOB_B"
wait_for_status "$JOB_B" processing 60 >/dev/null || fail "job $JOB_B never reached processing"
for _ in $(seq 1 100); do
  PROGRESS_B="$(job_field progress "$JOB_B")"
  python3 -c "import sys;sys.exit(0 if float(sys.argv[1]) > 0 else 1)" "$PROGRESS_B" && break
  sleep 0.1
done
PIDS_BEFORE="$(ffmpeg_pids_for_workdir)"
[[ -n "$PIDS_BEFORE" ]] || fail "no FFmpeg process found for running job $JOB_B"
FIRST_PID="$(echo "$PIDS_BEFORE" | head -1)"
echo "  attached FFmpeg pid=$FIRST_PID progress=$PROGRESS_B"
CANCEL_CODE="$(cancel_job "$JOB_B")"
[[ "$CANCEL_CODE" == 200 ]] || fail "cancel returned $CANCEL_CODE: $(cat "$BODY")"
CANCEL_STATE="$(json_field status)"
CANCEL_AT="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("cancelRequestedAt"))' "$BODY")"
echo "  cancel http=200 status=$CANCEL_STATE cancelRequestedAt=$CANCEL_AT"
check "running cancel returns cancel_requested" '[[ "$CANCEL_STATE" == cancel_requested && "$CANCEL_AT" != None ]]'
for _ in $(seq 1 150); do
  kill -0 "$FIRST_PID" 2>/dev/null || break
  sleep 0.1
done
check "attached FFmpeg process disappeared" '! kill -0 "$FIRST_PID" 2>/dev/null'
TERMINAL_B="$(wait_terminal "$JOB_B")"
check "cancelled job is terminal" '[[ "$TERMINAL_B" == cancelled ]]'
check "cancelled job has no output path" '[[ -z "$(job_field output_path "$JOB_B")" ]]'
check "cancelled video has no output" '[[ -z "$(video_field output_path "$VIDEO_B")" ]]'
REPEAT_CODE="$(cancel_job "$JOB_B")"
REPEAT_ERROR="$(json_field error)"
echo "  repeated cancel http=$REPEAT_CODE error=$REPEAT_ERROR"
check "repeated cancel is rejected" '[[ "$REPEAT_CODE" == 409 && "$REPEAT_ERROR" == "job has already finished" ]]'

say "Phase 3: queued cancellation is immediate and distinct"
# Saturate both workers with distinct users (per-user limit is one).
register >/dev/null
VIDEO_BUSY="$(upload_video "$WORK/long.mp4" "busy.mp4")"
BUSY_CODE="$(submit_flip "$VIDEO_BUSY")"
[[ "$BUSY_CODE" == 202 ]] || fail "busy submit returned $BUSY_CODE: $(cat "$BODY")"
JOB_BUSY="$(json_field id)"
remember_job_cookie "$JOB_BUSY"
register >/dev/null
VIDEO_BUSY2="$(upload_video "$WORK/long.mp4" "busy-2.mp4")"
BUSY2_CODE="$(submit_flip "$VIDEO_BUSY2")"
[[ "$BUSY2_CODE" == 202 ]] || fail "second busy submit returned $BUSY2_CODE: $(cat "$BODY")"
JOB_BUSY2="$(json_field id)"
remember_job_cookie "$JOB_BUSY2"
wait_for_status "$JOB_BUSY" processing 60 >/dev/null || fail "busy job never started"
wait_for_status "$JOB_BUSY2" processing 60 >/dev/null || fail "second busy job never started"
register >/dev/null
VIDEO_Q="$(upload_video "$WORK/long.mp4" "queued-cancel.mp4")"
Q_CODE="$(submit_flip "$VIDEO_Q")"
[[ "$Q_CODE" == 202 ]] || fail "queued submit returned $Q_CODE: $(cat "$BODY")"
JOB_Q="$(json_field id)"
remember_job_cookie "$JOB_Q"
Q_STATUS="$(job_field status "$JOB_Q")"
echo "  busy=$JOB_BUSY busy2=$JOB_BUSY2 queued=$JOB_Q status=$Q_STATUS submit=$Q_CODE"
check "third active job remains queued" '[[ "$Q_STATUS" == queued ]]'
QC_CODE="$(cancel_job "$JOB_Q")"
[[ "$QC_CODE" == 200 ]] || fail "queued cancel returned $QC_CODE: $(cat "$BODY")"
QC_STATE="$(json_field status)"
QC_FINISHED="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("finishedAt"))' "$BODY")"
QC_CANCEL_AT="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("cancelRequestedAt"))' "$BODY")"
echo "  queued cancel status=$QC_STATE finishedAt=$QC_FINISHED cancelRequestedAt=$QC_CANCEL_AT"
check "queued cancel is immediately cancelled" '[[ "$QC_STATE" == cancelled && "$QC_FINISHED" != None && "$QC_CANCEL_AT" == None ]]'
for id in "$JOB_BUSY" "$JOB_BUSY2"; do
  cancel_job "$id" >/dev/null || true
  wait_terminal "$id" >/dev/null || fail "busy job $id did not stop"
done

say "Phase 4: atomic per-user and global quotas"
register >/dev/null
VIDEO_D1="$(upload_video "$WORK/long.mp4" "quota-1.mp4")"
Q1_CODE="$(submit_flip "$VIDEO_D1")"
JOB_D1="$(json_field id)"
remember_job_cookie "$JOB_D1"
VIDEO_D2="$(upload_video "$WORK/long.mp4" "quota-2.mp4")"
Q2_CODE="$(submit_flip "$VIDEO_D2")"
Q2_ERROR="$(json_field error)"
echo "  first submit=$Q1_CODE second same-user=$Q2_CODE error=$Q2_ERROR"
check "per-user quota is atomic" '[[ "$Q1_CODE" == 202 && "$Q2_CODE" == 429 && "$Q2_ERROR" == "too many active jobs; try again later" ]]'
cancel_job "$JOB_D1" >/dev/null || true
wait_terminal "$JOB_D1" >/dev/null || true

GLOBAL_JOBS=()
for i in 1 2 3; do
  register >/dev/null
  VIDEO_G="$(upload_video "$WORK/long.mp4" "global-$i.mp4")"
  G_CODE="$(submit_flip "$VIDEO_G")"
  [[ "$G_CODE" == 202 ]] || fail "global user $i submit returned $G_CODE: $(cat "$BODY")"
  GLOBAL_ID="$(json_field id)"
  GLOBAL_JOBS+=("$GLOBAL_ID")
  remember_job_cookie "$GLOBAL_ID"
done
register >/dev/null
VIDEO_G4="$(upload_video "$WORK/long.mp4" "global-4.mp4")"
QG4_CODE="$(submit_flip "$VIDEO_G4")"
QG4_ERROR="$(json_field error)"
echo "  global accepts=${#GLOBAL_JOBS[@]} fourth user=$QG4_CODE error=$QG4_ERROR"
check "global quota is atomic" '[[ "$QG4_CODE" == 429 && "$QG4_ERROR" == "too many active jobs; try again later" ]]'
ACTIVE="$(db "SELECT COUNT(*) FROM jobs WHERE status IN ('queued','processing','cancel_requested')")"
echo "  active jobs in durable queue=$ACTIVE"
check "global active count is bounded" '[[ "$ACTIVE" == 3 ]]'
for id in "${GLOBAL_JOBS[@]}"; do
  cancel_job "$id" >/dev/null || true
  wait_terminal "$id" >/dev/null || true
done

say "Phase 5: abrupt restart recovery"
# Drain or cancel everything before isolating the recovery job.
for id in $(db "SELECT id FROM jobs WHERE status IN ('queued','processing','cancel_requested')"); do
  cancel_job "$id" >/dev/null || true
  wait_terminal "$id" >/dev/null || true
done
register >/dev/null
VIDEO_E="$(upload_video "$WORK/long.mp4" "recovery.mp4")"
RECOVER_CODE="$(submit_flip "$VIDEO_E")"
JOB_E="$(json_field id)"
remember_job_cookie "$JOB_E"
wait_for_status "$JOB_E" processing 60 >/dev/null || fail "recovery job never reached processing"
for _ in $(seq 1 100); do
  python3 -c "import sys;sys.exit(0 if float(sys.argv[1]) > 0 else 1)" "$(job_field progress "$JOB_E")" && break
  sleep 0.1
done
OLD_TOKEN="$(job_field execution_token "$JOB_E")"
OLD_ATTEMPT="$(job_field attempt "$JOB_E")"
OLD_PIDS="$(ffmpeg_pids_for_workdir)"
echo "  before kill: status=$(job_field status "$JOB_E") token=${OLD_TOKEN:0:8} attempt=$OLD_ATTEMPT"
kill -9 "$APP_PID"
wait "$APP_PID" 2>/dev/null || true
APP_PID=""
check "DB retains interrupted lease after hard kill" '[[ "$(job_field execution_token "$JOB_E")" == "$OLD_TOKEN" && "$(job_field status "$JOB_E")" == processing ]]'
ORPHANS="$(ffmpeg_pids_for_workdir)"
if [[ -n "$ORPHANS" ]]; then
  echo "  orphaned FFmpeg after JVM kill: $ORPHANS (documented limitation; killing fixture now)"
  for pid in $ORPHANS; do kill -9 "$pid" 2>/dev/null || true; done
fi
start_app
REQUEUED=0
for _ in $(seq 1 100); do
  status="$(job_field status "$JOB_E")"
  [[ "$status" == queued || "$status" == processing ]] && { REQUEUED=1; break; }
  sleep 0.1
done
check "recovery requeues the interrupted lease" '[[ "$REQUEUED" == 1 ]]'
NEW_TOKEN="$(job_field execution_token "$JOB_E")"
NEW_ATTEMPT="$(job_field attempt "$JOB_E")"
echo "  after recovery: status=$(job_field status "$JOB_E") token=${NEW_TOKEN:0:8} attempt=$NEW_ATTEMPT"
check "recovery invalidates the old token" '[[ "$NEW_TOKEN" != "$OLD_TOKEN" ]]'
TERMINAL_E="$(wait_terminal "$JOB_E" 240)"
echo "  recovered job terminal status=$TERMINAL_E"
check "recovered job completes with a fresh lease" '[[ "$TERMINAL_E" == completed && -n "$(job_field output_path "$JOB_E")" ]]'
check "attempt counter advanced on the new claim" '[[ "$NEW_ATTEMPT" -gt "$OLD_ATTEMPT" ]]'

say "Summary"
check "no unexpected failed jobs in runtime evidence" '[[ "$(failed_job_count)" == 0 ]]'
echo "  packaged JAR: $JAR"
echo "  evidence log: $EVIDENCE"
echo "ALL PACKAGED-JAR ASYNC RUNTIME CHECKS PASSED"
