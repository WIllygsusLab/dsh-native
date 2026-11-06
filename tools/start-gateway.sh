#!/usr/bin/env bash
# Start the no-restart LAN/Tailscale bridge to the loopback DSH host.
# Idempotent: refuses to start a second listener on the same port.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
HOST="${1:-0.0.0.0}"
PORT="${2:-3091}"
TARGET_PORT="${3:-3080}"
LOG="${DSH_HOME:-$HOME/.dsh}/mobile-gateway.log"

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "gateway already listening on $PORT"
  exit 0
fi

mkdir -p "$(dirname "$LOG")"
nohup node "$ROOT/mobile-gateway.mjs" "$HOST" "$PORT" 127.0.0.1 "$TARGET_PORT" >>"$LOG" 2>&1 &
echo $! > "${DSH_HOME:-$HOME/.dsh}/mobile-gateway.pid"
sleep 1
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "gateway listening on $HOST:$PORT -> 127.0.0.1:$TARGET_PORT (pid $(cat "${DSH_HOME:-$HOME/.dsh}/mobile-gateway.pid"))"
else
  echo "gateway failed to start; see $LOG" >&2
  exit 1
fi
