#!/usr/bin/env bash
# status.sh — Concise health summary of the running tunnel platform.
# Wraps: test-with-pods.md §4e (pod listing), §11b (session count), §11c (pod registry)
#
# Usage:  demo/status.sh

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_namespace

header "Tunnel Platform — Health Summary"

# ── Pod status ─────────────────────────────────────────────────────────────────
step "Kubernetes pods"
divider
kc -n "$NS" get pods \
  --no-headers \
  -o custom-columns='NAME:.metadata.name,READY:.status.containerStatuses[0].ready,STATUS:.status.phase'
divider

# ── Service readiness (per component) ─────────────────────────────────────────
step "Component readiness"
for svc in redis control router qa6-gateway; do
  ready=$(kc -n "$NS" get pods -l "app=$svc" --no-headers 2>/dev/null \
    | awk '{print $2}' | grep -c '^1/1$' || true)
  if [ "$ready" -ge 1 ]; then
    ok "$svc"
  else
    warn "$svc  — not ready (check logs with: minikube kubectl -- -n tunnel logs deploy/$svc)"
  fi
done

tunnel_pods=$(kc -n "$NS" get pods -l app=tunnel-pod --no-headers 2>/dev/null \
  | grep -c 'Running' || true)
ok "tunnel-pod  ×$tunnel_pods running"

info "tunnel-client  (runs on laptop — not a pod)"

# ── Session count ──────────────────────────────────────────────────────────────
step "Active sessions (Redis Session Registry)"
sess_count=$(redis_exec keys 'session:*' | grep -c 'session:' || true)
ok "$sess_count active session(s)"

# ── Per-pod connection count ───────────────────────────────────────────────────
step "Tunnel pod load (Redis Pod Registry)"
kc -n "$NS" exec deploy/redis -- sh -c \
  'for k in $(redis-cli keys "pod:*"); do
     id="${k#pod:}"
     conns=$(redis-cli hget "$k" conns)
     host=$(redis-cli hget "$k" host)
     ttl=$(redis-cli ttl "$k")
     echo "  pod: ${id##*-}...  conns=$conns  host=$host  ttl=${ttl}s"
   done' 2>/dev/null | tr -d '\r'

divider
ok "Status check complete.  Next: demo/tunnel.sh  or  demo/loadbalance.sh"
