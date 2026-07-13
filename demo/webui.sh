#!/usr/bin/env bash
# webui.sh — Demonstrate Enhancement 2: WebUI ingress lifecycle + selective routing.
#
# Proves three things with three curl commands:
#   1. No session cookie  → gateway placeholder (normal QA6 response)
#   2. Session + matching path (/ui/graphql/**)  → tunneled to local webui-sim
#   3. Session + non-matching path (/hello)  → gateway placeholder (path not in allowlist)
#
# Prerequisites:
#   - demo/deploy.sh already run (cluster up, nginx ingress enabled)
#   - webui-sim running locally:  ./gradlew :webui-sim:bootRun
#   - Tunnel client started with:
#       java -jar client/build/libs/client-0.1.0-SNAPSHOT.jar \
#         http 4000 --transport=ssh \
#         --coordinator=http://localhost:30092 \
#         --token=demo-alice-7f3c9a2b1e6d4058 \
#         '--paths=/ui/graphql/**' --create-ingress
#
# Usage:  demo/webui.sh [session-uuid]

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_namespace
ensure_portforwards

# Guard: refuse to run if no active sessions exist.
sess_count=$(redis_exec keys 'session:*' | grep -c 'session:' || true)
if [ "${sess_count:-0}" -lt 1 ]; then
  fail "No active sessions found. Start the tunnel client on your laptop first."
fi

header "WebUI Ingress Demo — Enhancement 2"

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

# ── Show ingress status ────────────────────────────────────────────────────────
step "Checking ingress created by coordinator"
kc -n "$NS" get ingress 2>/dev/null || true

# ── Test 1: No session → placeholder ──────────────────────────────────────────
step "Test 1 — No session cookie → expect gateway placeholder"
info "curl /ui/graphql/generateTTSPreview  (no cookie)"
divider

response1=$(curl_pod "webui-nosess-$$" \
  "http://qa6-gateway:9000/ui/graphql/generateTTSPreview")

echo ""
echo "   Response: $response1"
echo ""
divider

if echo "$response1" | grep -q '"route":"normal"'; then
  ok "PASS — gateway returned normal placeholder (no session = no tunnel)"
else
  warn "Unexpected response: $response1"
fi

# ── Test 2: Session + matching path → tunneled to webui-sim ───────────────────
step "Test 2 — Session cookie + matching path → expect webui-sim response"
info "curl /ui/graphql/generateTTSPreview  (cookie: remoteDebugConf=\$SESS)"
divider

response2=$(curl_pod "webui-match-$$" \
  -H "Cookie: remoteDebugConf=$SESS" \
  "http://qa6-gateway:9000/ui/graphql/generateTTSPreview")

echo ""
echo "   Response: $response2"
echo ""
divider

if echo "$response2" | grep -q '"service":"webui-sim"'; then
  ok "PASS — response came from webui-sim on your laptop (path matched allowlist)"
elif echo "$response2" | grep -q '"route":"normal"'; then
  fail "FAIL — got placeholder. Is webui-sim running on port 4000? Does --paths=/ui/graphql/** match?"
else
  warn "Unexpected response: $response2"
fi

# ── Test 3: Session + non-matching path → placeholder ─────────────────────────
step "Test 3 — Session cookie + non-matching path → expect gateway placeholder"
info "curl /hello  (cookie: remoteDebugConf=\$SESS, path not in allowlist)"
divider

response3=$(curl_pod "webui-nomatch-$$" \
  -H "Cookie: remoteDebugConf=$SESS" \
  "http://qa6-gateway:9000/hello")

echo ""
echo "   Response: $response3"
echo ""
divider

if echo "$response3" | grep -q '"route":"normal"'; then
  ok "PASS — /hello not tunneled (path not in /ui/graphql/** allowlist)"
else
  warn "Unexpected response: $response3"
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
header "Results"
info "  Test 1 (no session → placeholder):       $response1"
info "  Test 2 (session + match → webui-sim):    $response2"
info "  Test 3 (session + no match → placeholder): $response3"
echo ""
ok "Enhancement 2 demo complete."
info "To test ingress deletion: Ctrl+C the tunnel client → kubectl get ingress -n tunnel → empty"
info "To test reconciler cleanup: kill -9 the client → wait 60s → kubectl get ingress -n tunnel → empty"
