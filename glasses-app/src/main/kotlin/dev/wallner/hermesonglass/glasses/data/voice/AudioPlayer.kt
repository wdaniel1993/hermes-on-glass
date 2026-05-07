package dev.wallner.hermesonglass.glasses.data.voice

/**
 * Plays a single Opus blob via `MediaPlayer` on the glasses' local 48 kHz
 * speaker. Production impl writes the bytes to a tmp file, sets the
 * MediaPlayer source, and starts playback; tests can supply a fake.
 */
interface AudioPlayer {
    /**
     * Play the given audio bytes. Returns true if playback was kicked off
     * (the actual finish is asynchronous). Caller may invoke again before
     * the previous playback finishes — implementations typically queue or
     * replace.
     */
    fun play(bytes: ByteArray, ext: String): Boolean
    fun stop()
}
