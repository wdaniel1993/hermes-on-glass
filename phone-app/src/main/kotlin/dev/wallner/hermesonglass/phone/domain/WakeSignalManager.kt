package dev.wallner.hermesonglass.phone.domain

import dev.wallner.hermesonglass.phone.data.cxr.CapsLink
import dev.wallner.hermesonglass.phone.data.ws.WsConnection
import dev.wallner.hermesonglass.shared.ChatMessage
import dev.wallner.hermesonglass.shared.PushMessage
import dev.wallner.hermesonglass.shared.WakeAck
import dev.wallner.hermesonglass.shared.WakeSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Hermes-initiated push delivery with HUD wake coordination (design D11).
 *
 * Flow per [PushMessage]:
 *   1. Send `WakeSignal { reason, messageId }` over Caps so the glasses
 *      can pre-warm the display.
 *   2. Wait up to [timeoutMs] for a matching `WakeAck { ready, messageId }`.
 *      `ready=true`  → glasses HUD is rendering, deliver immediately.
 *      `ready=false` → glasses can't render right now; we still deliver
 *                      so the message isn't lost (logged at warn).
 *      timeout       → same as `ready=false`.
 *   3. Send `ChatMessage { messageId, text, sessionKey }` over Caps.
 *
 * The bridge no longer handles [PushMessage] directly — this class owns
 * the full sequence.
 */
class WakeSignalManager(
    private val ws: WsConnection,
    private val caps: CapsLink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val pendingAcks = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private var pushJob: Job? = null
    private var ackJob: Job? = null

    fun start() {
        if (pushJob?.isActive == true) return
        pushJob = scope.launch {
            ws.frames.filterIsInstance<PushMessage>().collect { push ->
                // launch per-push so concurrent cron deliveries don't
                // serialize on each other's wake-ack timeout.
                scope.launch { deliver(push) }
            }
        }
        ackJob = scope.launch {
            caps.inbound.filterIsInstance<WakeAck>().collect { onAck(it) }
        }
    }

    fun stop() {
        pushJob?.cancel(); pushJob = null
        ackJob?.cancel(); ackJob = null
        synchronized(pendingAcks) {
            pendingAcks.values.forEach { it.cancel() }
            pendingAcks.clear()
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    suspend fun deliver(push: PushMessage) {
        // Register the deferred BEFORE sending so we never miss an ack that
        // arrives between send and registration (the inbound flow runs on a
        // separate coroutine; with no buffer entry, onAck would drop it and
        // we'd wait the full timeout for nothing).
        val ack = CompletableDeferred<Boolean>()
        synchronized(pendingAcks) { pendingAcks[push.messageId] = ack }
        caps.send(WakeSignal(reason = push.origin, messageId = push.messageId))
        val ready = withTimeoutOrNull(timeoutMs) { ack.await() } ?: false
        synchronized(pendingAcks) { pendingAcks.remove(push.messageId) }
        if (!ready) {
            Timber.w("wake timeout/not-ready for %s; delivering %s anyway",
                push.messageId, push.origin)
        }
        caps.send(ChatMessage(
            messageId = push.messageId,
            text = formatPushText(push),
            sessionKey = push.sessionKey,
        ))
    }

    private fun onAck(ack: WakeAck) {
        val mid = ack.messageId ?: return
        val pending = synchronized(pendingAcks) { pendingAcks[mid] }
        pending?.complete(ack.ready)
    }

    private fun formatPushText(push: PushMessage): String =
        "[${push.origin}] ${push.text}"

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 3_000L
    }
}
