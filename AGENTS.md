# AGENTS.md

Orientation for AI agents. Canonical product/architecture content lives under `openspec/`; this file is a thin map.

## What this is

Wearable client for [Hermes Agent](https://github.com/NousResearch/hermes-agent) on [Rokid Glasses](https://global.rokid.com/pages/rokid-glasses). Hermes runs on the user's Mac mini; an Android phone bridges glasses (Bluetooth/CXR-M) ↔ Hermes REST (Tailscale + Bearer). Inspired by [`dweddepohl/clawsses`](https://github.com/dweddepohl/clawsses) — clean-room, no copied code.

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

- **Android:** Kotlin, Jetpack Compose, OkHttp + okhttp-sse, Gson, Timber, Android `SpeechRecognizer`. Edge TTS / ElevenLabs for output.
- **Glasses (Rokid AOSP):** Compose, CameraX, Rokid CXR-S SDK.
- **Phone↔Hermes:** HTTP+SSE on `http://mac:8642` over Tailscale, `Authorization: Bearer <API_SERVER_KEY>`.
- **Channel plugin (MVP+1):** Python, installed under `~/.hermes/plugins/glasses-channel/`.
- **Build:** Gradle (Kotlin DSL); Maven Rokid (`https://maven.rokid.com/repository/maven-public/`); `pyproject.toml` for the plugin.
- **Build commands:** TBD until Gradle is scaffolded (tasks 2.1–2.6); glasses APK is auto-bundled into the phone APK assets and sideloaded via Rokid WiFi P2P on first launch.

## House rules

- **Architectural decisions are load-bearing.** See `design.md` Decisions D1 (Responses API + named `conversation`), D3 (phone-local voice — no audio-through-Hermes), D4 (Tailscale + Bearer, no public exposure), D8 (256 KB JPEG cap before base64). Plus the proposal's Non-goals (no clawsses fork, no Discord/Telegram parity, no public TLS). Don't override without a new proposal.
- **Surgical changes.** Touch only what the task requires. Don't reformat files you weren't asked to touch. Don't refactor adjacent code that isn't broken. Don't "improve" things outside scope.
- **Simplicity first.** No abstractions for single-use code. No speculative features. Three modules, simple data classes, plain functions until something demands more.
- **Surface confusion, don't hide it.** State assumptions explicitly. If something is unclear, stop and ask — don't guess and run.

## Repository layout (target)

```
hermes-on-glass/
├── shared/                    # Kotlin: protocol DTOs (both wires)
├── phone-app/                 # Android: bridge + voice + Hermes client
├── glasses-app/               # Android: HUD on Rokid
├── glasses-channel-plugin/    # Python (MVP+1)
└── openspec/                  # Proposals, designs, specs, tasks
```

Modules don't exist yet — scaffolded in tasks 2.x and 11.x.

## Local setup

- `local.properties` (gitignored): `rokid.clientId`, `rokid.clientSecret`, `rokid.accessKey`.
- `~/.hermes/.env` on Mac mini: `API_SERVER_ENABLED=true`, `API_SERVER_KEY=<strong-random>`.
- Tailscale up on both ends; MagicDNS resolves `mac` to the Mac mini.
- Phone stores `API_SERVER_KEY` in EncryptedSharedPreferences. Never commit it.

## Conventions

- Compose only (no XML layouts). `MutableStateFlow` + sealed classes for state.
- Gson `@SerializedName` on every wire DTO. Timber on Android, `logging` in Python — no `println`.
- Default to no comments; add one only when the *why* is non-obvious.
- Unit-test parsers and state machines. UI/HUD verified manually on hardware.

## When unsure

Ask the user before guessing. Check `tasks.md` before inventing scope.
