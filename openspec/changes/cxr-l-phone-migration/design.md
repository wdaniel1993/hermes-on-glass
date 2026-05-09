## Context

Rokid's public CXR-M SDK documentation is being decommissioned on **2026-05-15** (today: 2026-05-09). The current phone-app uses CXR-M for three concerns — control-plane Caps messaging (`CxrCapsLink`), BLE pair/reconnect (`RokidSdkManager`), and Wi-Fi P2P APK sideload (`ApkSideloader`). After the cutoff, the phone build can break (artifacts may be pulled, docs gone), and we're already seeing a startup crash because `client-m`'s native libs reference `com.rokid.cxr.ReplyImpl` from the sibling `cxr-service-bridge` artifact and Rokid's maven host is intermittently 502.

CXR-L (phone-side, Android/iOS) is Rokid's public-supported successor — phone-app extends the **Hi Rokid AI app** rather than bridging directly to glasses. Two SDK sample projects (`cxrlsample101.zip` and `sSDKSampleforCXR.zip`, supplied by user 2026-05-09) confirm the API surface and the wire compatibility with our existing glasses-app.

### CXR-L surface (verified against the supplied samples)

- **Artifact:** `com.rokid.cxr:client-l:1.0.1` from `https://maven.rokid.com/repository/maven-public/`. Same artifact the glasses-app already uses.
- **Session model:** `CXRLink(context).configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, glassesPackageName))`. Alternative `CUSTOMVIEW` session type renders JSON-pushed UI on the glasses HUD without a glasses APK — out of scope for this migration.
- **Connection lifecycle:** `setCXRLinkCbk(ICXRLinkCbk)` exposes `onCXRLConnected(Boolean)` (CXR transport up) and `onGlassBtConnected(Boolean)` (BLE up — Hi Rokid manages it). Both must be true to consider the link ready.
- **Wire:** `cxrLink.sendCustomCmd(channel: String, payload: ByteArray)` and `setCXRCustomCmdCbk { onCustomCmdResult(key: String?, payload: ByteArray?) }`. Caps payloads serialize to/from bytes via `caps.serialize()` / `Caps.fromBytes(bytes)`. The two methods are separate primitives, so directionality requires two channel names.
- **Auth:** `AuthorizationHelper.INSTANCE` from `com.rokid.sprite.aiapp.externalapp.auth` — `isRequiredRokidAppInstalled(activity): Boolean`, `requestAuthorization(activity, requestCode)`, `parseAuthorizationResult(resultCode, data): AuthResult` returning `AuthSuccess(token) | AuthFail | AuthCancel`. Token feeds `cxrLink.connect(token)`.
- **APK install/launch:** `cxrLink.appUploadAndInstall(absolutePath, IGlassAppCbk)`, `cxrLink.appStart("<pkg>/<activity>", IGlassAppCbk)`, `cxrLink.appStop`, `cxrLink.appUninstall`, `cxrLink.appIsInstalled`. The SDK reads the APK from a path the SDK can access — sample tries `getExternalFilesDir(DCIM/Rokid)`, `filesDir`, `/sdcard/DCIM/Rokid/`. App-private `filesDir` works.
- **Hi Rokid market:** `SmartMarketLauncher.launchMarket(activity, "com.rokid.sprite.aiapp")` opens the install path when Hi Rokid is missing on the phone.

### Glasses-side (CXR-S) compatibility

- The current glasses-app subscribes to one topic (`"hermes-on-glass"`) and sends/receives bidirectionally. With CXR-L on the phone, `sendCustomCmd(channel, ...)` and `setCXRCustomCmdCbk { onCustomCmdResult(key, ...) }` are split primitives — same channel name on both ends would require self-suppression that we don't want to depend on. Glasses must subscribe on one channel and send on another.
- The glasses sample (`sSDKSampleforCXR`) confirms the symmetric pattern: `cxrBridge.subscribe(clientKey, msgCallback)` for inbound from phone, `cxrBridge.sendMessage(cmdKey, caps)` for outbound to phone. Channel names are arbitrary strings.

## Goals / Non-Goals

**Goals:**

- Phone-app builds and runs with `client-m` and `cxr-service-bridge` removed; `client-l:1.0.1` is the only Rokid dependency on the phone.
- All existing end-to-end flows (text, voice, camera, wake-on-message, reconnect) continue to work on a Pixel 9a + Rokid Glasses pair with Hi Rokid AI app authorized.
- Glasses-app, `shared/` module, and Hermes channel adapter are byte-identical except for two channel-name constants in `PhoneConnectionService.kt`.
- All existing JVM unit tests pass without modification.

**Non-Goals:**

- No move to `CXRSessionType.CUSTOMVIEW`. (Viable future simplification — JSON-driven HUD without the glasses APK — but a separate proposal.)
- No iOS, no on-device LLM, no fork or vendoring of clawsses code. (Same non-goals as `initial-mvp`.)
- No update to `client-l`'s pinned version (`1.0.1` stays).
- No vendoring of CXR-M AARs as a fallback. The migration lands directly; no tactical interim patch on the current crash.

## Decisions

### D15: Phone uses CXR-L via `CXRLink`; supersedes D6 of `initial-mvp`

**Choice:** Phone-app constructs a single `CXRLink(context)` instance configured as `CXRSessionType.CUSTOMAPP` with `applicationId = "dev.wallner.hermesonglass.glasses"`. The link is held at Application scope (mirrors `CXRLSampleApplication.sharedCxrLink` in the sample) so it survives Activity destruction and continues delivering callbacks while the phone screen is off.

**Connection state** is `onCXRLConnected && onGlassBtConnected`. Either becoming false flips the existing `CapsLink.connected: StateFlow<Boolean>` to false. The existing `BluetoothStatusEvent` sealed type used by `GlassesConnectionManager` is mapped from `onGlassBtConnected` only.

**Rationale:** CXR-L is Rokid's documented public-supported phone SDK path. Hi Rokid handles BLE, pairing persistence, and account management for us — large amounts of phone code (`RokidSdkManager`, `RokidSnCipher`, `EncryptedSnStore`, `WifiP2pStaleStateWorkaround`) become unnecessary.

**Alternatives considered:**

- *Keep CXR-M, vendor the AARs.* Rejected — CXR-M is decommissioned, no docs, no upgrades. Long-term liability.
- *Move to `CXRSessionType.CUSTOMVIEW` (no glasses APK).* Out of scope. The glasses-app's Compose HUD is richer than the JSON-driven `selfView` schema (`TextView`, `ImageView`, `LinearLayout`, `Lottie`) supports, and migrating the HUD is a separate concern.

### D16: Hi Rokid AI app on the phone is a hard runtime prerequisite; supersedes parts of D13 of `initial-mvp`

**Choice:** First-run flow checks `AuthorizationHelper.isRequiredRokidAppInstalled(activity)`. If missing, surfaces an install prompt that calls `SmartMarketLauncher.launchMarket(activity, "com.rokid.sprite.aiapp")`. If installed but unauthorized, shows an "authorize" button that calls `AuthorizationHelper.requestAuthorization(activity, REQUEST_CODE)`; the result lands in `MainActivity.onActivityResult` and `AuthorizationHelper.parseAuthorizationResult(resultCode, data)` returns `AuthResult.AuthSuccess(token)`.

The token is stored in `EncryptedSharedPreferences` next to the Hermes shared secret (`HermesPrefs`). We pass it to `cxrLink.connect(token)` on every app launch. Token TTL is unknown (OQ-1) — if `connect(token)` returns a not-authorized error, the app re-prompts.

**Rationale:** The Hi Rokid app *is* the BLE manager. There is no CXR-L without it. The user's setup burden is one Play Store install + one authorization grant.

**Alternatives considered:** None — without Hi Rokid we cannot use CXR-L.

### D17: Two-channel asymmetric Caps wire; supersedes part of D6's "single bidirectional topic"

**Choice:** Two channel names:

```
PHONE_TO_GLASSES_CHANNEL = "hermes-on-glass"
GLASSES_TO_PHONE_CHANNEL = "hermes-on-glass-reply"
```

Phone calls `cxrLink.sendCustomCmd(PHONE_TO_GLASSES_CHANNEL, caps.serialize())`. Phone's `setCXRCustomCmdCbk { onCustomCmdResult(key, payload) }` filters to `key == GLASSES_TO_PHONE_CHANNEL` and decodes via `Caps.fromBytes(payload)`. Glasses subscribes via `cxrBridge.subscribe(PHONE_TO_GLASSES_CHANNEL, ...)` and sends via `cxrBridge.sendMessage(GLASSES_TO_PHONE_CHANNEL, caps)`.

**Rationale:** CXR-L's `sendCustomCmd` and `setCXRCustomCmdCbk` are separate primitives. Same-channel bidirectional traffic would either need self-suppression in the SDK (undocumented) or risk message loops. Two channels make direction explicit and trivially mirror the sample's `rk_custom_client` / `rk_custom_key` pattern.

**Alternatives considered:** Single bidirectional channel with self-filtering by sender ID embedded in `Caps` — rejected, more code on both ends, opaque debugging.

## Open Questions

- **OQ-1 — Token TTL and refresh.** The cxrl-sample stores the token from `AuthResult.AuthSuccess` and uses it indefinitely; no refresh path is shown. We don't know whether the token expires. Resolution: re-authorize after a long server-side wait, or contact `Glasses.BD@rokid.com`. Workaround in code: if `connect(token)` fails with auth-related error, prompt re-authorization and retry once.
- **OQ-2 — Channel namespace conflicts.** The sample uses `"rk_custom_client"` / `"rk_custom_key"`. Reserved names or namespacing requirements (e.g., must include the package name) are undocumented. Resolution: probe with our chosen names; fall back to fully-qualified `"dev.wallner.hermesonglass:phone→glasses"` if collisions or rejections appear on hardware.
- **OQ-3 — APK install path access.** The sample tries `getExternalFilesDir(DCIM/Rokid)`, `filesDir`, `/sdcard/DCIM/Rokid/`, and `Environment.getExternalStoragePublicDirectory(...)`. `filesDir` is the safest first try — fully app-private, no permission. If the SDK rejects it, fall back to `getExternalFilesDir(DCIM/Rokid)`.
- **OQ-4 — Caps message size limits over CXR-L.** Currently bounded by `CapsFrameSizePolicy` (in-tree). The CXR-L wire may have a different MTU. Probe at runtime; the policy is already there to add a guard.
- **OQ-5 — Background operation.** Our `GlassesConnectionService` is a foreground service. We need `cxrLink` to keep delivering `setCXRCustomCmdCbk` callbacks while the phone screen is off. The sample's `CXRLSampleApplication.sharedCxrLink` model suggests Application-scoped lifetime is fine. Verify on hardware during step 3 of `tasks.md`.

## Risks

- **Decommissioning timing.** If Rokid pulls `client-m` / `cxr-service-bridge` from maven *before* this change is merged, the phone-app build breaks even with the existing crash workaround. Mitigated by removing those deps as the *first* step of implementation — once they're gone we no longer need them to be downloadable.
- **Hi Rokid as a single point of failure.** If Hi Rokid AI app is unavailable on a user's region or device class, the phone-app cannot function. Documented in proposal.md and surfaced clearly in first-run UI.
- **Two-channel rename mismatch between phone and glasses.** Forgetting to update glasses constants leaves the link silently no-op. Mitigated by keeping the channel names in `shared/` if practical, otherwise by symmetric tests in `PhoneToGlassesBridgeTest` that exercise both directions.
