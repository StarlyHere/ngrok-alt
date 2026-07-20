#!/usr/bin/env bash
# loadbalance.sh — Demonstrate least-connections load balancing across relays.
# Wraps: test-with-pods.md §9a (baseline distribution), §9b (third client), §9c (restore)
#
# Shows: 2-client baseline, add a 3rd client, observe it lands on the less-loaded
# pod, then restores to 2 clients.
#
# Usage:  demo/loadbalance.sh

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_namespace

header "Load Balancing — Least-Connections Demo"

# ── Helper: print per-pod connection counts ────────────────────────────────────
print_distribution() {
  info "Current pod connection counts:"
  kc -n "$NS" exec deploy/redis -- sh -c \
    'for k in $(redis-cli keys "pod:*"); do
       id="${k#pod:}"
       conns=$(redis-cli hget "$k" conns)
       host=$(redis-cli hget "$k" host)
       echo "  ..."${id##*-}"  conns=$conns  ($host)"
     done' 2>/dev/null | tr -d '\r' | sed 's/^/   /'
}

# ── §9a  Baseline: 2 clients, should be 1-1 ──────────────────────────────────
step "Baseline: 2 sessions across 2 pods"
divider
print_distribution
divider

# Count pods and verify spread
pod_count=$(redis_exec keys 'pod:*' | grep -c 'pod:' || true)
if [ "$pod_count" -lt 2 ]; then
  fail "Expected 2 relays registered in Redis, found $pod_count. Deploy with demo/deploy.sh"
fi

# Check each pod has exactly 1 connection
balanced=true
while IFS= read -r key; do
  [ -z "$key" ] && continue
  conns=$(redis_exec hget "$key" conns)
  if [ "$conns" != "1" ]; then
    balanced=false
    warn "Pod ${key#pod:} has conns=$conns (expected 1)"
  fi
done <<< "$(redis_exec keys 'pod:*')"

if $balanced; then
  ok "Even distribution: 1 connection per pod"
else
  warn "Uneven — both clients may have started simultaneously."
  info "Fix: scale clients to 0, wait, then 1, wait, then 2 (see demo/deploy.sh)"
fi

# ── §9b  Add a 3rd client, watch least-conn assignment ───────────────────────
step "Add a 3rd laptop client to observe least-connections placement"
info "Open a NEW terminal on your laptop and run:"
echo ""
MINIKUBE_IP=$(minikube ip 2>/dev/null || echo "<minikube-ip>")
echo "   java -jar client/build/libs/client-0.1.0-SNAPSHOT.jar \\"
echo "     http 3000 \\"
echo "     --coordinator=http://${MINIKUBE_IP}:30092 \\"
echo "     --token=demo-alice-7f3c9a2b1e6d4058"
echo ""
info "Then press ENTER here to check the distribution."
read -r

divider
info "Distribution after 3rd client:"
print_distribution
divider

max_conns=$(kc -n "$NS" exec deploy/redis -- sh -c \
  'max=0; for k in $(redis-cli keys "pod:*"); do
     c=$(redis-cli hget "$k" conns); [ "${c:-0}" -gt "$max" ] && max="$c"
   done; echo $max' 2>/dev/null | tr -d '\r')

if [ "$max_conns" -le 2 ]; then
  ok "3rd session placed on a pod with <=2 connections — least-conn working"
else
  warn "One pod has $max_conns connections — verify coordinator logs if this is unexpected"
fi

# ── §9c  Restore to 2 clients ─────────────────────────────────────────────────
step "Restore to 2 clients — stop the 3rd client (Ctrl-C in its terminal)"
info "Press ENTER here once the 3rd client is stopped."
read -r

info "Waiting for session count to drop back to 2..."
while true; do
  cnt=$(redis_exec keys 'session:*' | grep -c 'session:' || true)
  [ "$cnt" -le 2 ] && break
  sleep 2
done
ok "Back to 2 sessions"
divider
print_distribution
divider
ok "Load balancing demo complete."
