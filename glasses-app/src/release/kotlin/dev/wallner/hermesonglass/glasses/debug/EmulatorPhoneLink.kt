package dev.wallner.hermesonglass.glasses.debug

import dev.wallner.hermesonglass.glasses.data.PhoneLink

/**
 * Release-variant stub for the emulator WebSocket bridge — never returns a
 * link. The debug variant lives in src/debug/.../DebugPhoneLinkClient.kt
 * and returns a real WS-client-backed [PhoneLink] on emulators.
 */
fun createEmulatorPhoneLinkOrNull(): PhoneLink? = null
