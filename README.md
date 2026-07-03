# tunnel

Routes QA6 traffic to a locally running service through a managed SSH tunnel — no VPN, no deploy.

```
browser → QA6 gateway → router → tunnel pod ──SSH──▶ your laptop → local service
```

---

## How it works

1. Client connects to a coordinator, which assigns a tunnel pod
2. Client opens an SSH connection to that pod, authenticated with a bearer token
3. Pod registers the session in Redis
4. QA6 gateway reads the `remoteDebugConf` cookie, looks up the session in Redis, forwards matching requests into the tunnel
5. Responses travel back the same way

---

## Modules

https://docs.google.com/document/d/1Svdp7KCRDiU8WOl6SeXAC-7rGrkCjuZlW_wQQ7CBWAY/edit?usp=sharing

| Module | Role |
|---|---|
| `protocol/` | Wire format — frames, codec, mux, transport interfaces |
| `transport-ssh/` | SSH client transport (Apache SSHD) |
| `pod/` | Tunnel pod server — SSH server, session management, request forwarding |
| `control/` | Coordinator — pod assignment, auth token issuance |
| `router/` | Stateless HTTP router — resolves session → pod |
| `qa6-gateway/` | Gateway filter — reads cookie, validates session, forwards to router |
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
| `30022` | Tunnel pod SSH (port-forwarded from cluster) |
| `30080` | Router (NodePort) |
| `4040` | Request inspector |
