#!/usr/bin/env bash
# Thin wrapper around scripts/qa6-local-connect.sh for non-interactive use by
# an agent: backgrounds the tunnel, waits for it to come up, and prints the
# session ID and cookie string the caller needs to drive traffic through it.
#
# Usage:
#   run_live_qa.sh [--json] <local-port> [session-id] [microservice]
#   run_live_qa.sh --stop
#
# --json switches the ready/error output to a single machine-readable JSON
# object on stdout instead of the human-readable text. It is consumed by this
# script only; it is never forwarded to qa6-local-connect.sh.

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
readonly INSPECTOR_PORT="${QA6_INSPECTOR_PORT:-4040}"
readonly PID_FILE="/tmp/spr-live-qa.pid"
# Default per-session Ingress base domain (control/src/main/resources/application.yml,
# ControlProperties.java) — same hostname the QA6 WebUI is reached at.
readonly WEBUI_HOSTNAME="${QA6_WEBUI_HOSTNAME:-space-qa6.sprinklr.com}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

stop() {
  [[ -r "$PID_FILE" ]] || die "not running: $PID_FILE not found"
  local pid
  pid="$(<"$PID_FILE")"
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$PID_FILE"
    die "process $pid is not running (stale pid file removed)"
  fi

  printf 'Stopping Sprinklr LocalConnect tunnel (pid %s)...\n' "$pid"
  kill -INT "$pid" 2>/dev/null || true

  local attempt
  for attempt in $(seq 1 30); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done

  if kill -0 "$pid" 2>/dev/null; then
    printf 'WARNING: pid %s is still running after 30s\n' "$pid" >&2
  else
    printf 'Stopped.\n'
  fi
  rm -f "$PID_FILE"
}

# Emits {"status": "ERROR", "reason": "...", "logFile": ..., "pid": ...} on
# stdout when --json is set, then exits 1. Always writes JSON to stdout (not
# stderr) so a caller piping stdout into a JSON parser gets valid JSON either
# way; success/failure is signaled by exit code, not by which stream it's on.
json_error() {
  local reason="$1"
  if [[ "$JSON" == "1" ]]; then
    python3 -c '
import json, sys
print(json.dumps({
    "status": "ERROR",
    "reason": sys.argv[1],
    "logFile": sys.argv[2] or None,
    "pid": int(sys.argv[3]) if sys.argv[3] else None,
}))
' "$reason" "${LOG_FILE:-}" "${BG_PID:-}"
  else
    printf 'ERROR: %s\n' "$reason" >&2
    if [[ -n "${LOG_FILE:-}" ]]; then
      printf '---- tail of %s ----\n' "$LOG_FILE" >&2
      tail -n 20 "$LOG_FILE" >&2 2>/dev/null || true
    fi
  fi
  exit 1
}

if [[ "${1:-}" == "--stop" ]]; then
  stop
  exit 0
fi

JSON="0"
ARGS=()
for arg in "$@"; do
  if [[ "$arg" == "--json" ]]; then
    JSON="1"
  else
    ARGS+=("$arg")
  fi
done
set -- "${ARGS[@]}"

[[ $# -ge 1 && $# -le 3 ]] || die "usage: run_live_qa.sh [--json] <local-port> [session-id] [microservice]  |  run_live_qa.sh --stop"

(
  exec > >(exec tee "/tmp/spr-live-qa-$BASHPID.log") 2>&1
  exec "$REPO_ROOT/scripts/qa6-local-connect.sh" "$@"
) &
readonly BG_PID="$!"
readonly LOG_FILE="/tmp/spr-live-qa-${BG_PID}.log"

echo "$BG_PID" > "$PID_FILE"
if [[ "$JSON" != "1" ]]; then
  printf 'Started Sprinklr LocalConnect tunnel in the background (pid %s)\n' "$BG_PID"
  printf 'Log: %s\n' "$LOG_FILE"
fi

for _ in $(seq 1 10); do
  [[ -e "$LOG_FILE" ]] && break
  sleep 0.5
done

ready=""
for attempt in $(seq 1 120); do
  if ! kill -0 "$BG_PID" 2>/dev/null; then
    rm -f "$PID_FILE"
    json_error "tunnel process exited before the inspector came up"
  fi

  status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --connect-timeout 2 --max-time 2 \
    "http://127.0.0.1:${INSPECTOR_PORT}/api/requests" || true)"
  if [[ "$status" == "200" ]]; then
    ready="1"
    break
  fi
  sleep 1
done

if [[ -z "$ready" ]]; then
  json_error "timed out waiting for the inspector on localhost:${INSPECTOR_PORT}"
fi

session_line="$(grep -m1 'QA6 tunnel session:' "$LOG_FILE" || true)"
session_id="${session_line#*QA6 tunnel session: }"

if [[ "$JSON" == "1" ]]; then
  awk '/Set th/{flag=1; next} flag{print} flag && /^$/{exit}' "$LOG_FILE" \
    | python3 -c '
import json, sys

session_id = sys.argv[1]
inspector_port = int(sys.argv[2])
pid = int(sys.argv[3])
log_file = sys.argv[4]
webui_hostname = sys.argv[5]

cookies = []
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    parts = line.split()
    name, _, value = parts[0].partition("=")
    path = None
    for token in parts[1:]:
        if token.startswith("Path="):
            path = token[len("Path="):]
            break
    cookies.append({"name": name, "value": value, "path": path})

print(json.dumps({
    "status": "READY",
    "sessionId": session_id,
    "cookies": cookies,
    "webuiHostname": webui_hostname,
    "inspectorPort": inspector_port,
    "pid": pid,
    "logFile": log_file,
}))
' "$session_id" "$INSPECTOR_PORT" "$BG_PID" "$LOG_FILE" "$WEBUI_HOSTNAME"
else
  printf '\nTunnel is up.\n'
  printf 'Session ID: %s\n\n' "$session_id"
  awk '/Set th/{flag=1} flag{print} flag && /^$/{exit}' "$LOG_FILE"
fi
