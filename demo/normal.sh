#!/usr/bin/env bash
# normal.sh — Demonstrate Gateway → Decision Layer → QA6 Service routing.
# Wraps: test-with-pods.md §5b (normal request, no session header)
#
# A request with NO X-Tunnel-Session header must be routed to the QA6 Service,
# never to the tunnel path.
#
# Usage:  demo/normal.sh

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_namespace

header "Normal Request — Gateway → QA6 Service"

step "Sending request to QA6 Gateway (port 9000) with no session header"
info "Gateway Decision Layer should detect: no session → route to QA6 Service"
divider

response=$(curl_pod "demo-normal-$$" \
  "http://qa6-gateway:9000/api/hello")

echo ""
echo "   Response:"
echo "   $response"
echo ""
divider

# Validate the response came from the right service.
if echo "$response" | grep -q '"route":"normal"'; then
  ok "Decision Layer correctly routed to QA6 Service (normal path)"
  info "Path: QA6 Gateway -> Decision Layer -> QA6 Service"
elif [ -z "$response" ]; then
  fail "Empty response — is the QA6 Gateway running?"
else
  warn "Unexpected response — inspect the raw output above."
fi
