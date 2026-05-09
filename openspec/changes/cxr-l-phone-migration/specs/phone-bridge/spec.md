## REMOVED Requirements

### Requirement: Phone-app bridges to glasses via Rokid CXR-M with debug WebSocket fallback

CXR-M is decommissioned. Phone-app no longer pairs over Bluetooth itself; Hi Rokid AI app handles BLE on the phone side. The debug WebSocket fallback for emulators is unaffected and is preserved under the new transport requirement below.

### Requirement: Phone-app sideloads the glasses APK on first launch

`CxrApi.startUploadApk(...)` is a CXR-M API. The replacement requirement uses `CXRLink.appUploadAndInstall(...)` and is captured in the ADDED Requirements section.

## MODIFIED Requirements

### Requirement: Phone-app bridges to glasses via Rokid CXR-L (Hi Rokid AI app extension) with debug WebSocket fallback

The phone-app SHALL connect to glasses via Rokid CXR-L through the Hi Rokid AI app (`com.rokid.sprite.aiapp`) in production builds and SHALL fall back to a localhost WebSocket server on port 8081 when running in a debug emulator build.

#### Scenario: Production CXR-L connection

- **WHEN** Hi Rokid AI app is installed and authorized on the phone, glasses are paired through Hi Rokid, and `BuildConfig.DEBUG` is false
- **THEN** the phone-app constructs a single `CXRLink(context).configCXRSession(CxrDefs.CXRSession(CUSTOMAPP, "dev.wallner.hermesonglass.glasses"))`, calls `cxrLink.connect(token)` where `token` is the persisted `AuthResult.AuthSuccess.token`, and reaches `Connected` when both `ICXRLinkCbk.onCXRLConnected(true)` and `ICXRLinkCbk.onGlassBtConnected(true)` have fired

#### Scenario: Debug emulator fallback

- **WHEN** `BuildConfig.DEBUG` is true and the build runs on an emulator
- **THEN** the phone-app starts a WebSocket server on `127.0.0.1:8081` and accepts connections from the glasses-app debug client (unchanged from previous spec)

#### Scenario: Token revoked or expired

- **WHEN** `cxrLink.connect(token)` reports an authorization failure
- **THEN** the phone-app surfaces the auth-prompt UI and calls `AuthorizationHelper.requestAuthorization(activity, REQUEST_CODE)` to obtain a fresh token, persists it, and retries `connect` once

## ADDED Requirements

### Requirement: Phone-app requires Hi Rokid AI app installed and authorized

The phone-app SHALL on first launch (and on any subsequent launch when the persisted token is missing) check `AuthorizationHelper.INSTANCE.isRequiredRokidAppInstalled(activity)`; if false, the phone-app SHALL show an install prompt that invokes `SmartMarketLauncher.launchMarket(activity, "com.rokid.sprite.aiapp")`. If Hi Rokid is installed but no token is persisted, the phone-app SHALL show an authorize prompt that invokes `AuthorizationHelper.INSTANCE.requestAuthorization(activity, REQUEST_CODE)`. The returned token SHALL be persisted in `EncryptedSharedPreferences` next to the Hermes shared secret.

#### Scenario: Hi Rokid missing on first launch

- **WHEN** the phone-app launches and `AuthorizationHelper.isRequiredRokidAppInstalled(this)` is false
- **THEN** the phone-app shows a setup screen with an "Install Hi Rokid" action that calls `SmartMarketLauncher.launchMarket(this, "com.rokid.sprite.aiapp")`, and a "Recheck" action that re-evaluates the install state

#### Scenario: Hi Rokid installed but not authorized

- **WHEN** the phone-app launches with Hi Rokid installed and no persisted token
- **THEN** the phone-app shows an "Authorize" action that calls `AuthorizationHelper.requestAuthorization(this, REQUEST_CODE)`; on `onActivityResult`, the phone-app calls `AuthorizationHelper.parseAuthorizationResult(resultCode, data)` and persists `AuthResult.AuthSuccess.token` to encrypted prefs

#### Scenario: Authorization cancelled or failed

- **WHEN** the user cancels the authorization activity, or `parseAuthorizationResult` returns `AuthFail` / `AuthCancel`
- **THEN** the phone-app shows the failure state and surfaces a retry button; the WebSocket-to-Hermes path is not started until a token is persisted

### Requirement: Phone-app exchanges Caps frames with glasses via two unidirectional CXR-L channels

The phone-app SHALL send phone→glasses Caps frames via `cxrLink.sendCustomCmd(PHONE_TO_GLASSES_CHANNEL, caps.serialize())` where `PHONE_TO_GLASSES_CHANNEL = "hermes-on-glass"`. The phone-app SHALL receive glasses→phone Caps frames via `setCXRCustomCmdCbk { onCustomCmdResult(key, payload) }` filtered to `key == GLASSES_TO_PHONE_CHANNEL` where `GLASSES_TO_PHONE_CHANNEL = "hermes-on-glass-reply"`, decoding payloads via `Caps.fromBytes(payload)`.

#### Scenario: Outbound envelope to glasses

- **WHEN** the phone-app needs to deliver an envelope to the glasses
- **THEN** it serializes the envelope as JSON, wraps in a one-string `Caps`, and calls `cxrLink.sendCustomCmd("hermes-on-glass", caps.serialize())`

#### Scenario: Inbound envelope from glasses

- **WHEN** `onCustomCmdResult(key, payload)` is invoked with `key == "hermes-on-glass-reply"`
- **THEN** the phone-app decodes via `Caps.fromBytes(payload)`, reads the leading string, parses with `FrameParser.parseCapsEnvelope(json)`, and emits to `inbound: SharedFlow<CapsEnvelope>`

#### Scenario: Inbound channel filter

- **WHEN** `onCustomCmdResult` fires with a `key` other than `"hermes-on-glass-reply"`
- **THEN** the phone-app drops the payload and logs a debug-level message; it does not throw

### Requirement: Phone-app installs and launches the glasses-app via CXR-L appUploadAndInstall

The phone-app SHALL bundle `glasses-app-release.apk` as an asset (existing behavior). On first launch with the glasses paired via Hi Rokid, the phone-app SHALL copy the asset to `getExternalFilesDir(...)/glasses-app-release.apk`, then call `cxrLink.appUploadAndInstall(absolutePath, IGlassAppCbk)`. On `IGlassAppCbk.onInstallAppResult(true)`, the phone-app SHALL call `cxrLink.appStart("dev.wallner.hermesonglass.glasses/.MainActivity", IGlassAppCbk)`.

#### Scenario: First-launch install

- **WHEN** `cxrLink.appIsInstalled(...)` reports the glasses-app missing or below the bundled version
- **THEN** the phone-app copies the bundled APK to app-private external storage and calls `cxrLink.appUploadAndInstall(path, callback)`; on success, `cxrLink.appStart(...)` is invoked

#### Scenario: Already installed

- **WHEN** `cxrLink.appIsInstalled(...)` reports the glasses-app already installed at the bundled version
- **THEN** the phone-app skips upload and proceeds to CXR-L `connect`-and-establish flow
