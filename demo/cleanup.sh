#!/usr/bin/env bash
# cleanup.sh — Tear down the tunnel environment.
# Wraps: test-with-pods.md §14
#
# Usage:
#   demo/cleanup.sh              # remove namespace only, keep cluster running
#   demo/cleanup.sh --full       # also stop minikube + podman machine
#   demo/cleanup.sh --reset      # full wipe (minikube delete + podman machine rm)

set -euo pipefail
source "$(dirname "$0")/common.sh"

MODE="namespace"
for arg in "$@"; do
  case $arg in
    --full)  MODE="full" ;;
    --reset) MODE="reset" ;;
  esac
done

header "Tunnel Platform — Cleanup  (mode: $MODE)"

# ── Always: stop port-forwards ────────────────────────────────────────────────
step "Stopping port-forwards"
pkill -f "port-forward.*30092\|port-forward.*30091\|port-forward.*30022" 2>/dev/null || true
pkill -f "client-0.1.0" 2>/dev/null || true
ok "Port-forwards stopped"

# ── Always: remove the tunnel namespace ───────────────────────────────────────
step "Removing tunnel namespace"
if kc get ns "$NS" >/dev/null 2>&1; then
  kc delete ns "$NS"
  info "Waiting for namespace to terminate..."
  while kc get ns "$NS" >/dev/null 2>&1; do sleep 2; done
  ok "Namespace '$NS' removed"
else
  ok "Namespace '$NS' was not present — nothing to remove"
fi

# ── --full: stop cluster and VM ───────────────────────────────────────────────
if [ "$MODE" = "full" ] || [ "$MODE" = "reset" ]; then
  step "Stopping Minikube"
  minikube stop || warn "minikube stop failed (may already be stopped)"
  ok "Minikube stopped"

  step "Stopping Podman machine"
  podman machine stop || warn "podman machine stop failed (may already be stopped)"
  ok "Podman machine stopped"
fi

# ── --reset: full wipe ────────────────────────────────────────────────────────
if [ "$MODE" = "reset" ]; then
  step "Deleting Minikube cluster"
  minikube delete || warn "minikube delete failed"
  ok "Minikube cluster deleted"

  step "Removing Podman machine"
  podman machine rm -f podman-machine-default || warn "podman machine rm failed"
  ok "Podman machine removed"
fi

divider
case $MODE in
  namespace) ok "Namespace removed. Cluster still running — redeploy with  demo/deploy.sh --skip-cluster" ;;
  full)      ok "Cluster stopped. Restart with  demo/deploy.sh --skip-build" ;;
  reset)     ok "Full reset complete. Next run will need  demo/deploy.sh  (full)" ;;
esac
