#!/usr/bin/env bash
# redis.sh — Human-readable dump of all five Redis registries.
# Wraps: test-with-pods.md §11a–11e
#
# Actual key schema (discovered from live cluster):
#   auth:token:<token-id>     string  → owner name
#   session:<uuid>            hash    → ownerId, subdomain, podId, status, createdAt
#   pod:<pod-name>            hash    → host, port, secure, conns, lastSeen  (TTL-keyed)
#   owner:<owner>:sessions    set     → session UUIDs
#   subdomain:<slug>          string  → session UUID
#
# Usage:  demo/redis.sh

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_namespace

header "Redis Registry Inspector"

# ── Helper: print a hash in two-column format ──────────────────────────────────
print_hash() {
  local key="$1"
  kc -n "$NS" exec deploy/redis -- redis-cli hgetall "$key" 2>/dev/null \
    | tr -d '\r' \
    | awk 'NR%2==1{field=$0} NR%2==0{printf "   %-18s %s\n", field, $0}'
}

# ── §11a  Auth Registry  (auth:token:*  →  string: owner name) ────────────────
step "Auth Registry  (auth:token:*)"
keys=$(redis_exec keys 'auth:token:*')
if [ -z "$keys" ]; then
  warn "No auth keys found."
else
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    owner=$(redis_exec get "$key")
    ttl=$(redis_exec ttl "$key")
    printf "   %-45s  owner=%-10s  ttl=%ss\n" "$key" "$owner" "$ttl"
  done <<< "$keys"
  ok "Auth registry OK"
fi

# ── §11b  Session Registry  (session:*  →  hash) ─────────────────────────────
step "Session Registry  (session:*)"
keys=$(redis_exec keys 'session:*')
if [ -z "$keys" ]; then
  warn "No session keys found."
else
  count=0
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    count=$((count+1))
    uuid="${key#session:}"
    info "Session $count: ${uuid:0:12}..."
    print_hash "$key"
    echo ""
  done <<< "$keys"
  ok "$count session(s) total"
fi

# ── §11c  Pod Registry  (pod:*  →  hash, TTL-keyed via heartbeat) ─────────────
step "Pod Registry  (pod:*)"
keys=$(redis_exec keys 'pod:*')
if [ -z "$keys" ]; then
  warn "No pod keys found."
else
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    short="${key#pod:}"
    info "Pod: ...${short##*-}"
    print_hash "$key"
    ttl=$(redis_exec ttl "$key")
    echo "   ttl                $ttl s"
    echo ""
  done <<< "$keys"
  ok "Pod registry OK"
fi

# ── §11d  Owner Sessions Registry  (owner:<name>:sessions  →  set) ───────────
step "Owner Sessions Registry  (owner:*:sessions)"
keys=$(redis_exec keys 'owner:*:sessions')
if [ -z "$keys" ]; then
  warn "No owner session keys found."
else
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    info "Key: $key"
    kc -n "$NS" exec deploy/redis -- redis-cli smembers "$key" 2>/dev/null \
      | tr -d '\r' \
      | while IFS= read -r member; do
          [ -z "$member" ] && continue
          echo "   session: ${member:0:12}..."
        done
    echo ""
  done <<< "$keys"
  ok "Owner sessions registry OK"
fi

# ── §11e  Subdomain Registry  (subdomain:*  →  string: session UUID) ──────────
step "Subdomain Registry  (subdomain:*)"
keys=$(redis_exec keys 'subdomain:*')
if [ -z "$keys" ]; then
  warn "No subdomain keys found (subdomain routing may not be active)."
else
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    val=$(redis_exec get "$key")
    info "${key}  ->  ${val:0:12}..."
  done <<< "$keys"
  ok "Subdomain registry OK"
fi

divider
ok "Registry dump complete."
