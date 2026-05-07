package dev.wallner.hermesonglass.phone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.wallner.hermesonglass.phone.data.ws.ConnectionState
import dev.wallner.hermesonglass.phone.domain.ChatMessage
import dev.wallner.hermesonglass.phone.domain.ChatUiState
import dev.wallner.hermesonglass.phone.domain.ToolProgressLine
import dev.wallner.hermesonglass.shared.SessionInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val messageCount by remember(state) { derivedStateOf { state.messages.size } }
    LaunchedEffect(messageCount) {
        if (messageCount > 0) listState.animateScrollToItem(messageCount - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes") },
                actions = {
                    SessionMenu(
                        sessions = state.sessions,
                        currentSessionKey = state.currentSessionKey,
                        onSwitch = viewModel::switchSession,
                        onNew = viewModel::newSession,
                    )
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                value = input,
                onValueChange = { input = it },
                enabled = connection is ConnectionState.Connected,
                onSend = {
                    if (viewModel.sendUserText(input)) input = ""
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ConnectionBanner(connection)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    MessageRow(msg, state.activeToolProgress.takeIf { it?.messageId == msg.id })
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(state: ConnectionState) {
    when (state) {
        ConnectionState.Connected -> Unit
        ConnectionState.Connecting -> ProgressBanner("Connecting…")
        ConnectionState.Disconnecting -> ProgressBanner("Disconnecting…")
        ConnectionState.Disconnected -> ErrorBanner("Disconnected")
        is ConnectionState.Failed -> ErrorBanner("Connection failed: ${state.reason}")
    }
}

@Composable
private fun ProgressBanner(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(text, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(8.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionMenu(
    sessions: List<SessionInfo>,
    currentSessionKey: String?,
    onSwitch: (String) -> Unit,
    onNew: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = currentSessionKey?.let { key ->
        sessions.firstOrNull { it.sessionKey == key }?.label?.takeIf { it.isNotBlank() }
            ?: key.take(6)
    } ?: "—"
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("Session: $label") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sessions.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label?.takeIf { it.isNotBlank() } ?: s.sessionKey) },
                    onClick = {
                        expanded = false
                        onSwitch(s.sessionKey)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("New session") },
                onClick = {
                    expanded = false
                    onNew()
                },
            )
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage, toolProgress: ToolProgressLine?) {
    val isUser = message.role == ChatMessage.Role.USER
    val bubbleColor = when (message.role) {
        ChatMessage.Role.USER -> MaterialTheme.colorScheme.primary
        ChatMessage.Role.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
        ChatMessage.Role.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val textColor = when (message.role) {
        ChatMessage.Role.USER -> MaterialTheme.colorScheme.onPrimary
        ChatMessage.Role.ASSISTANT -> MaterialTheme.colorScheme.onSurfaceVariant
        ChatMessage.Role.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(if (isUser) Alignment.End else Alignment.Start),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bubbleColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = message.text + if (message.state == ChatMessage.State.STREAMING) " ▍" else "",
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (toolProgress != null) {
            Spacer(Modifier.height(2.dp))
            ToolProgressSubline(toolProgress)
        }
    }
}

@Composable
private fun ToolProgressSubline(progress: ToolProgressLine) {
    val text = buildString {
        append("⚙ ")
        append(progress.toolName)
        progress.preview?.let { append(": ").append(it) }
        append("…")
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message Hermes…") },
            enabled = enabled,
            singleLine = false,
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
            Text("➤", color = if (enabled && value.isNotBlank())
                MaterialTheme.colorScheme.primary else Color.Gray)
        }
    }
}

