package dev.wallner.hermesonglass.glasses.ui.hud

import android.view.KeyEvent

/**
 * Maps Rokid touchpad key events to HUD intents.
 *
 * Per Rokid docs the temple touchpad surfaces as standard Android KeyEvents:
 *   DPAD_LEFT / DPAD_RIGHT  — horizontal nav (menu items, focus)
 *   DPAD_UP / DPAD_DOWN     — vertical nav (chat scroll)
 *   DPAD_CENTER             — short tap = activate; long press = voice capture
 *   BACK                    — collapse focus / hide menu
 *   KEYCODE_CAMERA          — take photo
 *
 * Long-press detection lives here because we want one consistent threshold
 * (LONG_PRESS_MS = 500). We track the down timestamp per keycode; on UP we
 * decide short vs long.
 */
class HudGestures(
    private val emit: (HudIntent) -> Unit,
    private val timeMs: () -> Long = System::currentTimeMillis,
) {
    private val keyDownAt = mutableMapOf<Int, Long>()

    /**
     * Called from the Activity's `dispatchKeyEvent`. Forwards to [dispatch]
     * which is the unit-testable surface (the JVM android.jar stubs out
     * every method on KeyEvent, so tests can't construct one).
     */
    fun onKeyEvent(event: KeyEvent): Boolean =
        dispatch(action = event.action, keyCode = event.keyCode, repeatCount = event.repeatCount)

    /**
     * Pure-data entry point. Returns true when we consumed the event.
     */
    fun dispatch(action: Int, keyCode: Int, repeatCount: Int): Boolean {
        if (repeatCount > 0 && keyCode != KeyEvent.KEYCODE_DPAD_CENTER) return false
        return when (action) {
            KeyEvent.ACTION_DOWN -> onDown(keyCode, repeatCount)
            KeyEvent.ACTION_UP -> onUp(keyCode)
            else -> false
        }
    }

    private fun onDown(keyCode: Int, repeatCount: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER -> {
            if (repeatCount == 0) keyDownAt[keyCode] = timeMs()
            val downAt = keyDownAt[keyCode] ?: return true
            if (timeMs() - downAt >= LONG_PRESS_MS && repeatCount > 0) {
                emit(HudIntent.LongPressCenterDown)
            }
            true
        }
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_CAMERA -> {
            keyDownAt[keyCode] = timeMs()
            true
        }
        else -> false
    }

    private fun onUp(keyCode: Int): Boolean {
        val downAt = keyDownAt.remove(keyCode) ?: return false
        val held = timeMs() - downAt
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (held >= LONG_PRESS_MS) emit(HudIntent.LongPressCenterUp)
                else emit(HudIntent.TapCenter)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> emit(HudIntent.NavLeft)
            KeyEvent.KEYCODE_DPAD_RIGHT -> emit(HudIntent.NavRight)
            KeyEvent.KEYCODE_DPAD_UP -> emit(HudIntent.NavUp)
            KeyEvent.KEYCODE_DPAD_DOWN -> emit(HudIntent.NavDown)
            KeyEvent.KEYCODE_BACK -> emit(HudIntent.Back)
            KeyEvent.KEYCODE_CAMERA -> emit(HudIntent.Camera)
            else -> return false
        }
        return true
    }

    companion object {
        const val LONG_PRESS_MS: Long = 500L
    }
}

/**
 * UI intents the HUD's view model handles. Keep semantic, not key-bound.
 */
sealed interface HudIntent {
    data object NavLeft : HudIntent
    data object NavRight : HudIntent
    data object NavUp : HudIntent
    data object NavDown : HudIntent
    data object TapCenter : HudIntent
    data object LongPressCenterDown : HudIntent
    data object LongPressCenterUp : HudIntent
    data object Back : HudIntent
    data object Camera : HudIntent
}
