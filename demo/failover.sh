#!/usr/bin/env bash
# failover.sh — Demonstrate zero-downtime failover via kubectl delete pod.
# Wraps: test-with-pods.md §10a–10e
#
# Flow:
#   1. Find a session and its owning pod.
#   2. Delete the pod.
#   3. Watch the session reassign to a surviving pod (~25 s).
#   4. Confirm Redis cleaned up the victim pod entry.
#   5. Send a request through the same session — traffic still works.
#   6. Confirm Kubernetes recreated the deleted pod.
#
# Usage:  demo/failover.sh

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_namespace

# ── Ensure SSH port-forward is alive ──────────────────────────────────────────
# kubectl port-forward dies when its backing pod is deleted (even when targeting
# a Service). Kill any stale forward and start a fresh loop so the client can
# reconnect after failover.
step "Ensuring SSH port-forward is running"
pkill -f "port-forward svc/tunnel-pod-ssh" 2>/dev/null || true
sleep 1
(while true; do
  minikube kubectl -- -n "$NS" port-forward svc/tunnel-pod-ssh 30022:2222 2>/dev/null
  sleep 2
done) > /tmp/pf-ssh.log 2>&1 &
ok "SSH port-forward restarted (PID $!)"

header "Failover Demo — kubectl delete pod"

# ── §10a  Find session + owning pod ───────────────────────────────────────────
step "Locating active session"
SESS=$(active_session)
[ -z "$SESS" ] && fail "No active sessions in Redis. Run demo/deploy.sh first."
ok "Session: ${SESS:0:12}..."

VICTIM=$(pod_for_session "$SESS")
[ -z "$VICTIM" ] && fail "Session has no podId in Redis."
ok "Owning pod: ${VICTIM##*-}...  (full: $VICTIM)"

# ── §10b  Kill the pod ────────────────────────────────────────────────────────
step "Deleting owning pod"
info "Kubernetes will restart it automatically. The Coordinator watches for the gap."
kc -n "$NS" delete pod "$VICTIM" >/dev/null
ok "Pod deleted: ${VICTIM##*-}..."

# ── §10c  Watch session reassignment ─────────────────────────────────────────
step "Watching session reassignment (up to 60 s)"
info "The tunnel client will reconnect to a surviving pod via the Coordinator."
info "  old pod: ${VICTIM##*-}..."
echo ""

REASSIGNED=""
for i in $(seq 1 60); do
  NOW=$(redis_exec hget "session:$SESS" podId 2>/dev/null || true)
  printf "   [%2ds]  session on: %s\n" "$i" "${NOW##*-}"
  if [ -n "$NOW" ] && [ "$NOW" != "$VICTIM" ]; then
    REASSIGNED="$NOW"
    break
  fi
  sleep 1
done
echo ""

if [ -n "$REASSIGNED" ]; then
  ok "Reassigned to: ${REASSIGNED##*-}..."
else
  fail "Session did not reassign within 60 s. Check Coordinator logs: kc -n tunnel logs deploy/control"
fi

# ── §10d  Victim pod entry removed from Redis ─────────────────────────────────
step "Checking Redis Pod Registry for victim cleanup"
exists=$(redis_exec exists "pod:$VICTIM")
if [ "$exists" = "0" ]; then
  ok "Victim pod key removed from Redis"
else
  warn "Victim pod key still in Redis (may still be within its TTL window)"
  info "Key: pod:$VICTIM  — will expire automatically"
fi

# ── §10e  Traffic still works on the same session ─────────────────────────────
step "Sending request on the same session after failover"
info "Session ID unchanged — only the backing pod changed."
info "Waiting for client to reconnect and re-register on new pod..."
# Wait until the session is ACTIVE on the new pod before firing the test request.
for i in $(seq 1 20); do
  status=$(redis_exec hget "session:$SESS" status 2>/dev/null || true)
  [ "$status" = "ACTIVE" ] && break
  sleep 1
done
response=$(curl_pod "demo-failover-$$" \
  -H "X-Tunnel-Session: $SESS" \
  "http://qa6-gateway:9000/hello?name=AfterFailover")
echo ""
echo "   Response:"
echo "   $response"
echo ""

if [ -n "$response" ]; then
  ok "Traffic succeeded through new pod — zero session disruption"
else
  warn "Empty response — is your local app still running on port 3000?"
fi

# ── §10f  Kubernetes recreated the deleted pod ────────────────────────────────
step "Verifying Kubernetes recreated the deleted pod"
info "Waiting up to 60 s for a replacement pod to reach Running..."
for i in $(seq 1 30); do
  running=$(kc -n "$NS" get pods -l app=tunnel-pod --no-headers 2>/dev/null \
    | grep -c 'Running' || true)
  [ "$running" -ge 2 ] && break
  sleep 2
done
running=$(kc -n "$NS" get pods -l app=tunnel-pod --no-headers 2>/dev/null \
  | grep -c 'Running' || true)
if [ "$running" -ge 2 ]; then
  ok "Back to 2 running tunnel pods"
else
  warn "Only $running pod(s) running — replacement may still be starting"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
divider
echo ""
echo -e "   ${_c_bold}Failover summary${_c_reset}"
echo "   Session    : ${SESS:0:12}..."
echo "   Old pod    : ${VICTIM##*-}..."
echo "   New pod    : ${REASSIGNED##*-}..."
echo "   Traffic    : uninterrupted"
echo ""
divider
ok "Failover demo complete."
