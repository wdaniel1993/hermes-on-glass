package dev.wallner.hermesonglass.phone.debug

import android.os.Build

/**
 * Best-effort check that we're running on an Android emulator. Used only
 * by the debug-mode WebSocket bridge that stands in for CXR-S when neither
 * the phone nor the glasses has a Bluetooth radio (i.e. both are emulators
 * on the dev machine).
 */
fun isAndroidEmulator(): Boolean {
    val fp = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val product = Build.PRODUCT.lowercase()
    val brand = Build.BRAND.lowercase()
    return fp.startsWith("generic")
        || fp.startsWith("unknown")
        || model.contains("emulator")
        || model.contains("android sdk")
        || product.contains("sdk_gphone")
        || product == "sdk"
        || brand.startsWith("generic")
        || Build.HARDWARE.lowercase().contains("goldfish")
        || Build.HARDWARE.lowercase().contains("ranchu")
}
