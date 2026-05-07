package dev.wallner.hermesonglass.glasses.ui.hud

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KeyEvent constants are accessible without instantiating one (compile-time
 * `int` constants in `android.jar`), but constructing or reading from a
 * KeyEvent throws on the JVM stubs. We test the [HudGestures.dispatch]
 * primitive entry point and trust the one-line forwarding from
 * [HudGestures.onKeyEvent].
 */
class HudGesturesTest {

    private val intents = mutableListOf<HudIntent>()
    private var now = 0L
    private val gestures = HudGestures(emit = { intents += it }, timeMs = { now })

    private fun down(code: Int, repeat: Int = 0) =
        gestures.dispatch(KeyEvent.ACTION_DOWN, code, repeat)

    private fun up(code: Int) =
        gestures.dispatch(KeyEvent.ACTION_UP, code, 0)

    // ── Short-press routing ────────────────────────────────────────────

    @Test fun `dpad left short press emits NavLeft`() {
        assertTrue(down(KeyEvent.KEYCODE_DPAD_LEFT))
        now += 10
        assertTrue(up(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(listOf<HudIntent>(HudIntent.NavLeft), intents)
    }

    @Test fun `dpad right short press emits NavRight`() {
        down(KeyEvent.KEYCODE_DPAD_RIGHT)
        now += 10
        up(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf<HudIntent>(HudIntent.NavRight), intents)
    }

    @Test fun `dpad up and down emit NavUp NavDown`() {
        down(KeyEvent.KEYCODE_DPAD_UP); up(KeyEvent.KEYCODE_DPAD_UP)
        down(KeyEvent.KEYCODE_DPAD_DOWN); up(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf(HudIntent.NavUp, HudIntent.NavDown), intents)
    }

    @Test fun `dpad center short press emits TapCenter`() {
        down(KeyEvent.KEYCODE_DPAD_CENTER)
        now += 100
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<HudIntent>(HudIntent.TapCenter), intents)
    }

    @Test fun `back emits Back`() {
        down(KeyEvent.KEYCODE_BACK)
        up(KeyEvent.KEYCODE_BACK)
        assertEquals(listOf<HudIntent>(HudIntent.Back), intents)
    }

    @Test fun `camera key emits Camera`() {
        down(KeyEvent.KEYCODE_CAMERA)
        up(KeyEvent.KEYCODE_CAMERA)
        assertEquals(listOf<HudIntent>(HudIntent.Camera), intents)
    }

    // ── Long-press handling on DPAD_CENTER ────────────────────────────

    @Test fun `dpad center long press emits LongPressCenterUp on release`() {
        down(KeyEvent.KEYCODE_DPAD_CENTER)
        now += HudGestures.LONG_PRESS_MS + 50
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<HudIntent>(HudIntent.LongPressCenterUp), intents)
    }

    @Test fun `dpad center hold-down past threshold fires LongPressCenterDown`() {
        down(KeyEvent.KEYCODE_DPAD_CENTER)
        now += HudGestures.LONG_PRESS_MS + 10
        // OS auto-repeat past the threshold should trigger the hold-down event.
        down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)
        assertTrue(intents.any { it is HudIntent.LongPressCenterDown })
        up(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(intents.any { it is HudIntent.LongPressCenterUp })
    }

    @Test fun `non gesture keys are not consumed`() {
        val consumedDown = down(KeyEvent.KEYCODE_ENTER)
        val consumedUp = up(KeyEvent.KEYCODE_ENTER)
        assertFalse(consumedDown)
        assertFalse(consumedUp)
        assertTrue(intents.isEmpty())
    }

    @Test fun `repeat without DPAD_CENTER is ignored on dispatch`() {
        // OS may auto-repeat DPAD_LEFT — we don't want to spam NavLeft per repeat.
        val consumed = gestures.dispatch(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 1)
        assertFalse(consumed)
        assertTrue(intents.isEmpty())
    }
}
