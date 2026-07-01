#!/usr/bin/env bash
# common.sh — shared helpers sourced by every demo script.
# Do NOT run this file directly.

set -euo pipefail

# ── Namespace / image constants ────────────────────────────────────────────────
NS="tunnel"
CURL_IMAGE="curlimages/curl:8.10.1"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── Output helpers ─────────────────────────────────────────────────────────────
# Colours work on any terminal that honours ANSI codes; fall back gracefully.
_c_reset='\033[0m'
_c_bold='\033[1m'
_c_green='\033[0;32m'
_c_yellow='\033[0;33m'
_c_cyan='\033[0;36m'
_c_red='\033[0;31m'
_c_dim='\033[2m'

step()  { echo -e "\n${_c_bold}${_c_cyan}▶  $*${_c_reset}"; }
ok()    { echo -e "   ${_c_green}✓  $*${_c_reset}"; }
info()  { echo -e "   ${_c_dim}$*${_c_reset}"; }
warn()  { echo -e "   ${_c_yellow}⚠  $*${_c_reset}"; }
fail()  { echo -e "\n   ${_c_red}✗  $*${_c_reset}\n" >&2; exit 1; }
header(){ echo -e "\n${_c_bold}${_c_yellow}━━  $*  ━━${_c_reset}"; }
divider(){ echo -e "${_c_dim}────────────────────────────────────────────${_c_reset}"; }

# ── kubectl wrapper ────────────────────────────────────────────────────────────
# Always use minikube's bundled kubectl; the system kubectl may be stale/broken.
kc() { minikube kubectl -- "$@"; }

# ── Redis exec helper ──────────────────────────────────────────────────────────
# Usage: redis_exec <redis-cli args...>
redis_exec() { kc -n "$NS" exec deploy/redis -- redis-cli "$@" 2>/dev/null | tr -d '\r'; }

# ── One-shot curl pod ──────────────────────────────────────────────────────────
# Spawns a temporary curl pod inside the cluster, runs the request, then
# the pod self-deletes (--rm).  The caller sees only the response body/code.
# Usage: curl_pod <unique-pod-name> <curl args...>
curl_pod() {
  local pod_name="$1"; shift
  kc -n "$NS" run "$pod_name" \
    --image="$CURL_IMAGE" \
    --restart=Never \
    --rm -i --quiet \
    -- curl --silent "$@" 2>/dev/null
}

# ── Session discovery ──────────────────────────────────────────────────────────
# Returns the first active session UUID (without the "session:" prefix).
active_session() {
  redis_exec keys 'session:*' \
    | head -1 \
    | sed 's/session://'
}

# ── Wait helpers ───────────────────────────────────────────────────────────────
# Block until the tunnel namespace has at least N session keys in Redis.
wait_for_session_count() {
  local want="$1"
  local label="${2:-sessions}"
  info "Waiting for $want $label..."
  while true; do
    local got
    got=$(redis_exec keys 'session:*' 2>/dev/null | grep -c 'session:' || true)
    [ "$got" -ge "$want" ] && break
    sleep 2
  done
  ok "$want $label ready"
}

# Block until N pods have both host and port registered in the Pod Registry.
wait_for_pod_registration() {
  local want="$1"
  info "Waiting for $want tunnel pods to register in Redis..."
  while true; do
    local got
    got=$(kc -n "$NS" exec deploy/redis -- sh -c \
      'n=0; for k in $(redis-cli keys "pod:*"); do
         [ -n "$(redis-cli hget $k host)" ] && n=$((n+1))
       done; echo $n' 2>/dev/null | tr -d '\r')
    [ "${got:-0}" -ge "$want" ] && break
    sleep 3
  done
  ok "$want tunnel pods registered"
}

# Block until a specific deployment has all replicas ready.
wait_for_deploy() {
  local deploy="$1"
  local timeout="${2:-180s}"
  kc -n "$NS" wait --for=condition=ready pod -l "app=$deploy" \
    --timeout="$timeout" >/dev/null
  ok "$deploy is ready"
}

# ── Namespace guard ────────────────────────────────────────────────────────────
# Call at the top of any script that assumes the cluster is already deployed.
require_namespace() {
  if ! kc get ns "$NS" >/dev/null 2>&1; then
    fail "Namespace '$NS' does not exist. Run  demo/deploy.sh  first."
  fi
}

# ── Pod ID for a session ───────────────────────────────────────────────────────
pod_for_session() {
  local sess="$1"
  redis_exec hget "session:$sess" podId
}
