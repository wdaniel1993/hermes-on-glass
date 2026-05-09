## Why

Rokid is decommissioning the public CXR-M SDK on **2026-05-15**. The phone-app currently depends on `com.rokid.cxr:client-m:1.2.1` plus `com.rokid.cxr:cxr-service-bridge:1.0` to (a) carry control-plane Caps frames between phone and glasses, (b) drive Bluetooth pair/reconnect, and (c) sideload the bundled glasses APK over Wi-Fi P2P. After the cutoff the artifacts on `https://maven.rokid.com/repository/maven-public/` may stop being reachable, the docs are already being pulled, and a startup crash already surfaces today: `ClassNotFoundException: com.rokid.cxr.ReplyImpl` from `CXRServiceBridge.<clinit>` because `client-m`'s native libs reference a class that lives in `cxr-service-bridge` and the maven host is intermittently 502.

Rokid positions **CXR-L** (phone-side, Android/iOS) as the public-supported successor — it extends the **Hi Rokid AI app** rather than bridging directly to the glasses. Two SDK sample projects from Rokid (`cxrlsample101.zip` and `sSDKSampleforCXR.zip`, source: vendor) confirm:

- Phone depends on `com.rokid.cxr:client-l:1.0.1` — the *same* artifact the glasses-app already uses.
- Phone uses `com.rokid.cxr.link.CXRLink` to register a `CUSTOMAPP` session against our glasses-app package and exchange Caps payloads via `sendCustomCmd(channel, capsBytes)` and `setCXRCustomCmdCbk { onCustomCmdResult(key, payload) }`.
- Auth uses `AuthorizationHelper` (already used by glasses-app's `CxrLBootstrap`) — Hi Rokid AI app must be installed on the phone, the user grants authorization, the SDK returns a token that drives `cxrLink.connect(token)`.
- Hi Rokid handles BLE pairing for us. We do not pair, we do not store SNs, we do not cipher AES.
- `cxrLink.appUploadAndInstall(absolutePath, IGlassAppCbk)` replaces `CxrApi.startUploadApk(...)`; `cxrLink.appStart(activityRef, callback)` launches the glasses-app.

The wire format is **`Caps`**, identical to today. The glasses-app stays on `CXRServiceBridge` (still bundled in `client-l:1.0.1`); only the *channel naming* changes — CXR-L's `sendCustomCmd(channel, ...)` and `setCXRCustomCmdCbk { onCustomCmdResult(key, ...) }` are separate primitives, so we must use two unidirectional channel names (`hermes-on-glass` for phone→glasses, `hermes-on-glass-reply` for glasses→phone) instead of today's single bidirectional topic.

## What Changes

- **Phone transport switches from `CXRServiceBridge` (CXR-M) to `CXRLink` (CXR-L).** Phone routes Caps frames via `cxrLink.sendCustomCmd` / `setCXRCustomCmdCbk` against the Hi Rokid AI app on the phone. Hi Rokid relays to the glasses.
- **Hi Rokid AI app on the phone becomes a hard runtime prerequisite.** First-run flow checks `AuthorizationHelper.isRequiredRokidAppInstalled(activity)`; if missing, surfaces `SmartMarketLauncher.launchMarket(activity, "com.rokid.sprite.aiapp")`. If installed but unauthorized, calls `AuthorizationHelper.requestAuthorization(activity, REQUEST_CODE)` and stores the returned token in `EncryptedSharedPreferences`.
- **BLE pair/reconnect logic on the phone is removed.** Hi Rokid handles BLE; we observe state via `ICXRLinkCbk.onGlassBtConnected`. `RokidSdkManager.kt` shrinks to a thin status adapter; `RokidSnCipher.kt` and `EncryptedSnStore.kt` (and tests) are deleted; Wi-Fi P2P workaround is deleted.
- **APK sideload pivots to `cxrLink.appUploadAndInstall`.** The bundled `glasses-app-release.apk` asset is copied to `getExternalFilesDir(...)` at runtime, then handed to the SDK. After install the phone calls `cxrLink.appStart("dev.wallner.hermesonglass.glasses/.MainActivity", callback)`. No more Wi-Fi P2P, no more `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` / `NEARBY_WIFI_DEVICES` permissions.
- **Glasses-app PhoneConnectionService switches from one bidirectional topic to two unidirectional channels.** Subscribes `"hermes-on-glass"` (inbound from phone), sends on `"hermes-on-glass-reply"` (outbound to phone). Two-line constant rename; payload format and DTOs unchanged.
- **Removes** `com.rokid.cxr:client-m` and `com.rokid.cxr:cxr-service-bridge` from the phone-app build. Replaces with `com.rokid.cxr:client-l:1.0.1` (already declared in `gradle/libs.versions.toml`).

## Capabilities

### Modified Capabilities

- `phone-bridge`: phone-side transport pivots from CXR-M to CXR-L. Auth flow added (Hi Rokid presence + token). BLE/SN pairing logic removed (Hi Rokid owns it). APK sideload reworked. Wake-on-message, WebSocket-to-Hermes path, and chat UI are unchanged.

### Untouched Capabilities

- `glasses-hud`: HUD, voice loop, photo path, gestures unchanged.
- `hermes-client-protocol`: `Caps` envelope DTOs and `FrameParser` unchanged. Wire format identical.
- `glasses-channel-plugin`: Python adapter on the Mac mini unchanged.

## Impact

- **External dependencies (Android):** Removes `com.rokid.cxr:client-m:1.2.1` and `com.rokid.cxr:cxr-service-bridge:1.0`. Adds `com.rokid.cxr:client-l:1.0.1` to phone-app (already a glasses-app dep). Net dependency footprint decreases.
- **Android permissions:** Removes `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES`. Bluetooth permissions retained for OS-level pairing-status reads.
- **Hi Rokid AI app:** New runtime prerequisite on the phone. Documented in README and surfaced via in-app install prompt.
- **Tests:** No test code edits expected. All phone-app unit tests mock `CapsLink`, `RokidSdkClient`, `WsConnection`, `ApkSideloader` — never the underlying SDK types. Verified during planning.
- **Supersedes**: `openspec/changes/initial-mvp/design.md` D6 (CXR-M transport on phone) and D13 (`CxrApi.startUploadApk`). Adds D15 (CXR-L on phone), D16 (Hi Rokid as hard prerequisite), D17 (two-channel asymmetric Caps wire).
- **Out of scope:** No glasses-app HUD/voice/camera changes. No `shared/` DTO changes. No Hermes-side changes. No move to `CXRSessionType.CUSTOMVIEW` (JSON-pushed HUD without our glasses APK) — viable as a future simplification but not part of this migration.
