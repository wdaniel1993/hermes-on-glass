# hermes-channel-adapter

Hermes channel plugin for Rokid Glasses + phone bridge. Hosts an aiohttp
WebSocket endpoint that the phone connects to outbound; bridges text,
voice, and image traffic plus Hermes-initiated cron/run-completion/agent-nudge
pushes over a single socket.

See `openspec/changes/initial-mvp/proposal.md` and `openspec/changes/initial-mvp/design.md`
for the full architecture rationale (decisions D1–D14).

## Install

The adapter loads as a Hermes plugin from `~/.hermes/plugins/hermes-channel-adapter/`.
Symlink it from the repo so the runtime tracks your edits:

```bash
ln -s "$(pwd)/hermes-channel-adapter" ~/.hermes/plugins/hermes-channel-adapter
```

(Or copy the directory if you prefer; symlink is the dev workflow.)

Create the adapter config at `~/.hermes/plugins/hermes-channel-adapter/config.yaml`:

```yaml
listen_host: 0.0.0.0          # 100.x.x.x for Tailscale-only
listen_port: 8765
shared_secret: "<long-random-string>"
ws_path: /glasses             # optional, default /glasses
```

The shared secret is checked as `Authorization: Bearer <secret>` on the
WebSocket upgrade request. Voice provider settings live in
`~/.hermes/config.yaml` (`stt.provider`, `tts.provider`, `voice.auto_tts`)
and are inherited via the shared response pipeline — do not duplicate them
here.

Restart the gateway (`hermes gateway restart`). On startup the log should
contain:

```
[glasses] listening on 0.0.0.0:8765/glasses
```

`hermes status` lists `Glasses (plugin)` once registration succeeds.

## Smoke test

A websocat one-liner exercises the handshake without the full Hermes loop:

```bash
SECRET="<your shared secret>"
websocat -H="Authorization: Bearer $SECRET" ws://127.0.0.1:8765/glasses <<EOF
{"type":"client_hello","protocolVersion":1,"deviceId":"dev-1"}
EOF
```

A `server_welcome` frame should come back with the current session list.
For an end-to-end run with a real Hermes pipeline, drive the connection
from the phone-app or the test client in `tests/test_e2e_ws.py`.

## Tests

```bash
cd hermes-channel-adapter
pip install -e ".[test]"
pytest
```

Tests cover the frame parser, the tool-progress heuristic, and a full WS
round-trip with a stubbed message handler standing in for the gateway
runner.
