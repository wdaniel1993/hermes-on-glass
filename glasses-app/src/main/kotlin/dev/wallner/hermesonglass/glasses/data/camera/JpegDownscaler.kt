package dev.wallner.hermesonglass.glasses.data.camera

/**
 * Progressively shrinks a JPEG until its on-wire size is <= [maxBytes].
 *
 * The strategy (matching design D10's "≤ 256 KB after downscale"):
 *   1. If already small enough, return as-is.
 *   2. Otherwise re-encode at a sequence of quality levels.
 *   3. If quality alone doesn't fit, halve the longer-edge dimension
 *      and retry the quality sweep.
 *   4. After a few halvings give up — return the smallest result we got
 *      (caller decides whether to drop or send anyway).
 *
 * The Android impl ([BitmapJpegDownscaler]) does the actual decode/encode.
 * The interface lets us inject a fake in unit tests for the strategy.
 */
interface JpegDownscaler {
    fun downscale(jpeg: ByteArray, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray

    companion object {
        const val DEFAULT_MAX_BYTES: Int = 256 * 1024
    }
}
