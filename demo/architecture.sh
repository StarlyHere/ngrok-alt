#!/usr/bin/env bash
# architecture.sh — Print a clean architecture diagram for presentations.
# Source material: Architecture Reference section of test-with-pods.md
#
# Usage:  demo/architecture.sh

set -euo pipefail
source "$(dirname "$0")/common.sh"

# No require_namespace — diagram works whether or not the system is deployed.

header "Tunnel Platform — Architecture"

cat << 'EOF'

  ┌──────────────────────────────────────────────────────────────────────┐
  │  DEVELOPER LAPTOP                                                    │
  │                                                                      │
  │   Spring Boot App  :3000  ◄──  Tunnel Client (outbound SSH)         │
  │                                  │  connects to NodePort :30022      │
  └──────────────────────────────────┼───────────────────────────────────┘
                                     │ outbound SSH (traverses NAT)
  ┌──────────────────────────────────▼───────────────────────────────────┐
  │  KUBERNETES CLUSTER                                                   │
  │                                                                       │
  │  CONTROL PLANE                                                        │
  │   Tunnel Client  ──────────►  Coordinator (:30090)  ──►  Redis       │
  │   (laptop, on connect)         assigns pod, returns WS URL           │
  │                                                                       │
  │  DATA PLANE                                                           │
  │                                                                       │
  │   Inbound Request                                                     │
  │         │                                                             │
  │         ▼                                                             │
  │   ┌─────────────────┐                                                │
  │   │  QA6 Gateway    │  :9000  ◄── single entry point                 │
  │   └────────┬────────┘                                                │
  │            │                                                          │
  │            ▼                                                          │
  │   ┌─────────────────┐                                                │
  │   │  Decision Layer │  inspects X-Tunnel-Session header              │
  │   └────────┬────────┘                                                │
  │            │                                                          │
  │     ┌──────┴──────┐                                                  │
  │     │             │                                                   │
  │     ▼             ▼                                                   │
  │  Normal         Tunnel                                                │
  │  Request        Request                                               │
  │     │             │                                                   │
  │     ▼             ▼                                                   │
  │  QA6          Router                                                  │
  │  Service       │                                                     │
  │                │                                                      │
  │                ▼                                                      │
  │              Redis  ◄── session → pod lookup                         │
  │                │                                                      │
  │                ▼                                                      │
  │           Tunnel Pod  ◄── pod IP (sticky, Router only)               │
  │                │                                                      │
  │                ▼                                                      │
  │           SSH  (:30022, sessionAffinity: ClientIP)                   │
  │                │                                                      │
  └────────────────┼─────────────────────────────────────────────────────┘
                   │ exits cluster via NodePort
  ┌────────────────▼─────────────────────────────────────────────────────┐
  │  DEVELOPER LAPTOP                                                     │
  │   Tunnel Client  ──►  http://127.0.0.1:3000  (your Spring Boot app)  │
  └───────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────────┐
  │                      REDIS REGISTRIES                               │
  │                                                                     │
  │   auth:*           Bearer token → owner mapping                    │
  │   session:*        Session UUID → pod + owner + active flag        │
  │   pod:*            Pod ID → host / port / connection count         │
  │   owner-sessions:* Owner → set of session UUIDs                   │
  │   subdomain:*      Subdomain slug → session UUID                  │
  └─────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────────┐
  │                  KEY SYSTEM PROPERTIES                              │
  │                                                                     │
  │   Least-connections   New clients assigned to least-loaded pod     │
  │   Automatic failover  Coordinator detects pod loss, reassigns      │
  │   Zero-downtime       Same session ID survives pod replacement     │
  │   Redis-backed        All state in Redis — coordinator is          │
  │   routing             stateless and restartable                    │
  └─────────────────────────────────────────────────────────────────────┘

EOF

# If the system is running, append live stats.
if kc get ns "$NS" >/dev/null 2>&1; then
  divider
  info "Live snapshot (system is deployed):"
  sess=$(redis_exec keys 'session:*' 2>/dev/null | grep -c 'session:' || echo 0)
  pods=$(redis_exec keys 'pod:*'     2>/dev/null | grep -c 'pod:'     || echo 0)
  echo "   Active sessions : $sess"
  echo "   Registered pods : $pods"
fi
