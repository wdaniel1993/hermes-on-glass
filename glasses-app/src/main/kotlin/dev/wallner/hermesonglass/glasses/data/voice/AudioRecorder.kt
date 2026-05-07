package dev.wallner.hermesonglass.glasses.data.voice

/**
 * Hold-to-talk recorder abstraction.
 *
 * The production impl wraps `MediaRecorder` (Opus encoder + OGG container,
 * `AudioSource.MIC`); a stub fake drives the same surface in unit tests.
 *
 * Lifecycle:
 *   start() — begins recording; idempotent.
 *   stop()  — flushes the encoder, returns the captured bytes (Opus/OGG),
 *             or null if nothing was captured (e.g. user released before
 *             the recorder finished initialising).
 */
interface AudioRecorder {
    fun start(): Boolean
    fun stop(): ByteArray?

    /** True between start() and the next stop(). */
    val isRecording: Boolean
}
