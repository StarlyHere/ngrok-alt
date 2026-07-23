# Sprinklr LocalConnect Live QA

Verify that a local code change is actually live against an authenticated QA6
session, by tunneling QA6 traffic to the local service and confirming a
matching request round-tripped through the tunnel.

## Preconditions

- The local application under test must already be listening on
  `127.0.0.1:<port>` before starting the tunnel. `qa6-local-connect.sh` checks
  this itself and exits with `nothing is listening on 127.0.0.1:<port>` if not.
- QA6 SSH access must work: `ssh -J access.qa6.spr-ops.com 10.56.32.106`
  (jump host `access.qa6.spr-ops.com` → cluster host `10.56.32.106`). SSH and
  `sudo` may prompt interactively on first use.
- Java 21+ must be available (`select_java_21` in `qa6-local-connect.sh`
  handles discovery via `/usr/libexec/java_home` if `java` isn't already 21+).

## Step 1 — Launch the tunnel

For an autonomous flow, prefer `--json` (machine-parseable):

```
.agents/skills/spr-local-connect-live-qa/scripts/run_live_qa.sh --json <local-port> [session-id] [microservice]
```

For manual/human use, drop `--json` for plain text:

```
.agents/skills/spr-local-connect-live-qa/scripts/run_live_qa.sh <local-port> [session-id] [microservice]
```

`--json` only changes the output format — the underlying tunnel setup is
identical either way. Both forms background `scripts/qa6-local-connect.sh`,
tee its output to `/tmp/spr-live-qa-<pid>.log`, and record the pid in
`/tmp/spr-live-qa.pid`.

- If SSH or `sudo` prompts for a password, this will show up as the launch
  appearing to hang. Surface this to the human immediately and wait for them
  to enter it — do not attempt to guess or automate credential entry. This
  applies regardless of `--json`/browser-automation availability; it's a
  property of the SSH jump-host step, not the browser step.
- If the log or error output contains `nothing is listening on
  127.0.0.1:<port>`, the local application under test isn't running yet.
  Start it first, then retry.
- If it contains `localhost:8090 is already in use` or `localhost:2222 is
  already in use`, a previous tunnel is still running. Run
  `run_live_qa.sh --stop` first, then retry.

## Step 2 — Ready

`run_live_qa.sh` blocks until the inspector API
(`http://127.0.0.1:${QA6_INSPECTOR_PORT:-4040}/api/requests`) responds, which
only happens once the coordinator port-forward, relay port-forward, login,
and tunnel client are all up. No extra waiting is needed once it returns.

With `--json`, on success it prints a single JSON object to stdout and exits
0:

```json
{
  "status": "READY",
  "sessionId": "3fa2c1...",
  "cookies": [
    {"name": "sprLocalConnect", "value": "always", "path": "/ui"},
    {"name": "remoteDebugConf", "value": "3fa2c1...", "path": "/ui"}
  ],
  "webuiHostname": "space-qa6.sprinklr.com",
  "inspectorPort": 4040,
  "pid": 12345,
  "logFile": "/tmp/spr-live-qa-12345.log"
}
```

On failure (process died early, or the inspector never came up within 120s),
it prints `{"status": "ERROR", "reason": "...", "logFile": ..., "pid": ...}`
to stdout and exits 1 — always check the exit code, not just presence of a
`status` field.

`webuiHostname` defaults to `space-qa6.sprinklr.com` — the same
`ingress-domain` default set in `control/src/main/resources/application.yml`
and `ControlProperties.java`. Override with `QA6_WEBUI_HOSTNAME` if the target
QA6 deployment sets `INGRESS_DOMAIN` to something else.

Without `--json`, it prints the session ID and cookie string(s) as plain text
for a human to read:

```
Session ID: 3fa2c1...

Set these cookies on the QA6 WebUI hostname:
  sprLocalConnect=always                  Path=/ui
  remoteDebugConf=3fa2c1...  Path=/ui
```

## Step 3 — Baseline

Before driving any traffic, record the latest inspector request ID so the
later verification only matches new requests:

```
python3 .agents/skills/spr-local-connect-live-qa/scripts/wait_for_inspected_request.py --baseline
```

Save the printed integer as `BASELINE_ID`.

## Step 4 — Trigger the request (automated if possible, manual otherwise)

This step needs a browser with an authenticated QA6 WebUI session. Check
first whether a browser-automation tool is available in your current toolset
(e.g. a Playwright MCP server, chrome-devtools MCP, or similar — use
`ToolSearch` if unsure). Then branch:

**If a browser-automation tool is available:**

1. Launch or attach to a browser session via that tool.
2. Set each cookie from the Step 2 JSON's `cookies` array (`name`, `value`,
   `path`) on the `webuiHostname` from that same JSON.
3. Navigate to the QA6 page/URL that exercises the code change under test.
4. Trigger the specific action (click, form submit, API call) that exercises
   the change.
5. Continue straight to Step 5 — no human wait needed.

This removes the human dependency for *driving the browser only*. The SSH/
`sudo` prompt in Step 1 can still require a human regardless of whether
browser automation is available.

**If no browser-automation tool is available:** fall back to the manual flow.
Tell the human to:

1. Set the cookie(s) printed in Step 2 on the QA6 WebUI hostname
   (`webuiHostname` in the JSON output, or the plain-text cookie block),
   using the exact name/value/path shown.
2. Trigger the specific request that should exercise the local code change
   (e.g. load a page, submit an action, call an API).

Be explicit that this is a manual browser action, not something this skill
performs on its own.

## Step 5 — Verify

```
python3 .agents/skills/spr-local-connect-live-qa/scripts/wait_for_inspected_request.py \
  --after <BASELINE_ID> --path <expected-path> --method <expected-method> \
  --require-success --timeout 45
```

- Success: the script prints the matching inspector record as JSON and exits
  0. This confirms the request actually traveled QA6 → relay → tunnel client
  → local app and got a 2xx/3xx response.
- Timeout (exit 1): likely causes, in order of likelihood:
  - The cookie wasn't set, was set on the wrong hostname, or the human hasn't
    triggered the request yet.
  - `--path`/`--method` don't match what the browser actually sent.
  - The tunnel itself is unhealthy — check
    `/tmp/spr-local-connect-<user>-<port>-coordinator.log` and
    `-relay.log` on the remote host (paths are also echoed into
    `/tmp/spr-live-qa-<pid>.log` if a port-forward died) for
    `A QA6 Kubernetes port-forward stopped unexpectedly`.
  - If Step 4 ran via browser automation: confirm navigation/the triggering
    action actually finished (page load, click, or API call completed)
    before this 45s timeout elapsed — QA6 page load latency can outrun the
    default timeout. Re-run with a larger `--timeout` if the action is slow
    rather than assuming the tunnel is broken.

## Step 6 — Report and clean up

Report back to the user: matched request's `path`, `status`, and
`durationMs` from the JSON record in Step 5.

Then tear down the tunnel:

```
.agents/skills/spr-local-connect-live-qa/scripts/run_live_qa.sh --stop
```

Confirm cleanup succeeded: ports `8090` (coordinator) and `2222` (relay SSH)
should no longer be in use on the laptop.

## Do not modify

`scripts/qa6-local-connect.sh`, `scripts/wait_for_inspected_request.py`, and
`agents/openai.yaml` are stable interfaces this skill builds on — do not
change them as part of this flow.
