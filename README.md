# hermes-on-glass

Wearable client for Hermes Agent on Rokid Glasses. Android phone-app holds
an outbound WebSocket to a custom Hermes channel adapter running on the
user's Mac mini; the phone bridges glasses ↔ Hermes over Bluetooth via
Rokid CXR.

Agent-facing context lives in [AGENTS.md](./AGENTS.md). Product /
architecture lives in `openspec/changes/initial-mvp/`.

## Repository layout

```
hermes-on-glass/
├── shared/                       # Kotlin: protocol DTOs (both wires)
├── phone-app/                    # Android: WS↔Hermes + CXR-M bridge + chat UI
├── glasses-app/                  # Android: HUD on Rokid (CXR-S + CXR-L)
├── hermes-channel-adapter/       # Python: BasePlatformAdapter + WS server
└── openspec/                     # Proposals, designs, specs, tasks
```

## Build

JDK 17 (auto-downloaded via the foojay toolchain resolver if not local).
Android SDK 35, build-tools 36.0.0+. The Gradle wrapper bootstraps the
rest.

```bash
./gradlew :phone-app:assembleDebug :glasses-app:assembleDebug
```

`phone-app:bundleGlassesApk` runs as part of `phone-app:preBuild`,
copying the latest glasses APK into `phone-app/src/main/assets/glasses-app-release.apk`
so first-launch sideload via `CxrApi.startUploadApk(...)` has a payload.

Test:

```bash
./gradlew :shared:test :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest
cd hermes-channel-adapter && pytest
```

## §13.4 — Tailscale and LAN configurations

The phone connects outbound to `ws://<host>:<port>/glasses` over Tailscale
(no port forwarding, no public TLS). The shared secret is sent as
`Authorization: Bearer <secret>` on the WebSocket upgrade.

### Tailscale (recommended)

1. Install Tailscale on the Mac mini and the phone; sign in to the same
   tailnet.
2. Confirm MagicDNS is on (it is by default in modern installs); the
   Mac mini becomes addressable as `mac.<your-tailnet>.ts.net` or just
   `mac` inside the tailnet.
3. In the phone-app's Settings: set **Hermes WebSocket URL** to
   `ws://mac:8765/glasses` (use the hostname; do not pin an IP — Tailscale
   may rotate).
4. The adapter's `listen_host` should be `0.0.0.0` (or the tailnet
   interface) so it picks up the upstream connection.

### LAN fallback (no Tailscale, same Wi-Fi)

If the phone and Mac mini share a LAN and Tailscale is unavailable:

1. Find the Mac's LAN IP (`ipconfig getifaddr en0` on macOS).
2. Phone-app Settings → **Hermes WebSocket URL** = `ws://<lan-ip>:8765/glasses`.
3. Make sure the macOS firewall lets the adapter listen on that port.

The wire format and auth are identical between Tailscale and LAN — only
the URL differs. The protocol is plain `ws://`, not `wss://`; security
relies on the network being trusted (Tailscale's WireGuard tunnel or
private LAN).

## §13.5 — Rokid SDK credentials

The phone-app reads three values from `local.properties` at build time
(see `local.properties.template` for the keys). They land as
`BuildConfig` fields in the phone-app only.

```properties
rokid.clientId=<from-rokid-developer-console>
rokid.clientSecret=<from-rokid-developer-console>
rokid.accessKey=<from-rokid-developer-console>
```

How to obtain them:

1. Sign in at the Rokid developer console.
2. Create an application bound to the package name `dev.wallner.hermesonglass.phone`.
3. The console issues the three values; copy them into `local.properties`.
4. Rebuild the phone-app — `BuildConfig.ROKID_CLIENT_ID`,
   `BuildConfig.ROKID_CLIENT_SECRET`, and `BuildConfig.ROKID_ACCESS_KEY`
   are now baked into the APK.

`local.properties` is gitignored and must not be committed. Use
`local.properties.template` as the canonical reference; do not check in
real credentials.

The shared secret used by `CxrApi.connectBluetooth(...)` for SN
verification is `rokid.clientSecret`. Glasses-app needs the same Rokid AI
app installed at versionCode ≥ 100000 to authorise CXR-L.

## §13.6 — Channel adapter install

The Python adapter ships as a Hermes plugin and registers via
`ctx.register_platform(...)` (zero core Hermes changes). Install path:
`~/.hermes/plugins/hermes-channel-adapter/`.

```bash
# From the repo root, on the Mac mini that runs Hermes:
ln -s "$(pwd)/hermes-channel-adapter" ~/.hermes/plugins/hermes-channel-adapter
```

Symlinking lets edits flow through without copying.

Create the adapter config at
`~/.hermes/plugins/hermes-channel-adapter/config.yaml`:

```yaml
listen_host: 0.0.0.0           # 100.x.x.x on a Tailscale-only setup
listen_port: 8765
shared_secret: "<long random string — match the phone-app value>"
ws_path: /glasses              # optional; this is the default
```

Voice provider settings live in `~/.hermes/config.yaml`
(`stt.provider`, `tts.provider`, `voice.auto_tts`) and are inherited by
the adapter via the shared response pipeline — do not duplicate them in
the adapter config.

Restart the gateway so the plugin is picked up:

```bash
hermes gateway restart
```

The startup log should contain a line like:

```
[glasses] listening on 0.0.0.0:8765/glasses
```

`hermes status` lists `Glasses (plugin)` once registration succeeds.

### Smoke test

websocat one-liner that exercises the handshake without the full loop:

```bash
SECRET="<your shared secret>"
websocat -H="Authorization: Bearer $SECRET" ws://127.0.0.1:8765/glasses <<EOF
{"type":"client_hello","protocolVersion":1,"deviceId":"dev-1"}
EOF
```

A `server_welcome` frame should come back with the current session list.

## References

- **Hermes Agent** — [github.com/NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent). Channel adapter base class: `gateway/platforms/base.py:BasePlatformAdapter`. Voice tools: `tools/tts_tool.py`, `tools/transcription_tools.py`. Voice mode docs: [hermes-agent.nousresearch.com/docs/guides/use-voice-mode-with-hermes](https://hermes-agent.nousresearch.com/docs/guides/use-voice-mode-with-hermes).
- **Rokid SDK docs** — [github.com/buildwithfenna/rokid-docs](https://github.com/buildwithfenna/rokid-docs). Three SDKs: `cxr-m/` (mobile), `cxr-s/` (on-glasses bridge), `cxr-l/` (glasses-native media APIs — camera, audio, structured HUD push). Hardware: `yodaos/docs/hardware/{audio,display,thermal,power-performance}.md`.
- **Reference architecture** — [github.com/dweddepohl/clawsses](https://github.com/dweddepohl/clawsses). Same module shape (phone-app + glasses-app + shared protocol), same phone-as-bridge topology, same HUD UX. We are inspired by it but write fresh code; no fork, no vendoring (clawsses is AGPL-3.0).
