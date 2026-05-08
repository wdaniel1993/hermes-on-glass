# AGENTS.md

Orientation for AI agents. Canonical product/architecture content lives under `openspec/`; this file is a thin map.

## What this is

Wearable client for [Hermes Agent](https://github.com/NousResearch/hermes-agent) on [Rokid Glasses](https://global.rokid.com/pages/rokid-glasses). Hermes runs on the user's Mac mini. We ship a **custom Hermes channel adapter** (Python, subclasses `gateway/platforms/base.py:BasePlatformAdapter`) that hosts a WebSocket endpoint; the phone connects outbound and bridges glasses (Bluetooth/CXR) ↔ Hermes (Tailscale-only WS, Bearer auth). Inspired by [`dweddepohl/clawsses`](https://github.com/dweddepohl/clawsses) — clean-room, no copied code.

## External references

- **Hermes Agent** — [repo](https://github.com/NousResearch/hermes-agent). Adapter base: `gateway/platforms/base.py`. Voice pipeline (shared across all channels): `tools/tts_tool.py`, `tools/transcription_tools.py`. [Voice-mode guide](https://hermes-agent.nousresearch.com/docs/guides/use-voice-mode-with-hermes).
- **Rokid docs** — [`buildwithfenna/rokid-docs`](https://github.com/buildwithfenna/rokid-docs). Three SDK trees: `cxr-m/` (mobile), `cxr-s/` (on-glasses bridge to phone), `cxr-l/` (glasses-native media APIs: `takePhoto`, `startAudioStream`, `openCustomView`). Hardware notes: `yodaos/docs/hardware/`. APK sideload via `CxrApi.startUploadApk(...)` to port 8848 (documented).
- **clawsses** — [repo](https://github.com/dweddepohl/clawsses). Reference for module shape, phone-as-bridge topology, HUD UX. **Do not** fork or vendor.

## Read first

- `openspec/changes/initial-mvp/proposal.md` — what & why
- `openspec/changes/initial-mvp/design.md` — how (Decisions D1–D10, risks, open questions)
- `openspec/changes/initial-mvp/specs/*/spec.md` — **success criteria** (WHEN/THEN scenarios)
- `openspec/changes/initial-mvp/tasks.md` — implementation checklist (steps, not criteria)

Don't duplicate their content here, in READMEs, or in code comments.

## Workflow

```
/opsx:explore  → think (no code)     /opsx:apply    → implement against tasks.md
/opsx:propose  → proposal+design+    /opsx:archive  → archive when shipped
                 specs+tasks
```

New behavior or scope shifts require a change proposal first. Bug fixes inside an existing capability are fine without one.

**Tasks are steps; specs are success criteria.** A task is not done until the relevant scenarios in `specs/*/spec.md` are satisfiable. For protocol, parsers, and state machines, write the WHEN/THEN scenarios as unit tests first, then make them pass.

## Tech stack

- **Android (phone):** Kotlin, Jetpack Compose, OkHttp + okhttp-ws (WebSocket client), Gson, Timber, Rokid CXR-M SDK. **No** `SpeechRecognizer`, **no** Edge/ElevenLabs SDK on the phone — voice is server-side via Hermes.
- **Glasses (Rokid AOSP):** Compose, Rokid CXR-S (bridge to phone) + CXR-L (camera/audio/structured view: `takePhoto`, `startAudioStream`, `openCustomView`). Local mic + speaker used directly via Android `MediaRecorder` / `MediaPlayer`.
- **Phone↔Hermes:** WebSocket on `ws://mac:<port>/glasses` over Tailscale, `Authorization: Bearer <SHARED_SECRET>` on the upgrade. No REST, no SSE.
- **Channel adapter (core, not deferred):** Python, installed under `~/.hermes/plugins/hermes-channel-adapter/`. `aiohttp` WS server, subclass of `BasePlatformAdapter`. Voice handling delegates to Hermes' shared `tools/tts_tool.py` + `tools/transcription_tools.py`.
- **Build:** Gradle (Kotlin DSL); Maven Rokid (`https://maven.rokid.com/repository/maven-public/`); `pyproject.toml` for the adapter.
- **Build commands:** TBD until Gradle is scaffolded; glasses APK is auto-bundled into the phone APK assets and sideloaded via `CxrApi.startUploadApk(...)` (Wi-Fi P2P, port 8848) on first launch.

## House rules

- **Architectural decisions are load-bearing.** See `design.md` for the current decision set: full Hermes channel (no REST), WebSocket transport, server-side voice (Telegram pattern, no phone-local STT/TTS), Tailscale-only with Bearer-on-upgrade, Hermes-owned sessions, phone is a participating chat client (not transparent bridge), 256 KB JPEG cap before encoding. Plus the proposal's Non-goals (no clawsses fork, no Discord/Telegram parity, no public TLS, no phone-side voice). Don't override without a new proposal.
- **Surgical changes.** Touch only what the task requires. Don't reformat files you weren't asked to touch. Don't refactor adjacent code that isn't broken. Don't "improve" things outside scope.
- **Simplicity first.** No abstractions for single-use code. No speculative features. Three modules, simple data classes, plain functions until something demands more.
- **Surface confusion, don't hide it.** State assumptions explicitly. If something is unclear, stop and ask — don't guess and run.

## Repository layout (target)

```
hermes-on-glass/
├── shared/                    # Kotlin: protocol DTOs (both wires)
├── phone-app/                 # Android: WS↔Hermes + CXR-M bridge + chat UI
├── glasses-app/               # Android: HUD on Rokid (CXR-S + CXR-L)
├── hermes-channel-adapter/    # Python: BasePlatformAdapter + WS server (core)
└── openspec/                  # Proposals, designs, specs, tasks
```

Modules don't exist yet — scaffolded by `tasks.md`.

## Local setup

- `local.properties` (gitignored): `rokid.clientId`, `rokid.clientSecret`, `rokid.accessKey`.
- `~/.hermes/plugins/hermes-channel-adapter/config.yaml`: `listen_host`, `listen_port`, `shared_secret`.
- Hermes voice config (already on Mac mini): `~/.hermes/config.yaml` `tts.provider`, `stt.provider`, `voice.auto_tts`.
- Tailscale up on both ends; MagicDNS resolves `mac` to the Mac mini.
- Phone stores the shared secret in EncryptedSharedPreferences. Never commit it.

## Conventions

- Compose only (no XML layouts). `MutableStateFlow` + sealed classes for state.
- Gson `@SerializedName` on every wire DTO. Timber on Android, `logging` in Python — no `println`.
- Default to no comments; add one only when the *why* is non-obvious.
- Unit-test parsers and state machines. UI/HUD verified manually on hardware.

## When unsure

Ask the user before guessing. Check `tasks.md` before inventing scope.
