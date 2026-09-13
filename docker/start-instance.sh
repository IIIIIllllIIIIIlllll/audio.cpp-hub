#!/usr/bin/env bash
# start-instance.sh — environment-variable-driven hub instance launcher.
# Replaces start-breeze-instance.sh with a generic version that works for
# any model supported by audio.cpp-hub (breeze-tts, pocket-tts, kokoro, …).
#
# Required env vars:
#   AUDIOCPP_WEIGHTS   — absolute path to the .gguf weight file (inside container)
#   AUDIOCPP_MODEL_ID  — hub model ID (e.g. breeze-tts, pocket-tts, kokoro-tts)
#
# Optional env vars:
#   AUDIOCPP_SERVICE_NAME — name used for /v1/audio/speech routing (default: MODEL_ID)
#   AUDIOCPP_BACKEND      — vulkan | cpu | cuda (default: vulkan)
#   AUDIOCPP_DEVICE       — device index passed to the engine   (default: 0)
#   AUDIOCPP_THREADS      — CPU thread count                    (default: 4)
#   HUB_URL               — hub base URL (default: http://127.0.0.1:18080)
#   WARMUP_TEXT           — sentence for warm-up inference      (default: short English)

set -Eeuo pipefail

HUB_URL="${HUB_URL:-http://127.0.0.1:18080}"
MODEL_ID="${AUDIOCPP_MODEL_ID:-breeze-tts}"
SERVICE_NAME="${AUDIOCPP_SERVICE_NAME:-${MODEL_ID}}"
WEIGHTS="${AUDIOCPP_WEIGHTS:-}"
BACKEND="${AUDIOCPP_BACKEND:-vulkan}"
DEVICE="${AUDIOCPP_DEVICE:-0}"
THREADS="${AUDIOCPP_THREADS:-4}"
WARMUP_TEXT="${WARMUP_TEXT:-This is a short startup warm-up.}"
WARMUP_WAV="/tmp/audio-cpp-hub-warmup.wav"
WAIT_SECONDS=180

log()   { printf '[start-instance] %s\n' "$*" >&2; }
fatal() { log "ERROR: $*"; exit 1; }

[[ -n "$WEIGHTS" ]]           || fatal "AUDIOCPP_WEIGHTS must be set"
[[ -x /audio.cpp/bin/audiocpp_server ]] || fatal "audiocpp_server not found / not executable at /audio.cpp/bin/audiocpp_server"
[[ -f "$WEIGHTS" ]]           || fatal "Weight file not found: $WEIGHTS"

log "Waiting for hub at $HUB_URL …"
for ((i = 0; i < 60; i++)); do
  if curl -fsS --max-time 2 "$HUB_URL/api/models" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS --max-time 2 "$HUB_URL/api/models" >/dev/null \
  || fatal "hub did not become reachable within 60 s"

# --- look up existing instance by service name ---
instance_line() {
  curl -fsS --max-time 5 "$HUB_URL/api/instances" \
    | python3 -c "
import json, sys
for x in json.load(sys.stdin):
    if x.get('instanceName') == '${SERVICE_NAME}' or x.get('name') == '${SERVICE_NAME}':
        print(x.get('id',''), x.get('status',''), x.get('port',''))
        break
"
}

line="$(instance_line || true)"
if [[ -z "$line" ]]; then
  log "No existing instance '$SERVICE_NAME'; starting model=$MODEL_ID backend=$BACKEND device=$DEVICE"
  curl -fsS --max-time 15 -X POST "$HUB_URL/api/instances" \
    -H 'Content-Type: application/json' \
    --data-raw "{\"modelId\":\"$MODEL_ID\",\"name\":\"$SERVICE_NAME\",\"weightsPath\":\"$WEIGHTS\",\"backend\":\"$BACKEND\",\"device\":$DEVICE,\"threads\":$THREADS}" \
    >/tmp/audio-cpp-hub-start.json \
    || fatal "hub rejected the instance start request"
  line="$(instance_line || true)"
fi

[[ -n "$line" ]] || fatal "Instance '$SERVICE_NAME' not visible after start request"
read -r instance_id status child_port <<< "$line"
log "Instance $instance_id is $status on child port $child_port"

for ((i = 0; i < WAIT_SECONDS; i++)); do
  line="$(instance_line || true)"
  [[ -n "$line" ]] || fatal "Instance disappeared while waiting for READY"
  read -r instance_id status child_port <<< "$line"
  case "$status" in
    READY)    break ;;
    STARTING) sleep 1 ;;
    *) fatal "Instance entered unexpected status: $status" ;;
  esac
done
[[ "$status" == READY ]] || fatal "Instance did not become READY within ${WAIT_SECONDS}s"

log "Warming up '$SERVICE_NAME' (READY does not prove model is loaded into VRAM)"
rm -f "$WARMUP_WAV"
WARMUP_PAYLOAD="{\"model\":\"$SERVICE_NAME\",\"input\":\"$WARMUP_TEXT\",\"response_format\":\"wav\""
if [[ -n "${AUDIOCPP_VOICE_REF:-}" ]]; then
  WARMUP_PAYLOAD="$WARMUP_PAYLOAD,\"voice_ref\":\"$AUDIOCPP_VOICE_REF\""
fi
WARMUP_PAYLOAD="$WARMUP_PAYLOAD}"

curl -fsS --max-time 300 -X POST "$HUB_URL/v1/audio/speech" \
  -H 'Content-Type: application/json' \
  --data-raw "$WARMUP_PAYLOAD" \
  -o "$WARMUP_WAV" \
  || fatal "Warm-up request failed"

[[ -s "$WARMUP_WAV" ]] || fatal "Warm-up output is empty"
[[ "$(dd if="$WARMUP_WAV" bs=1 count=4 2>/dev/null)" == RIFF ]] \
  || fatal "Warm-up output is not a valid WAV file"

wav_bytes="$(stat -c '%s' "$WARMUP_WAV")"
log "Warm-up OK: $WARMUP_WAV (${wav_bytes} bytes) — instance $instance_id port $child_port"
