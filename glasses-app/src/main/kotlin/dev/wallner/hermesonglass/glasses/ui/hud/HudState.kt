package dev.wallner.hermesonglass.glasses.ui.hud

import dev.wallner.hermesonglass.shared.SessionInfo

/**
 * The HUD has a small focus model — exactly one surface consumes nav-key
 * events at a time. Sealed instead of enum so each focus mode can carry
 * its own state (selection index, etc.).
 */
sealed interface FocusArea {
    data object Content : FocusArea
    data class Menu(val selectedIndex: Int = 0) : FocusArea
    data class SessionPicker(val selectedIndex: Int = 0) : FocusArea
}

/**
 * The HUD's overall position on the 480x640 panel. The "size" / position
 * cycle is driven by the SIZE menu item (task 7.6).
 */
enum class HudPosition {
    FULL,         // entire 480x640 canvas
    BOTTOM_HALF,  // bottom 480x320 — keeps eyeline clear
    TOP_HALF;     // top 480x320

    fun next(): HudPosition = when (this) {
        FULL -> BOTTOM_HALF
        BOTTOM_HALF -> TOP_HALF
        TOP_HALF -> FULL
    }
}

/**
 * Lines of text the user has accumulated in the HUD chat content area.
 * Stream chunks append; chat_message replaces with a final.
 */
data class HudMessage(
    val id: String,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

data class HudToolProgress(
    val messageId: String,
    val toolName: String,
    val phase: String,
    val preview: String?,
)

data class HudUiState(
    val focus: FocusArea = FocusArea.Content,
    val position: HudPosition = HudPosition.FULL,
    val messages: List<HudMessage> = emptyList(),
    val activeToolProgress: HudToolProgress? = null,
    val phoneConnected: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val currentSessionKey: String? = null,
    val recordingVoice: Boolean = false,
    val setupError: String? = null,
)
