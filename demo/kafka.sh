#!/usr/bin/env bash
# kafka.sh — Demonstrate Enhancement 3: Kafka async interception.
#
# Architecture under test:
#   kafka-producer-sim → notifications_<sessionId> (Redpanda in-cluster)
#                      → KafkaForwardingConsumer (router)
#                      → POST /_kafka/record (existing HTTP tunnel, zero changes)
#                      → webui-sim (laptop :4000) logs the record
#
# Prerequisites:
#   - demo/deploy.sh already run (cluster up, Redpanda running)
#   - webui-sim running locally:  ./gradlew :webui-sim:bootRun
#   - Tunnel client started:
#       java -jar client/build/libs/client-0.1.0-SNAPSHOT.jar \
#         http 4000 --transport=ssh \
#         --coordinator=http://localhost:30092 \
#         --token=demo-alice-7f3c9a2b1e6d4058
#
# Usage:  demo/kafka.sh [session-uuid]

set -euo pipefail
source "$(dirname "$0")/common.sh"

require_namespace
ensure_portforwards

header "Kafka Interception Demo — Enhancement 3"

# ── Discover or accept a session ───────────────────────────────────────────────
if [ "${1:-}" != "" ]; then
  SESS="$1"
  info "Using provided session: ${SESS:0:12}..."
else
  step "Discovering active session from Redis"
  SESS=$(active_session)
  if [ -z "$SESS" ]; then
    fail "No active sessions found. Start the tunnel client on your laptop first."
  fi
  ok "Session found: ${SESS:0:12}..."
fi

TOPIC="notifications_${SESS}"

# ── Verify Redpanda ────────────────────────────────────────────────────────────
step "Verifying Redpanda is running"
kc -n "$NS" get pod -l app=redpanda --no-headers 2>/dev/null | grep -q Running \
  && ok "Redpanda pod is Running" \
  || fail "Redpanda pod is not Running. Run demo/deploy.sh first."

# ── Wait for topic creation ────────────────────────────────────────────────────
# The coordinator creates the topic asynchronously in <200ms after session assignment.
# Poll with rpk topic list (no --no-headers flag — that flag does not exist in v23.x).
step "Waiting for Kafka topic to be created by Coordinator"
TOPIC_WAIT=0
TOPIC_FOUND=false
while [ $TOPIC_WAIT -lt 15 ]; do
  if kc -n "$NS" exec deploy/redpanda -- \
      /usr/bin/rpk topic list 2>/dev/null | grep -q "$TOPIC"; then
    TOPIC_FOUND=true
    ok "Topic '$TOPIC' exists in Redpanda (${TOPIC_WAIT}s)"
    break
  fi
  sleep 1
  TOPIC_WAIT=$((TOPIC_WAIT + 1))
done
if [ "$TOPIC_FOUND" = "false" ]; then
  warn "Topic '$TOPIC' not found after 15s"
  info "Is KAFKA_ENABLED=true on the control pod?"
  info "Check: minikube kubectl -- -n tunnel logs deploy/control | grep kafka"
fi

# ── Test 1: Basic round-trip ───────────────────────────────────────────────────
step "Test 1 — Basic round-trip (kafka-producer-sim → tunnel → webui-sim)"
info "Starting kafka-producer-sim as a k8s Job (SESSION_ID=$SESS)"
divider

# Delete any previous run of the job.
kc -n "$NS" delete job kafka-producer-sim --ignore-not-found >/dev/null

kc -n "$NS" apply -f - >/dev/null <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: kafka-producer-sim
  namespace: tunnel
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: kafka-producer-sim
          image: tunnel/kafka-producer-sim:latest
          imagePullPolicy: IfNotPresent
          env:
            - name: SESSION_ID
              value: "${SESS}"
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: "kafka-bootstrap:9092"
EOF

info "kafka-producer-sim started — publishing every 5s to $TOPIC"
info "Check webui-sim logs for:  kafka record received: session=${SESS:0:12}..."
info ""
info "   ./gradlew :webui-sim:bootRun  (in another terminal — watch its output)"
info ""
# Wait for the router to assign the partition (topic discovered via metadata refresh,
# typically within metadata.max.age.ms=10s of topic creation).
info "Waiting for router to discover topic and assign partition (expect ≤10s)..."
ASSIGN_WAIT=0
ASSIGNED=false
while [ $ASSIGN_WAIT -lt 20 ]; do
  if kc -n "$NS" logs deploy/router --tail=20 2>/dev/null \
      | grep -q "partitions assigned:.*$TOPIC"; then
    ASSIGNED=true
    ok "Router has partition assigned for $TOPIC (${ASSIGN_WAIT}s)"
    break
  fi
  sleep 1
  ASSIGN_WAIT=$((ASSIGN_WAIT + 1))
done
if [ "$ASSIGNED" = "false" ]; then
  warn "Router has not yet assigned partition after 20s — waiting 10s more then checking"
  sleep 10
fi
# Give the producer one publish cycle (5s) before checking.
sleep 6

# Check router logs for forwarding activity.
step "Checking router logs for Kafka forward activity"
divider
kc -n "$NS" logs deploy/router --tail=30 2>/dev/null | grep -i "kafka" || true
divider

ok "Test 1 complete — verify webui-sim terminal shows 'kafka record received'"

# ── Test 2: Session isolation ──────────────────────────────────────────────────
step "Test 2 — Session isolation (second session receives no records)"
info "Starting a second tunnel client for a new session (Session B)..."
divider

SESS_B=$(redis_exec keys 'session:*' | grep -v "session:${SESS}" | head -1 | sed 's/session://' || true)
if [ -z "$SESS_B" ]; then
  warn "Only one session active — cannot run isolation test."
  info "Start a second tunnel client to test isolation."
  info "  java -jar client/build/libs/client-0.1.0-SNAPSHOT.jar \\"
  info "    http 4001 --transport=ssh \\"
  info "    --coordinator=http://localhost:30092 \\"
  info "    --token=demo-alice-7f3c9a2b1e6d4058"
else
  ok "Session B found: ${SESS_B:0:12}..."
  info "kafka-producer-sim is publishing only to notifications_${SESS:0:12}..."
  info "Session B's webui-sim should receive NO records (different topic, different pod)"
  info "Verify: Session B's webui-sim terminal shows no new 'kafka record received' lines"
fi

# ── Test 3: Lifecycle / topic cleanup ─────────────────────────────────────────
step "Test 3 — Lifecycle: topic is deleted after session ends"
info "To test topic cleanup:"
info "  1. Stop the tunnel client (Ctrl+C)"
info "  2. Wait up to 60s for KafkaTopicReconciler"
info "  3. Run: minikube kubectl -- -n tunnel exec deploy/redpanda -- /usr/bin/rpk topic list"
info "  4. Verify '$TOPIC' is no longer listed"

# ── Cleanup producer ───────────────────────────────────────────────────────────
echo ""
step "Stopping kafka-producer-sim job"
kc -n "$NS" delete job kafka-producer-sim --ignore-not-found >/dev/null
ok "kafka-producer-sim stopped"

divider
ok "Enhancement 3 demo complete."
echo ""
info "Summary:"
info "  Test 1: kafka-producer-sim → Redpanda → KafkaForwardingConsumer → tunnel → webui-sim"
info "  Test 2: Session isolation — records go only to the matching session's pod"
info "  Test 3: Topic deleted by Coordinator (clean shutdown) or Reconciler (crash recovery)"
