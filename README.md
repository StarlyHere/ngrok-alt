# Sprinklr LocalConnect

Routes QA6 traffic to a locally running service through a managed SSH tunnel — no VPN, no deploy.

```
browser → QA6 canary Ingress → router → relay ──SSH──▶ your laptop → local service
```

---

## How it works

1. Client connects to the coordinator, which assigns a relay
2. Client opens an authenticated SSH connection to that relay
3. Relay registers the session in Redis
4. QA6 WebUI's canary Ingress selects browsers carrying `sprLocalConnect=always`
5. Router reads `remoteDebugConf=<sessionId>`, resolves the owning relay in Redis, and forwards the request into the tunnel
6. Responses travel back the same way

For WebUI-to-microservice gRPC calls, `remoteDebugConf=<sessionId>:<microservice>`
selects only the named destination. Sprinklr's gRPC channel uses the router's
HTTP CONNECT port (`8181`); the router resolves the session, connects to the
relay's raw bridge (`8182`), and the existing multiplexed SSH connection carries
the untouched TCP/gRPC byte stream to the local port.
Raw streams intentionally bypass the HTTP inspector on `4040`; verify them in
the local microservice logs or debugger.

If the selected session is absent or has no live relay, the router replays the
request through its original QA ingress hostname after removing only the
`sprLocalConnect`, `remoteDebugConf`, and tunnel-routing header. The replay is
therefore handled by the normal WebUI backend without looping through the
LocalConnect canary. An explicit `WEBUI_FALLBACK_URL` can override this dynamic
target. The QA6 Helm release allows only hostnames containing `qa6` as an exact
hyphen/dot-delimited environment token and ending in `.sprinklr.com`; dynamic
replay always uses HTTPS. If the host is not allowed or replay fails, the router
returns `502 {"error":"tunnel-not-found"}`.

---

## Modules

https://docs.google.com/document/d/1Svdp7KCRDiU8WOl6SeXAC-7rGrkCjuZlW_wQQ7CBWAY/edit?usp=sharing

| Module | Role |
|---|---|
| `protocol/` | Wire format — frames, codec, mux, transport interfaces |
| `transport-ssh/` | SSH client transport (Apache SSHD) |
| `pod/` | Relay implementation — SSH server, session management, request forwarding |
| `control/` | Coordinator implementation — relay assignment, auth token issuance |
| `router/` | Stateless HTTP router — resolves session → pod |
| `qa6-gateway/` | Optional standalone decision gateway; not deployed in the QA6 canary-Ingress path |
| `client/` | CLI entry point — connects to coordinator, opens tunnel, heartbeat |
| `deploy/k8s/` | Kubernetes manifests |

---

## Quickstart

**Prerequisites:** Java 21, Minikube, Podman

```bash
# Build all modules
./gradlew build

# Deploy to local cluster
bash demo/deploy.sh

# Start the tunnel (token is printed by deploy.sh)
java -jar client/build/libs/client-0.1.0-SNAPSHOT.jar \
  http 3000 \
  --transport=ssh \
  --coordinator=http://localhost:30092 \
  --token=<your-token>
```

---

## Ports

| Port | What |
|---|---|
| `30092` | Coordinator (port-forwarded from cluster) |
| `30022` | Relay SSH (port-forwarded from cluster) |
| `30080` | Router (NodePort) |
| `4040` | Request inspector |

---

## QA6 deployment

QA6 runs three application images: `spr-local-connect-coordinator`, `spr-local-connect-router`, and
`spr-local-connect-relay`. The WebUI QA6 canary Ingress routes opted-in browsers directly
to the router, so the optional gateway image is not deployed. The laptop CLI
remains local. Redis and Kafka are shared QA6 services; the Helm release does
not install either one.

Merging an MR into the default branch automatically starts a GitLab push
pipeline. It invokes the QA6 Jenkins `ci-docker-image-builder` job for all three
images in parallel, collects their generated tags, and then invokes
`custom-k8s-helm-deployment` for the `qa6-tier1` release. Deployment does not
start unless all three image builds succeed. QA6 deployments are serialized,
and a newer default-branch pipeline cancels older builds that have not reached
deployment.

The protected, masked GitLab variables `QA6_JENKINS_USER` and
`QA6_JENKINS_TOKEN` must exist and be available to the protected default
branch. The automated builds use these Dockerfiles:

| Image | Dockerfile |
|---|---|
| `spr-local-connect-coordinator` | `Dockerfile.coordinator` |
| `spr-local-connect-router` | `Dockerfile.router` |
| `spr-local-connect-relay` | `Dockerfile.relay` |

Then run `custom-k8s-helm-deployment` with:

| Parameter | Value |
|---|---|
| `GIT_REPO_URL` | `git@prod-gitlab.sprinklr.com:sprinklr-k8s/helm-internal-tools.git` |
| `KUBE_CLUSTER` | `apps-gke` |
| `CHART_NAME` | `spr-local-connect` |
| `CHART_RELEASE_NAME` | `qa6-tier1` |
| `CHART_NAMESPACE` | `spr-apps` |
| `CHART_REPO_BRANCH` | `origin/main` |
| `ITOPS_REPO_BRANCH` | `origin/custom-helm-deploy` |
| `EXTRA_VALUES` | `--set-string=image.coordinator.tag=<coordinator-tag>,image.router.tag=<router-tag>,image.relay.tag=<relay-tag>` |

For an explicit redeployment, start a GitLab pipeline manually with
`DEPLOY_SPR_LOCAL_CONNECT=true`. Set `BUILD_SPR_LOCAL_CONNECT=true` instead to
build and publish all three images without deploying them. The Jenkins
parameters above remain useful for recovery and debugging.

The shared `webui` Helm chart creates QA6-only NGINX canary Ingresses pointing
to the router. Each developer sets two cookies on the QA6 hostname:

```text
sprLocalConnect=always
remoteDebugConf=<their-session-id>
```

The first cookie selects the shared canary backend; the second independently
maps the request to that developer's Redis session and laptop connection. For
local WebUI backend development, set both cookies with `Path=/ui`; this keeps
frontend routes such as `/care/...` on normal QA6 while tunnelling `/ui/...`
requests. Use `Path=/` only when the entire host should go to the local service.

To intercept a downstream microservice instead, append its exact tier type:

```text
remoteDebugConf=<their-session-id>:process-engine
```

In microservice mode, do **not** set `sprLocalConnect`. The original request must
stay on the QA6 WebUI so its gRPC client can select the named tier and use the
cluster-internal CONNECT router. Only that downstream connection is tunnelled to
the local service. `sprLocalConnect=always` is reserved for routing the WebUI
HTTP request itself to a locally running WebUI.

After deployment, connect to the cluster and expose the two developer-facing
ports locally:

```bash
kubectl -n spr-apps get pods -l app.kubernetes.io/name=spr-local-connect
kubectl -n spr-apps port-forward svc/spr-local-connect-tier1-coordinator 8090:8090
kubectl -n spr-apps port-forward svc/spr-local-connect-tier1-relay 2222:2222
```

Create/login through the coordinator API on `localhost:8090`, then start the
local client using the returned token. The coordinator response advertises
`ssh://localhost:2222`, matching the second port-forward.

For the normal laptop workflow, start the application that should receive QA6
requests and use the QA6 launcher instead of managing those processes manually:

```bash
# Example: the local application is already listening on port 8080
scripts/qa6-local-connect.sh 8080

# Route WebUI calls whose destination tier type is process-engine.
scripts/qa6-local-connect.sh 8080 "$(openssl rand -hex 16)" process-engine
```

The launcher builds the client incrementally, opens the access-host SSH session,
starts both remote Kubernetes port-forwards, validates or refreshes the saved
token, generates a session ID, prints the required browser cookies, and runs the
client. It may prompt for SSH and sudo credentials; it never stores them. Press
Ctrl+C to close the client and all port-forwards. Run
`scripts/qa6-local-connect.sh --help` for connection overrides.

The production release uses its own Redis key prefix and cookie name. Sessions
created by an earlier POC release are not migrated; start a new session and use
the `sprLocalConnect` browser cookie after this release is live.

### Shared Redis and Kafka

The QA6 values use `qa6-redis-int.sprinklr.com:6379`. Every key is prefixed with
`spr-local-connect:qa6-tier1:` so this release cannot overwrite another application's
keys.

Kafka is needed only for the optional asynchronous notification path: coordinator
creates one topic per live tunnel session, and router consumes that topic and
forwards records to the developer's local service. Normal HTTP tunnelling does
not require Kafka. QA6 enables this path using the existing core Kafka brokers,
with release-specific topic and consumer-group names. Set `kafka.enabled=false`
if notification forwarding is not required.
