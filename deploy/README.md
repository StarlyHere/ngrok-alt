# Deployment assets

This directory contains the Kubernetes manifests used by the local demo. The
QA6 runtime is deployed from the `spr-local-connect` chart in the separate
`helm-internal-tools` repository; do not copy that chart back into this source
repository.

The QA6 release builds and deploys three images from the repository root:

- `spr-local-connect-coordinator` from `Dockerfile.coordinator`;
- `spr-local-connect-router` from `Dockerfile.router`;
- `spr-local-connect-relay` from `Dockerfile.relay`.

The deployed router Service must expose HTTP `8080` and CONNECT `8181`. The
relay must expose HTTP `8080`, SSH `2222`, and its cluster-internal raw bridge
`8182`. Redis and Kafka are existing QA6 dependencies, not resources installed
by this repository.

Use the [QA6 deployment guide](../docs/sprinklr-localconnect-deployment.html)
for build, Helm, rollout, and port verification. Use the
[local WebUI guide](../docs/sprinklr-localconnect-local-webui.html) or
[local microservice guide](../docs/sprinklr-localconnect-local-microservice.html)
after the runtime is healthy.
