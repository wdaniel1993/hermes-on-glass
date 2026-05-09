# Tasks — cxr-l-phone-migration

Implementation order. Each task should leave the build green so we can pause/resume.

## 1. Gradle deps

- [ ] 1.1 Remove `rokidCxrM`, `rokidCxrServiceBridge` from `gradle/libs.versions.toml` `[versions]` and the corresponding entries from `[libraries]`
- [ ] 1.2 Replace `implementation(libs.rokid.cxr.m)` + `implementation(libs.rokid.cxr.service.bridge)` in `phone-app/build.gradle.kts` with `implementation(libs.rokid.cxr.l)`
- [ ] 1.3 Verify `./gradlew :phone-app:dependencies | grep cxr` shows only `client-l` for the phone

## 2. CxrCapsLink rewrite

- [ ] 2.1 Rewrite `phone-app/src/main/kotlin/dev/wallner/hermesonglass/phone/data/cxr/CxrCapsLink.kt` to use `CXRLink(context)` instead of `CXRServiceBridge()`
- [ ] 2.2 Add `connect(token: String)` method on the `CapsLink` interface OR pass token via constructor / setter; wire it into the link's `connect(token)` call
- [ ] 2.3 Channel constants: `PHONE_TO_GLASSES_CHANNEL = "hermes-on-glass"`, `GLASSES_TO_PHONE_CHANNEL = "hermes-on-glass-reply"`
- [ ] 2.4 Outbound: `cxrLink.sendCustomCmd(PHONE_TO_GLASSES_CHANNEL, caps.serialize())`
- [ ] 2.5 Inbound: `setCXRCustomCmdCbk { onCustomCmdResult(key, payload) -> if (key == GLASSES_TO_PHONE_CHANNEL) Caps.fromBytes(payload) }`
- [ ] 2.6 Connection state: `connected.value = onCXRLConnected && onGlassBtConnected`
- [ ] 2.7 Run `:phone-app:compileDebugKotlin` to confirm the type swap is internally consistent

## 3. Auth flow + token storage

- [ ] 3.1 Add Rokid auth token slot to `phone-app/src/main/kotlin/.../data/HermesPrefs.kt` — `EncryptedSharedPreferences`, alongside the Hermes shared secret
- [ ] 3.2 Add a `RokidAuthGate` Composable in `MainActivity` that runs before the chat/settings flow: reads `AuthorizationHelper.isRequiredRokidAppInstalled(this)`; renders install prompt + `SmartMarketLauncher.launchMarket(this, "com.rokid.sprite.aiapp")` action when missing; renders authorize prompt + `AuthorizationHelper.requestAuthorization(this, REQUEST_CODE)` action when no token persisted
- [ ] 3.3 Wire `MainActivity.onActivityResult(requestCode, resultCode, data)`: on `REQUEST_CODE` match, call `AuthorizationHelper.parseAuthorizationResult(resultCode, data)`; on `AuthSuccess`, persist token and re-bootstrap the connection
- [ ] 3.4 Surface auth state in the existing settings screen (status line: "Hi Rokid: installed/missing", "Authorized: yes/no")

## 4. RokidSdkManager shrink

- [ ] 4.1 Replace BLE-pairing logic in `phone-app/src/main/kotlin/.../data/rokid/RokidSdkManager.kt` with a thin `ICXRLinkCbk` adapter that maps `onGlassBtConnected(Boolean)` to `BluetoothStatusEvent.Connected` / `Disconnected`
- [ ] 4.2 Confirm `RokidSdkClient` interface is unchanged
- [ ] 4.3 Confirm `GlassesConnectionManagerTest.kt` still passes via its `FakeSdk`

## 5. ApkSideloader rewrite

- [ ] 5.1 Rewrite `phone-app/src/main/kotlin/.../data/rokid/ApkSideloader.kt`: copy `assets/glasses-app-release.apk` → `getExternalFilesDir(null)/glasses-app-release.apk` (or `filesDir` if SDK accepts) at runtime
- [ ] 5.2 Call `cxrLink.appUploadAndInstall(absolutePath, IGlassAppCbk)`; map `IGlassAppCbk` callbacks to existing `SideloadEvent` sealed type
- [ ] 5.3 On `onInstallAppResult(true)`, call `cxrLink.appStart("dev.wallner.hermesonglass.glasses/.MainActivity", IGlassAppCbk)`
- [ ] 5.4 Remove no-longer-relevant code paths: `stopUploadApk`, Wi-Fi P2P retries, etc.

## 6. Glasses-app two-channel update

- [ ] 6.1 In `glasses-app/src/main/kotlin/.../PhoneConnectionService.kt`: add `PHONE_TO_GLASSES_CHANNEL = "hermes-on-glass"` and `GLASSES_TO_PHONE_CHANNEL = "hermes-on-glass-reply"` constants
- [ ] 6.2 Subscribe via `cxrBridge.subscribe(PHONE_TO_GLASSES_CHANNEL, msgReplyCallback)`
- [ ] 6.3 Send via `cxrBridge.sendMessage(GLASSES_TO_PHONE_CHANNEL, caps)`
- [ ] 6.4 Run `:glasses-app:compileDebugKotlin`

## 7. Manifest cleanup + dead-code deletion

- [ ] 7.1 Remove `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` from `phone-app/src/main/AndroidManifest.xml`
- [ ] 7.2 Delete `phone-app/src/main/kotlin/.../data/rokid/RokidSnCipher.kt` and its test `phone-app/src/test/kotlin/.../data/rokid/RokidSnCipherTest.kt`
- [ ] 7.3 Delete `phone-app/src/main/kotlin/.../data/rokid/EncryptedSnStore.kt` and any test
- [ ] 7.4 Delete `phone-app/src/main/kotlin/.../data/rokid/WifiP2pStaleStateWorkaround.kt` and any test
- [ ] 7.5 Update `HermesApp.kt` DI wiring: drop the SnStore/Cipher constructions; thread the auth token from `HermesPrefs` into `CxrCapsLink`

## 8. Build + test

- [ ] 8.1 `./gradlew :phone-app:assembleDebug :glasses-app:assembleDebug` — both succeed
- [ ] 8.2 `./gradlew :phone-app:test :shared:test :glasses-app:test` — all pass without modification
- [ ] 8.3 Update `openspec/changes/initial-mvp/tasks.md:37` SDK-packaging note to point at this change as superseding

## 9. Hardware verification (Pixel 9a + Rokid Glasses)

- [ ] 9.1 Install Hi Rokid AI app from Play Store (or via in-app `SmartMarketLauncher`)
- [ ] 9.2 Install migrated phone-app; complete first-run authorization; token persisted
- [ ] 9.3 Pair glasses via Hi Rokid AI app
- [ ] 9.4 Launch our phone-app; confirm `onCXRLConnected(true)` and `onGlassBtConnected(true)`; `CapsLink.connected.value == true`
- [ ] 9.5 First-run sideload of glasses-app via `appUploadAndInstall`; launched via `appStart`
- [ ] 9.6 Text loop (matches `initial-mvp/tasks.md` 8.8)
- [ ] 9.7 Voice loop (matches `initial-mvp/tasks.md` 9.7)
- [ ] 9.8 Camera path (matches `initial-mvp/tasks.md` 10.7)
- [ ] 9.9 Wake-on-message (matches `initial-mvp/tasks.md` 12.5)
- [ ] 9.10 Reconnect: walk out of BLE range and back; confirm `Disconnected → Connected`

## 10. Archive

- [ ] 10.1 `/opsx:archive` once §9 is green
