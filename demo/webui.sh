#!/usr/bin/env bash
# webui.sh — Demonstrate Enhancement 2: WebUI ingress lifecycle + session-aware routing.
#
# Architecture under test:
#   Browser → Temporary Ingress ({subdomain}.tunnel.local → router)
#           → router reads remoteDebugConf cookie
#           → session found  → SSH tunnel → webui-sim (laptop :4000)
#           → no session     → webui-placeholder (in-cluster :3000)
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
    fail "No active sessions found in Redis. Run demo/deploy.sh first."
  fi
  ok "Session found: ${SESS:0:12}..."
fi

# ── Discover subdomain ─────────────────────────────────────────────────────────
step "Resolving subdomain for session"
SUBDOMAIN=$(redis_exec hget "session:$SESS" subdomain)
if [ -z "$SUBDOMAIN" ]; then
  fail "Could not find subdomain for session $SESS in Redis."
fi
ok "Subdomain: $SUBDOMAIN"
INGRESS_HOST="${SUBDOMAIN}.tunnel.local"

# ── Verify ingress ─────────────────────────────────────────────────────────────
step "Verifying ingress was created by coordinator"
divider
kc -n "$NS" get ingress 2>/dev/null || true
divider

step "Checking ingress routes to router (not injecting session header)"
kc -n "$NS" describe ingress "dev-ingress-${SUBDOMAIN}" 2>/dev/null | grep -E 'Backend|annotation|snippet' || true
divider

# ── DNS resolution via --resolve (no /etc/hosts or sudo needed) ───────────────
step "Resolving minikube IP for ingress DNS (no /etc/hosts modification needed)"
MINIKUBE_IP=$(minikube ip)
# With the Podman driver, minikube's node IP is not reachable from the Mac host.
# Nginx ingress is port-forwarded to localhost:8888 by ensure_portforwards.
# curl --resolve HOST:PORT:IP bypasses DNS — nginx sees the correct Host header.
INGRESS_PORT=8888
CURL_RESOLVE="--resolve ${INGRESS_HOST}:${INGRESS_PORT}:127.0.0.1"
ok "Nginx ingress reachable at localhost:$INGRESS_PORT (port-forwarded) — Host: $INGRESS_HOST"

# ── Test 1: No cookie → webui-placeholder ─────────────────────────────────────
step "Test 1 — No cookie → expect webui-placeholder response"
info "curl http://$INGRESS_HOST/ui/graphql/generateTTSPreview  (no cookie)"
divider

response1=$(curl --silent --max-time 5 $CURL_RESOLVE \
  "http://$INGRESS_HOST:$INGRESS_PORT/ui/graphql/generateTTSPreview" || true)

echo ""
echo "   Response: $response1"
echo ""
divider

if echo "$response1" | grep -q '"service":"webui-placeholder"'; then
  ok "PASS — no session cookie → routed to webui-placeholder (in-cluster fallback)"
else
  warn "Unexpected response: $response1"
  info "Is webui-placeholder running? Check: minikube kubectl -- get pods -n tunnel"
fi

# ── Test 2: Cookie present → webui-sim (tunneled) ─────────────────────────────
step "Test 2 — Cookie present → expect webui-sim response (tunneled to laptop)"
info "curl http://$INGRESS_HOST/ui/graphql/generateTTSPreview  (cookie: remoteDebugConf=\$SESS)"
divider

response2=$(curl --silent --max-time 5 $CURL_RESOLVE \
  -H "Cookie: remoteDebugConf=$SESS" \
  "http://$INGRESS_HOST:$INGRESS_PORT/ui/graphql/generateTTSPreview" || true)

echo ""
echo "   Response: $response2"
echo ""
divider

if echo "$response2" | grep -q '"service":"webui-sim"'; then
  ok "PASS — cookie present → router tunneled request to webui-sim on your laptop"
elif echo "$response2" | grep -q '"service":"webui-placeholder"'; then
  fail "FAIL — got placeholder. Is webui-sim running on port 4000? Is the tunnel client connected?"
else
  warn "Unexpected response: $response2"
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
header "Results"
info "  Test 1 (no cookie → placeholder): $response1"
info "  Test 2 (cookie → webui-sim):      $response2"
echo ""
ok "Enhancement 2 demo complete."
echo ""
info "Ingress lifecycle tests:"
info "  Clean shutdown:  Ctrl+C the tunnel client → minikube kubectl -- get ingress -n tunnel → empty"
info "  Crash recovery:  kill -9 the client → wait 60s → minikube kubectl -- get ingress -n tunnel → empty"
