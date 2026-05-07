package dev.wallner.hermesonglass.phone.domain

import dev.wallner.hermesonglass.shared.SessionInfo

/** A single chat row rendered in the phone UI. */
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val state: State = State.COMPLETE,
    val parentId: String? = null,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
    enum class State { STREAMING, COMPLETE }
}

/** Tool-progress subline rendered under the active streaming message. */
data class ToolProgressLine(
    val messageId: String,
    val toolName: String,
    val phase: String,
    val preview: String?,
)

/** Snapshot of everything the chat UI needs in one immutable value. */
data class ChatUiState(
    val sessions: List<SessionInfo> = emptyList(),
    val currentSessionKey: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val activeToolProgress: ToolProgressLine? = null,
)
