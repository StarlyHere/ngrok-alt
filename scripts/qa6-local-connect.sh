#!/usr/bin/env bash
# Open a Sprinklr LocalConnect tunnel from QA6 to a service on this laptop.
#
# Usage: scripts/qa6-local-connect.sh <local-port> [session-id] [microservice]
# Example: scripts/qa6-local-connect.sh 8080
#
# SSH and sudo may prompt interactively. Passwords are never stored here.

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

readonly ACCESS_HOST="${QA6_ACCESS_HOST:-lakshayjindal@access.qa6.spr-ops.com}"
readonly CLUSTER_HOST="${QA6_CLUSTER_HOST:-lakshayjindal@10.56.32.106}"
readonly KUBE_USER="${QA6_KUBE_USER:-sprapps}"
readonly KUBE_NAMESPACE="${QA6_KUBE_NAMESPACE:-spr-apps}"
readonly COORDINATOR_SERVICE="${QA6_COORDINATOR_SERVICE:-spr-local-connect-tier1-coordinator}"
readonly RELAY_SERVICE="${QA6_RELAY_SERVICE:-spr-local-connect-tier1-relay}"
readonly LOCAL_CONTROL_PORT="${QA6_LOCAL_CONTROL_PORT:-8090}"
# QA6 coordinator advertises ssh://localhost:2222 in its assignment response, so
# this laptop-side port must remain 2222 unless that server setting also changes.
readonly LOCAL_SSH_PORT="2222"
readonly INSPECTOR_PORT="${QA6_INSPECTOR_PORT:-4040}"
readonly QA6_USER="${QA6_USER:-lakshayjindal}"
readonly COOKIE_PATH="${QA6_COOKIE_PATH:-/ui}"

# Avoid collisions between developers on the shared QA6 host. The laptop ports
# remain the stable 8090/2222 expected by the client and control response.
readonly REMOTE_PORT_BASE="${QA6_REMOTE_PORT_BASE:-$((20000 + ($$ % 10000)))}"
readonly REMOTE_CONTROL_PORT="$REMOTE_PORT_BASE"
readonly REMOTE_SSH_PORT="$((REMOTE_PORT_BASE + 1))"

CLIENT_WORKER_PID=""

usage() {
  cat <<'EOF'
Usage: scripts/qa6-local-connect.sh <local-port> [session-id] [microservice]

Starts the QA6 access SSH connection, Kubernetes port-forwards, and the local
Sprinklr LocalConnect client. Start your local application before this script.

Arguments:
  local-port   Port on 127.0.0.1 where the local application is listening
  session-id   Optional fixed session ID; otherwise a random ID is generated
  microservice Optional destination microservice name. When supplied, the
               remoteDebugConf cookie becomes <session-id>:<microservice> and
               normal QA6 WebUI requests selectively call this local service.

Useful environment overrides:
  QA6_ACCESS_HOST          SSH jump host
  QA6_CLUSTER_HOST         QA6 Kubernetes host
  QA6_KUBE_USER            Remote kubectl OS user (default: sprapps)
  QA6_USER                 Owner used for tunnel login
  QA6_FORCE_LOGIN=1        Issue and store a fresh QA6 token
  QA6_LOCAL_CONTROL_PORT   Laptop coordinator port (default: 8090)
  QA6_REMOTE_PORT_BASE     First of two ports used on the shared QA6 host
  QA6_COOKIE_PATH          Debug-cookie path (default: /ui; use / for full-host routing)
  QA6_MICROSERVICE         Destination microservice to intercept
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

port_is_open() {
  nc -z 127.0.0.1 "$1" >/dev/null 2>&1
}

wait_for_control() {
  local attempt
  for attempt in $(seq 1 90); do
    local status
    status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
      --connect-timeout 2 --max-time 2 \
      "http://127.0.0.1:${LOCAL_CONTROL_PORT}/actuator/health" || true)"
    if [[ "$status" == "200" ]]; then
      printf 'Ready: QA6 coordinator on localhost:%s\n' "$LOCAL_CONTROL_PORT"
      return 0
    fi
    sleep 1
  done
  printf 'ERROR: timed out waiting for QA6 coordinator on localhost:%s\n' \
    "$LOCAL_CONTROL_PORT" >&2
  return 1
}

wait_for_tunnel_ssh() {
  local attempt
  for attempt in $(seq 1 90); do
    local banner
    banner="$(nc -w 2 127.0.0.1 "$LOCAL_SSH_PORT" </dev/null 2>/dev/null \
      | head -1 | tr -d '\r' || true)"
    if [[ "$banner" == SSH-* ]]; then
      printf 'Ready: QA6 relay SSH on localhost:%s\n' "$LOCAL_SSH_PORT"
      return 0
    fi
    sleep 1
  done
  printf 'ERROR: timed out waiting for QA6 relay SSH on localhost:%s\n' \
    "$LOCAL_SSH_PORT" >&2
  return 1
}

select_java_21() {
  JAVA_BIN="${JAVA_BIN:-java}"
  local major
  major=""
  if command -v "$JAVA_BIN" >/dev/null 2>&1; then
    major="$($JAVA_BIN -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
  fi

  if [[ ! "$major" =~ ^[0-9]+$ || "$major" -lt 21 ]]; then
    if [[ -x /usr/libexec/java_home ]]; then
      local java_home_21
      java_home_21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
      if [[ -n "$java_home_21" && -x "$java_home_21/bin/java" ]]; then
        export JAVA_HOME="$java_home_21"
        export PATH="$JAVA_HOME/bin:$PATH"
        JAVA_BIN="$JAVA_HOME/bin/java"
        major="$($JAVA_BIN -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
      fi
    fi
  fi

  [[ "$major" =~ ^[0-9]+$ && "$major" -ge 21 ]] \
    || die "Java 21 or newer is required; current Java reports version ${major:-unknown}"
  readonly JAVA_BIN
}

saved_token() {
  local config="$HOME/.tunnel/config"
  [[ -r "$config" ]] || return 1
  awk -F= '$1 == "token" {sub(/^[^=]*=/, ""); print; exit}' "$config"
}

token_is_valid() {
  local token
  token="$(saved_token || true)"
  [[ -n "$token" ]] || return 1

  # Authentication happens before body validation. An empty assignment returns
  # 400 for a valid token and 401 for an invalid or expired token.
  local status
  status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --connect-timeout 3 \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/octet-stream' \
    --data-binary '' \
    "http://127.0.0.1:${LOCAL_CONTROL_PORT}/sessions" || true)"
  [[ "$status" == "400" ]]
}

login_if_needed() {
  if [[ "${QA6_FORCE_LOGIN:-0}" != "1" ]] && token_is_valid; then
    printf 'Using the existing valid QA6 tunnel token from ~/.tunnel/config\n'
    return 0
  fi

  printf 'Requesting a fresh QA6 tunnel token for %s...\n' "$QA6_USER"
  "$JAVA_BIN" -jar "$REPO_ROOT/client/build/libs/client-0.1.0-SNAPSHOT.jar" \
    login \
    "--user=$QA6_USER" \
    "--coordinator=http://127.0.0.1:${LOCAL_CONTROL_PORT}"

  token_is_valid || die "QA6 login did not produce a valid token"
}

cleanup() {
  if [[ -n "$CLIENT_WORKER_PID" ]] && kill -0 "$CLIENT_WORKER_PID" 2>/dev/null; then
    kill "$CLIENT_WORKER_PID" 2>/dev/null || true
    wait "$CLIENT_WORKER_PID" 2>/dev/null || true
  fi
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

[[ $# -ge 1 && $# -le 3 ]] || { usage >&2; exit 2; }

readonly LOCAL_APP_PORT="$1"
[[ "$LOCAL_APP_PORT" =~ ^[0-9]+$ ]] \
  && ((LOCAL_APP_PORT >= 1 && LOCAL_APP_PORT <= 65535)) \
  || die "invalid local application port: $LOCAL_APP_PORT"

require_command ssh
require_command curl
require_command nc
require_command openssl
require_command awk
require_command seq
select_java_21

port_is_open "$LOCAL_APP_PORT" \
  || die "nothing is listening on 127.0.0.1:${LOCAL_APP_PORT}; start the local application first"
port_is_open "$LOCAL_CONTROL_PORT" \
  && die "localhost:${LOCAL_CONTROL_PORT} is already in use"
port_is_open "$LOCAL_SSH_PORT" \
  && die "localhost:${LOCAL_SSH_PORT} is already in use"

readonly SESSION_ID="${2:-$(openssl rand -hex 16)}"
readonly MICROSERVICE="${3:-${QA6_MICROSERVICE:-}}"
readonly REMOTE_DEBUG_CONF="${SESSION_ID}${MICROSERVICE:+:${MICROSERVICE}}"
[[ "$SESSION_ID" =~ ^[A-Za-z0-9._-]+$ ]] || die "session ID contains unsupported characters"
[[ -z "$MICROSERVICE" || "$MICROSERVICE" =~ ^[A-Za-z0-9._-]+$ ]] \
  || die "microservice contains unsupported characters"
[[ "$COOKIE_PATH" == /* ]] || die "QA6_COOKIE_PATH must start with /"

printf 'Building the Sprinklr LocalConnect client (Gradle reuses unchanged outputs)...\n'
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" :client:bootJar --no-daemon

if [[ -n "$MICROSERVICE" ]]; then
  cat <<EOF

QA6 tunnel session: $SESSION_ID

Set this cookie on the QA6 WebUI hostname:
  remoteDebugConf=$REMOTE_DEBUG_CONF  Path=$COOKIE_PATH

Do not set sprLocalConnect for this mode. The QA6 WebUI must handle the HTTP
request normally; only its calls to $MICROSERVICE are sent through LocalConnect.

Traffic selected by this cookie will be forwarded to:
  127.0.0.1:$LOCAL_APP_PORT

Opening QA6 access. SSH and sudo may prompt for passwords.
Press Ctrl+C to close the tunnel.

EOF
else
  cat <<EOF

QA6 tunnel session: $SESSION_ID

Set these cookies on the QA6 WebUI hostname:
  sprLocalConnect=always                  Path=$COOKIE_PATH
  remoteDebugConf=$REMOTE_DEBUG_CONF  Path=$COOKIE_PATH

Path=$COOKIE_PATH keeps frontend routes such as /care on normal QA6 while
routing matching backend requests to this tunnel.

Traffic selected by these cookies will be forwarded to:
  http://127.0.0.1:$LOCAL_APP_PORT

Opening QA6 access. SSH and sudo may prompt for passwords.
Press Ctrl+C to close the tunnel.

EOF
fi

trap cleanup EXIT
trap 'exit 130' INT TERM

# Wait in the background until the foreground SSH connection exposes both local
# ports, then authenticate and run the actual application tunnel client.
(
  JAVA_CHILD_PID=""
  stop_java() {
    if [[ -n "$JAVA_CHILD_PID" ]] && kill -0 "$JAVA_CHILD_PID" 2>/dev/null; then
      kill "$JAVA_CHILD_PID" 2>/dev/null || true
      wait "$JAVA_CHILD_PID" 2>/dev/null || true
    fi
  }
  trap stop_java EXIT INT TERM

  wait_for_control || exit 1
  wait_for_tunnel_ssh || exit 1
  login_if_needed

  printf '\nStarting Sprinklr LocalConnect tunnel client...\n'
  "$JAVA_BIN" -jar "$REPO_ROOT/client/build/libs/client-0.1.0-SNAPSHOT.jar" \
    http "$LOCAL_APP_PORT" \
    --transport=ssh \
    "--coordinator=http://127.0.0.1:${LOCAL_CONTROL_PORT}" \
    "--session=$SESSION_ID" \
    --paths=ALL \
    "--inspector=$INSPECTOR_PORT" &
  JAVA_CHILD_PID="$!"
  wait "$JAVA_CHILD_PID"
  JAVA_CHILD_PID=""
) &
CLIENT_WORKER_PID="$!"

# The remote commands remain attached to this foreground SSH session. Use the
# exact sudo/su flow available on the QA6 host.
read -r -d '' REMOTE_INNER <<EOF || true
set -Eeuo pipefail
echo "Sudo accepted; starting QA6 Kubernetes port-forwards..."
coordinator_pid=""
relay_pid=""
cleanup_remote() {
  if [[ -n "\$coordinator_pid" ]]; then kill "\$coordinator_pid" 2>/dev/null || true; fi
  if [[ -n "\$relay_pid" ]]; then kill "\$relay_pid" 2>/dev/null || true; fi
  wait 2>/dev/null || true
}
trap cleanup_remote EXIT HUP INT TERM

kubectl -n ${KUBE_NAMESPACE} port-forward \
  service/${COORDINATOR_SERVICE} ${REMOTE_CONTROL_PORT}:8090 \
  >/tmp/spr-local-connect-${QA6_USER}-${REMOTE_CONTROL_PORT}-coordinator.log 2>&1 &
coordinator_pid="\$!"

kubectl -n ${KUBE_NAMESPACE} port-forward \
  service/${RELAY_SERVICE} ${REMOTE_SSH_PORT}:2222 \
  >/tmp/spr-local-connect-${QA6_USER}-${REMOTE_SSH_PORT}-relay.log 2>&1 &
relay_pid="\$!"

while kill -0 "\$coordinator_pid" 2>/dev/null && kill -0 "\$relay_pid" 2>/dev/null; do
  sleep 2
done

echo "A QA6 Kubernetes port-forward stopped unexpectedly." >&2
echo "Coordinator log: /tmp/spr-local-connect-${QA6_USER}-${REMOTE_CONTROL_PORT}-coordinator.log" >&2
echo "Relay log:       /tmp/spr-local-connect-${QA6_USER}-${REMOTE_SSH_PORT}-relay.log" >&2
exit 1
EOF

printf -v REMOTE_INNER_QUOTED '%q' "$REMOTE_INNER"
readonly REMOTE_COMMAND="sudo su - $KUBE_USER -c $REMOTE_INNER_QUOTED"

ssh -tt \
  -o ExitOnForwardFailure=yes \
  -o LogLevel=QUIET \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -J "$ACCESS_HOST" \
  -L "${LOCAL_CONTROL_PORT}:127.0.0.1:${REMOTE_CONTROL_PORT}" \
  -L "${LOCAL_SSH_PORT}:127.0.0.1:${REMOTE_SSH_PORT}" \
  "$CLUSTER_HOST" \
  "$REMOTE_COMMAND"
