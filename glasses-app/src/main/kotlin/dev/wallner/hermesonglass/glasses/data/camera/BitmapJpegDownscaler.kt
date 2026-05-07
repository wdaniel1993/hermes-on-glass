package dev.wallner.hermesonglass.glasses.data.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Production [JpegDownscaler] backed by `BitmapFactory` + `Bitmap.compress`.
 *
 * Algorithm:
 *   1. If `jpeg.size <= maxBytes` return immediately.
 *   2. Decode once to get a Bitmap. If decode fails, return the input.
 *   3. Sweep quality 80, 70, 60, 50 at the current dimensions.
 *   4. If still too large, halve the longer edge and repeat the quality
 *      sweep. Cap at [MAX_HALVINGS] iterations so we don't burn CPU on
 *      a pathological image.
 *
 * On the rare image where even the smallest result exceeds [maxBytes] we
 * return the smallest result we managed; the caller logs a warning when
 * it sees an oversized blob and the [CapsFrameSizePolicy] hard-reject
 * gate at the phone-side guards the wire.
 */
class BitmapJpegDownscaler : JpegDownscaler {

    override fun downscale(jpeg: ByteArray, maxBytes: Int): ByteArray {
        if (jpeg.size <= maxBytes) return jpeg

        val original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (original == null) {
            Timber.w("BitmapFactory.decodeByteArray returned null; passing through %d B", jpeg.size)
            return jpeg
        }

        var bitmap: Bitmap = original
        var smallest = jpeg
        try {
            for (halving in 0..MAX_HALVINGS) {
                if (halving > 0) {
                    val nextW = (bitmap.width / 2).coerceAtLeast(MIN_EDGE)
                    val nextH = (bitmap.height / 2).coerceAtLeast(MIN_EDGE)
                    val scaled = Bitmap.createScaledBitmap(bitmap, nextW, nextH, true)
                    if (bitmap !== original) bitmap.recycle()
                    bitmap = scaled
                }
                for (q in QUALITY_SWEEP) {
                    val out = ByteArrayOutputStream(maxBytes)
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)) continue
                    val bytes = out.toByteArray()
                    if (bytes.size < smallest.size) smallest = bytes
                    if (bytes.size <= maxBytes) return bytes
                }
                if (bitmap.width <= MIN_EDGE && bitmap.height <= MIN_EDGE) break
            }
        } finally {
            if (bitmap !== original) bitmap.recycle()
            original.recycle()
        }
        return smallest
    }

    companion object {
        private val QUALITY_SWEEP = intArrayOf(80, 70, 60, 50)
        private const val MAX_HALVINGS: Int = 4
        private const val MIN_EDGE: Int = 240
    }
}
