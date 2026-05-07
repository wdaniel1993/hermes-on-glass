package dev.wallner.hermesonglass.phone.debug

import dev.wallner.hermesonglass.phone.data.cxr.CapsLink

/**
 * Release-variant stub for the emulator WebSocket bridge — never returns a
 * bridge in release. The debug variant of this function lives in
 * src/debug/.../DebugCapsBridgeServer.kt and returns a real WS-server-backed
 * [CapsLink] when running on an Android emulator.
 */
fun createEmulatorCapsLinkOrNull(): CapsLink? = null
