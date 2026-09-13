#!/usr/bin/env bash
set -Eeuo pipefail

HUB_URL="${HUB_URL:-http://127.0.0.1:18080}"
MODEL_ID="${AUDIOCPP_MODEL_ID:-breeze-tts}"
SERVICE_NAME="${AUDIOCPP_SERVICE_NAME:-breeze}"
WEIGHTS="${AUDIOCPP_WEIGHTS:-/audio.cpp/breeze/breeze-tts-2-q8_0.gguf}"
BACKEND="${AUDIOCPP_BACKEND:-vulkan}"
DEVICE="${AUDIOCPP_DEVICE:-0}"
THREADS="${AUDIOCPP_THREADS:-10}"
WARMUP_WAV="/tmp/audio-cpp-hub-breeze-warmup.wav"
WAIT_SECONDS=120

log() { printf '[start-breeze] %s\n' "$*" >&2; }
fatal() { log "ERROR: $*"; exit 1; }

for ((i = 0; i < 60; i++)); do
  if curl -fsS --max-time 2 "$HUB_URL/api/models" >/dev/null; then break; fi
  sleep 1
done
curl -fsS --max-time 2 "$HUB_URL/api/models" >/dev/null \
  || fatal "hub did not become reachable at $HUB_URL within 60 seconds"

[[ -x /audio.cpp/bin/audiocpp_server ]] || fatal "mounted audio.cpp executable is missing or not executable"
[[ -f "$WEIGHTS" ]] || fatal "mounted Breeze weights are missing: $WEIGHTS"

instance_line() {
  curl -fsS --max-time 5 "$HUB_URL/api/instances" \
    | python3 -c 'import json,sys
for x in json.load(sys.stdin):
    if x.get("instanceName") == "breeze" or x.get("name") == "breeze":
        print(x.get("id", ""), x.get("status", ""), x.get("port", ""))
        break'
}

line="$(instance_line || true)"
if [[ -z "$line" ]]; then
  log "No existing Breeze instance; starting $BACKEND child"
  curl -fsS --max-time 15 -X POST "$HUB_URL/api/instances" \
    -H 'Content-Type: application/json' \
    --data-raw "{\"modelId\":\"$MODEL_ID\",\"name\":\"$SERVICE_NAME\",\"weightsPath\":\"$WEIGHTS\",\"backend\":\"$BACKEND\",\"device\":$DEVICE,\"threads\":$THREADS}" \
    >/tmp/audio-cpp-hub-breeze-start.json \
    || fatal "hub rejected the Breeze start request"
  line="$(instance_line || true)"
fi

[[ -n "$line" ]] || fatal "Breeze instance was not visible after the start request"
read -r instance_id status child_port <<<"$line"
log "Breeze instance $instance_id is $status on child port $child_port"

for ((i = 0; i < WAIT_SECONDS; i++)); do
  line="$(instance_line || true)"
  [[ -n "$line" ]] || fatal "Breeze instance disappeared while waiting for READY"
  read -r instance_id status child_port <<<"$line"
  case "$status" in
    READY) break ;;
    STARTING) sleep 1 ;;
    *) fatal "Breeze instance entered unexpected status: $status" ;;
  esac
done
[[ "$status" == READY ]] || fatal "Breeze did not become READY within ${WAIT_SECONDS}s"

log "Warming Breeze; READY alone does not prove VRAM loading"
rm -f "$WARMUP_WAV"
curl -fsS --max-time 180 -X POST "$HUB_URL/v1/audio/speech" \
  -H 'Content-Type: application/json' \
  --data-raw '{"model":"breeze","input":"This is a short startup warm-up.","instructions":"Speak clearly and naturally.","response_format":"wav"}' \
  -o "$WARMUP_WAV" \
  || fatal "Breeze warm-up request failed"

[[ -s "$WARMUP_WAV" ]] || fatal "warm-up output is empty"
[[ "$(dd if="$WARMUP_WAV" bs=1 count=4 2>/dev/null)" == RIFF ]] \
  || fatal "warm-up output is not a RIFF/WAV file"
log "Warm-up succeeded: $WARMUP_WAV ($(stat -c '%s' "$WARMUP_WAV") bytes); child port $child_port"
