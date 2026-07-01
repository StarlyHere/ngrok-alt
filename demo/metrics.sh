#!/usr/bin/env bash
# metrics.sh — Display Prometheus metrics: assignments, reassignments, heartbeat, gateway.
# Wraps: test-with-pods.md §12a–12d
#
# Usage:  demo/metrics.sh

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_namespace

header "Metrics — Coordinator + Gateway"

# ── §12a + §12b  Assignment counters from Coordinator ─────────────────────────
step "Assignment counters  (control:8090/actuator/prometheus)"
raw=$(curl_pod "demo-metrics-$$" "http://control:8090/actuator/prometheus")

assigned=$(echo "$raw" \
  | grep '^tunnel_assignments_total{outcome="assigned"}' \
  | awk '{print $2}' | cut -d'.' -f1 || true)
reassigned=$(echo "$raw" \
  | grep '^tunnel_assignments_total{outcome="reassigned"}' \
  | awk '{print $2}' | cut -d'.' -f1 || true)

echo ""
printf "   %-30s %s\n" "Assignments (total):"   "${assigned:-0}"
printf "   %-30s %s\n" "Reassignments (failover):" "${reassigned:-0}"
echo ""

[ "${assigned:-0}" -gt 0 ]    && ok "Assignment counter is non-zero" \
                               || warn "Assignment counter is 0 — have clients connected?"
[ "${reassigned:-0}" -gt 0 ]  && ok "Reassignment counter is non-zero (failover occurred)" \
                               || info "Reassignment counter is 0  (run demo/failover.sh to trigger one)"

# ── Active session + pod counts from Redis ────────────────────────────────────
step "Active sessions and healthy pods  (Redis)"
sess_count=$(redis_exec keys 'session:*' | grep -c 'session:' || true)
pod_count=$(redis_exec keys 'pod:*'     | grep -c 'pod:'     || true)
echo ""
printf "   %-30s %s\n" "Active sessions:" "$sess_count"
printf "   %-30s %s\n" "Registered pods:" "$pod_count"
echo ""

# ── §12c  Heartbeat validation ────────────────────────────────────────────────
step "Heartbeat — pod TTL is being refreshed"
first_pod=$(redis_exec keys 'pod:*' | head -1)
if [ -z "$first_pod" ]; then
  warn "No pod keys in Redis — cannot check heartbeat."
else
  ttl1=$(redis_exec ttl "$first_pod")
  info "Initial TTL for ${first_pod#pod:}: ${ttl1}s  — waiting 5 s..."
  sleep 5
  ttl2=$(redis_exec ttl "$first_pod")
  info "TTL after 5 s:  ${ttl2}s"

  # Heartbeat resets the TTL; if the pod is alive, ttl2 should be >= ttl1-3
  # (the heartbeat period is shorter than 5 s, so it should have fired at least once)
  if [ "${ttl2:-0}" -ge $((${ttl1:-0} - 3)) ]; then
    ok "TTL is being refreshed — heartbeat is healthy"
  else
    warn "TTL dropped by more than expected — heartbeat may be slow or stopped"
    info "Check: minikube kubectl -- -n tunnel logs deploy/control | grep heartbeat"
  fi
fi

# ── §12d  Gateway metrics (if exposed) ───────────────────────────────────────
step "Gateway metrics  (qa6-gateway:9000/actuator/prometheus)"
gw_raw=$(curl_pod "demo-gwmet-$$" \
  "http://qa6-gateway:9000/actuator/prometheus" 2>/dev/null || true)

if [ -z "$gw_raw" ]; then
  warn "Gateway metrics endpoint not reachable — may not be exposed."
else
  echo ""
  echo "$gw_raw" \
    | grep -E '^(http_server_requests_seconds_count|gateway_requests)' \
    | head -20 \
    | sed 's/^/   /' || true
  echo ""
  ok "Gateway metrics available"
fi

divider
ok "Metrics check complete."
