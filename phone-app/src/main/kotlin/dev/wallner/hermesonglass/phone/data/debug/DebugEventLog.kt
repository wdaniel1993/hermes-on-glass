package dev.wallner.hermesonglass.phone.data.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded ring-buffer log of wire-level events for the developer screen.
 *
 * Two streams feed it:
 *   - WS frames inbound  / outbound (HermesWsClient)
 *   - Caps envelopes inbound / outbound (CxrCapsLink + DebugCapsBridgeServer)
 *
 * Recording is gated by [HermesPrefs.debugEventsEnabled] so production
 * builds don't accumulate state. The buffer is fixed-size (last [CAPACITY]
 * entries) so the dev screen renders instantly and we never run out of
 * memory on a chatty connection.
 */
object DebugEventLog {

    enum class Direction { IN, OUT }
    enum class Wire { WS, CAPS }

    data class Entry(
        val timestampMs: Long,
        val direction: Direction,
        val wire: Wire,
        val type: String,
        val preview: String,
    )

    @Volatile var enabled: Boolean = false

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun record(direction: Direction, wire: Wire, type: String, preview: String) {
        if (!enabled) return
        val entry = Entry(
            timestampMs = System.currentTimeMillis(),
            direction = direction,
            wire = wire,
            type = type,
            preview = preview.take(MAX_PREVIEW_LEN),
        )
        _entries.value = (_entries.value + entry).takeLast(CAPACITY)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private const val CAPACITY: Int = 200
    private const val MAX_PREVIEW_LEN: Int = 200
}
