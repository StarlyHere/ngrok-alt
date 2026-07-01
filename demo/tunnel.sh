#!/usr/bin/env bash
# tunnel.sh — Demonstrate full tunnel path: Gateway → Router → Redis → Pod → Client → localhost
# Wraps: test-with-pods.md §5c (Decision Layer tunnel routing), §8c (e2e forwarding)
#
# Automatically discovers an active session from Redis and uses it.
#
# Usage:  demo/tunnel.sh [session-uuid]

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_namespace

# Guard: refuse to run if no active sessions exist (laptop client not connected).
sess_count=$(redis_exec keys 'session:*' | grep -c 'session:' || true)
if [ "${sess_count:-0}" -lt 1 ]; then
  fail "No active sessions found. Start the tunnel client on your laptop first."
fi

header "Tunnel Request — Full End-to-End Path"

# ── Discover or accept a session ───────────────────────────────────────────────
if [ "${1:-}" != "" ]; then
  SESS="$1"
  info "Using provided session: ${SESS:0:12}..."
else
  step "Discovering active session from Redis"
  SESS=$(active_session)
  if [ -z "$SESS" ]; then
    fail "No active sessions found in Redis. Is the system deployed? Run demo/deploy.sh"
  fi
  ok "Session found: ${SESS:0:12}..."
fi

# ── Show what the session maps to ─────────────────────────────────────────────
step "Session details from Redis"
POD_ID=$(pod_for_session "$SESS")
POD_HOST=$(redis_exec hget "pod:$POD_ID" host)
POD_PORT=$(redis_exec hget "pod:$POD_ID" port)
OWNER=$(redis_exec hget "session:$SESS" ownerId)

info "Session  : ${SESS:0:12}..."
info "Owner    : $OWNER"
info "Pod      : ${POD_ID##*-}...  ($POD_HOST:$POD_PORT)"

# ── Send tunnel request via Gateway ───────────────────────────────────────────
step "Sending tunnel request through QA6 Gateway"
info "Gateway Decision Layer detects X-Tunnel-Session → routes to Router"
divider

response=$(curl_pod "demo-tunnel-$$" \
  -H "X-Tunnel-Session: $SESS" \
  -H "X-Tunnel-Trace: demo-trace-$$" \
  "http://qa6-gateway:9000/hello?name=TunnelDemo")

echo ""
echo "   Response:"
echo "   $response"
echo ""
divider

# ── Validate ───────────────────────────────────────────────────────────────────
if echo "$response" | grep -q '"error"'; then
  fail "Tunnel not connected — start the tunnel client on your laptop first, then re-run."
elif [ -n "$response" ]; then
  ok "Response received from tunnel — came from localhost:3000 on your laptop"
  echo ""
  info "Full path taken:"
  info "  QA6 Gateway (:9000)"
  info "  → Decision Layer  (detected X-Tunnel-Session)"
  info "  → Router"
  info "  → Redis  (session:$SESS → pod:${POD_ID##*-}...)"
  info "  → Tunnel Pod  ($POD_HOST:$POD_PORT)"
  info "  → SSH tunnel"
  info "  → Tunnel Client  (laptop)"
  info "  → localhost:3000  (your Spring Boot app)"
  echo ""
  info "The response body above is from YOUR local app — that is the proof."
elif echo "$response" | grep -q '"service":"qa6-service"'; then
  fail "Wrong routing: response came from qa6-service. Decision Layer is not recognising the session header."
else
  fail "Empty response — is your local Spring Boot app running on port 3000?"
fi
