#!/usr/bin/env bash
set -Eeuo pipefail

java -cp 'build/classes:lib/*' org.mark.audiocpp.hub.AudioHubServer &
hub_pid=$!

shutdown() {
  kill -TERM "$hub_pid" 2>/dev/null || true
  wait "$hub_pid" 2>/dev/null || true
}
trap shutdown TERM INT

if ! /usr/local/bin/start-instance.sh; then
  shutdown
  exit 1
fi

wait "$hub_pid"
