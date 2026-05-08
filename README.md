# hermes-on-glass

Wearable client for Hermes Agent on Rokid Glasses. Android phone-app holds an outbound WebSocket to a custom Hermes channel adapter running on the user's Mac mini; the phone bridges glasses ↔ Hermes over Bluetooth via Rokid CXR.

Agent-facing context lives in [AGENTS.md](./AGENTS.md). Product/architecture lives in `openspec/`.

## References

- **Hermes Agent** — [github.com/NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent). Channel adapter base class: `gateway/platforms/base.py:BasePlatformAdapter`. Voice tools: `tools/tts_tool.py`, `tools/transcription_tools.py`. Voice mode docs: [hermes-agent.nousresearch.com/docs/guides/use-voice-mode-with-hermes](https://hermes-agent.nousresearch.com/docs/guides/use-voice-mode-with-hermes).
- **Rokid SDK docs** — [github.com/buildwithfenna/rokid-docs](https://github.com/buildwithfenna/rokid-docs). Three SDKs: `cxr-m/` (mobile), `cxr-s/` (on-glasses bridge), `cxr-l/` (glasses-native media APIs — camera, audio, structured HUD push). Hardware: `yodaos/docs/hardware/{audio,display,thermal,power-performance}.md`.
- **Reference architecture** — [github.com/dweddepohl/clawsses](https://github.com/dweddepohl/clawsses). Same module shape (phone-app + glasses-app + shared protocol), same phone-as-bridge topology, same HUD UX. We are inspired by it but write fresh code; no fork, no vendoring.
