#!/usr/bin/env bash
# deploy.sh — Full environment deploy (cluster → images → infra → gateway → pods → clients)
# Wraps: test-with-pods.md §1 (cluster), §2 (images), §3 (clean slate), §4a–4e (staged deploy)
#
# Usage:  demo/deploy.sh [--skip-build] [--skip-cluster]
#   --skip-build    assume images are already loaded into minikube
#   --skip-cluster  assume minikube + podman are already running

set -euo pipefail
source "$(dirname "$0")/common.sh"

SKIP_BUILD=false
SKIP_CLUSTER=false
for arg in "$@"; do
  case $arg in
    --skip-build)   SKIP_BUILD=true ;;
    --skip-cluster) SKIP_CLUSTER=true ;;
  esac
done

header "Tunnel Platform — Full Deploy"

# ── §1  Cluster ────────────────────────────────────────────────────────────────
if $SKIP_CLUSTER; then
  step "Cluster  (skipped — --skip-cluster)"
else
  step "Starting Podman machine"
  if ! podman machine list 2>/dev/null | grep -q 'Currently running'; then
    if ! podman machine list 2>/dev/null | grep -q 'podman-machine-default'; then
      info "Creating new Podman machine (first-time setup)..."
      podman machine init --cpus 4 --memory 8192 --disk-size 40
      podman machine set --rootful
    fi
    podman machine start
    ok "Podman machine started"
  else
    ok "Podman machine already running"
  fi

  step "Starting Minikube"
  if ! minikube status 2>/dev/null | grep -q 'host: Running'; then
    minikube start \
      --driver=podman \
      --container-runtime=containerd \
      --memory=6144 \
      --cpus=4
    ok "Minikube started"
  else
    ok "Minikube already running"
  fi
fi

# ── §2  Build + load images ────────────────────────────────────────────────────
if $SKIP_BUILD; then
  step "Image build  (skipped — --skip-build)"
else
  step "Building all service images with Jib"
  cd "$REPO_ROOT"
  ./gradlew \
    :dev-service:jibBuildTar \
    :control:jibBuildTar \
    :router:jibBuildTar \
    :pod:jibBuildTar \
    :client:jibBuildTar \
    :qa6-gateway:jibBuildTar \
    :webui-placeholder:jibBuildTar
  ok "Gradle build complete"

  step "Loading images into Minikube"
  for module in dev-service control router pod client qa6-gateway webui-placeholder; do
    info "Loading $module..."
    minikube image load "$module/build/jib-image.tar"
  done
  ok "All 7 images loaded"
fi

# ── Resolve Minikube node IP ───────────────────────────────────────────────────
step "Resolving Minikube node IP"
MINIKUBE_IP=$(minikube ip)
ok "Node IP: $MINIKUBE_IP"

# ── §3  Clean slate ────────────────────────────────────────────────────────────
step "Wiping previous deployment (if any)"
kc delete ns "$NS" --ignore-not-found >/dev/null
while kc get ns "$NS" >/dev/null 2>&1; do sleep 2; done
ok "Namespace clean"

# ── §4a  Core infrastructure ───────────────────────────────────────────────────
step "Deploying core infrastructure (Redis · Coordinator · Router · WebUI Placeholder)"
kc apply \
  -f deploy/k8s/00-namespace.yaml \
  -f deploy/k8s/10-redis.yaml \
  >/dev/null
# Patch TUNNEL_URL_PLACEHOLDER with the real node IP so the Coordinator
# returns a URL the laptop client can actually reach.
sed "s|TUNNEL_URL_PLACEHOLDER|ssh://localhost:30022|g" \
  deploy/k8s/20-control.yaml | kc apply -f - >/dev/null
kc apply -f deploy/k8s/30-router.yaml >/dev/null
kc apply -f deploy/k8s/16-webui-placeholder.yaml >/dev/null

for svc in redis control router webui-placeholder; do
  info "Waiting for $svc..."
  wait_for_deploy "$svc"
done

# ── §4b  QA6 Gateway ──────────────────────────────────────────────────────────
step "Deploying QA6 Gateway (port 9000)"
kc apply -f deploy/k8s/35-gateway.yaml >/dev/null
wait_for_deploy "qa6-gateway"

# ── §4c  2 tunnel pods ────────────────────────────────────────────────────────
step "Deploying 2 tunnel pods"
sed 's/replicas: 5/replicas: 2/' deploy/k8s/40-pods.yaml | kc apply -f - >/dev/null
wait_for_pod_registration 2

# ── §4d  Auth token ───────────────────────────────────────────────────────────
step "Seeding bearer token for owner 'alice'"
kc apply -f deploy/k8s/50-token-seed.yaml >/dev/null
ok "Token seeded"

# ── §4e  Nginx ingress addon (required for Enhancement 2 — WebUI ingress) ─────
step "Enabling nginx ingress addon"
if minikube addons list | grep -q 'ingress.*enabled'; then
  ok "Nginx ingress addon already enabled"
else
  minikube addons enable ingress
  info "Waiting for ingress-nginx controller to be ready..."
  kc -n ingress-nginx wait --for=condition=ready pod \
    -l app.kubernetes.io/component=controller \
    --timeout=120s >/dev/null
  ok "Nginx ingress controller ready"
fi
# The nginx admission webhook uses a self-signed cert the K8s API server can't
# verify, causing ingress CRUD calls to fail with x509 errors. Removing it lets
# the API server accept ingress objects without calling the webhook.
kc delete validatingwebhookconfiguration ingress-nginx-admission --ignore-not-found >/dev/null
ok "Nginx admission webhook removed (avoids x509 TLS error on ingress create)"

# ── §4f  Final summary ────────────────────────────────────────────────────────
step "Deployment complete"
divider
kc -n "$NS" get pods
divider
# ── §4g  Port-forwards (Podman driver doesn't expose NodePorts natively) ──────
step "Starting port-forwards (coordinator :30092, tunnel-pod-ssh :30022)"
# Kill any stale port-forward processes first.
pkill -f "port-forward.*30092\|port-forward.*30091\|port-forward.*30022" 2>/dev/null || true
sleep 1

# Coordinator: forward to Deployment (stable across restarts).
minikube kubectl -- -n "$NS" port-forward deploy/control 30092:8090 \
  > /tmp/pf-control.log 2>&1 &
echo $! > /tmp/pf-control.pid

# SSH tunnel: loop-restart port-forward so it survives pod failovers.
(while true; do
  minikube kubectl -- -n "$NS" port-forward svc/tunnel-pod-ssh 30022:2222 2>/dev/null
  sleep 2
done) > /tmp/pf-ssh.log 2>&1 &
echo $! > /tmp/pf-ssh.pid

# Nginx ingress: Podman driver does not expose NodePorts to the Mac host,
# so port-forward the ingress controller to localhost:8888.
minikube kubectl -- -n ingress-nginx port-forward svc/ingress-nginx-controller 8888:80 \
  > /tmp/pf-ingress.log 2>&1 &
echo $! > /tmp/pf-ingress.pid

# Wait until both port-forwards are accepting connections.
# SSH has no HTTP probe so we check that the port is open with nc.
for i in $(seq 1 20); do
  curl -s --connect-timeout 1 http://localhost:30092/healthz >/dev/null 2>&1 && \
  nc -z localhost 30022 2>/dev/null && break
  sleep 1
done
ok "Port-forwards ready  (coordinator :30092, tunnel-pod-ssh :30022, nginx :8888)"

divider
ok "Cluster ready."
echo ""
info "── Enhancement 1: Selective path routing ──────────────────────────────────"
info "  1. Start dev-service on port 3000:"
info "       ./gradlew :dev-service:bootRun"
info "  2. Start the tunnel client:"
info "       java -jar client/build/libs/client-0.1.0-SNAPSHOT.jar \\"
info "         http 3000 --transport=ssh \\"
info "         --coordinator=http://localhost:30092 \\"
info "         --token=demo-alice-7f3c9a2b1e6d4058"
info "  3. Run demo/tunnel.sh to prove routing"
echo ""
info "── Enhancement 2: WebUI ingress lifecycle ─────────────────────────────────"
info "  1. Start webui-sim on port 4000:"
info "       ./gradlew :webui-sim:bootRun"
info "  2. Start the tunnel client with path filter + ingress creation:"
info "       java -jar client/build/libs/client-0.1.0-SNAPSHOT.jar \\"
info "         http 4000 --transport=ssh \\"
info "         --coordinator=http://localhost:30092 \\"
info "         --token=demo-alice-7f3c9a2b1e6d4058 \\"
info "         '--paths=/ui/graphql/**' --create-ingress"
info "  3. Run demo/webui.sh to prove ingress creation + selective routing"
