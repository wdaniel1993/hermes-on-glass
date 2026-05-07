package dev.wallner.hermesonglass.glasses.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wallner.hermesonglass.glasses.domain.HudRepository
import dev.wallner.hermesonglass.glasses.ui.theme.JetBrainsMono
import dev.wallner.hermesonglass.glasses.ui.theme.rememberMonoBodySize

/**
 * The top-level HUD layout: TopBar + ChatContentArea + MenuBar, positioned
 * within a 480x640 portrait canvas. The [HudPosition] cycle slides the HUD
 * between the full canvas, the bottom half, and the top half.
 */
@Composable
fun HudScreen(viewModel: HudViewModel) {
    val state by viewModel.state.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (state.position) {
            HudPosition.FULL -> HudContent(state, Modifier.fillMaxSize())
            HudPosition.BOTTOM_HALF -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .align(Alignment.BottomCenter),
            ) { HudContent(state, Modifier.fillMaxSize()) }
            HudPosition.TOP_HALF -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .align(Alignment.TopCenter),
            ) { HudContent(state, Modifier.fillMaxSize()) }
        }
    }
}

@Composable
private fun HudContent(state: HudUiState, modifier: Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val width = maxWidth
        val bodySize = rememberMonoBodySize(width)
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            TopBar(state)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.setupError != null -> Text(
                        text = state.setupError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.focus is FocusArea.SessionPicker -> SessionPickerArea(state, bodySize)
                    else -> ChatContentArea(state, bodySize)
                }
            }
            MenuBar(state.focus)
        }
        if (state.recordingVoice) {
            RecordingOverlay()
        }
        if (!state.phoneConnected && state.setupError == null) {
            OfflineOverlay()
        }
    }
}

@Composable
private fun TopBar(state: HudUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sessionLabel = state.currentSessionKey?.take(6) ?: "—"
        Text(
            text = "HERMES",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (state.phoneConnected) "● $sessionLabel" else "○ offline",
            color = if (state.phoneConnected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ChatContentArea(state: HudUiState, bodySize: androidx.compose.ui.unit.TextUnit) {
    val listState = rememberLazyListState()
    val count by remember(state.messages) { derivedStateOf { state.messages.size } }
    LaunchedEffect(count) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.messages, key = { it.id }) { msg ->
            HudMessageRow(
                message = msg,
                bodySize = bodySize,
                toolProgress = state.activeToolProgress.takeIf { it?.messageId == msg.id },
            )
        }
    }
}

@Composable
private fun HudMessageRow(
    message: HudMessage,
    bodySize: androidx.compose.ui.unit.TextUnit,
    toolProgress: HudToolProgress?,
) {
    val prefix = when (message.role) {
        HudMessage.Role.USER -> "› "
        HudMessage.Role.ASSISTANT -> ""
        HudMessage.Role.SYSTEM -> "• "
    }
    val color = when (message.role) {
        HudMessage.Role.USER -> MaterialTheme.colorScheme.outline
        HudMessage.Role.ASSISTANT -> MaterialTheme.colorScheme.onBackground
        HudMessage.Role.SYSTEM -> MaterialTheme.colorScheme.primary
    }
    val text = prefix + message.text + if (message.streaming) " ▍" else ""
    Text(
        text = text,
        color = color,
        fontFamily = JetBrainsMono,
        fontSize = bodySize,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    )
    if (toolProgress != null) {
        ToolProgressSubline(toolProgress, bodySize)
    }
}

@Composable
private fun SessionPickerArea(
    state: HudUiState,
    bodySize: androidx.compose.ui.unit.TextUnit,
) {
    val focus = state.focus as FocusArea.SessionPicker
    val listState = rememberLazyListState()
    LaunchedEffect(focus.selectedIndex) {
        if (state.sessions.isNotEmpty()) listState.animateScrollToItem(focus.selectedIndex)
    }
    if (state.sessions.isEmpty()) {
        Text(
            text = "No sessions yet — pick NEW from the menu.",
            color = MaterialTheme.colorScheme.outline,
            fontFamily = JetBrainsMono,
            fontSize = bodySize,
        )
        return
    }
    Column {
        Text(
            text = "SESSIONS",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.sessions, key = { it.sessionKey }) { session ->
                val idx = state.sessions.indexOfFirst { it.sessionKey == session.sessionKey }
                val highlighted = idx == focus.selectedIndex
                val isCurrent = session.sessionKey == state.currentSessionKey
                val display = session.label?.takeIf { it.isNotBlank() } ?: session.sessionKey
                val cursor = if (highlighted) "> " else "  "
                val marker = if (isCurrent) " ●" else ""
                Text(
                    text = "$cursor$display$marker",
                    color = if (highlighted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground,
                    fontFamily = JetBrainsMono,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                    fontSize = bodySize,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolProgressSubline(
    progress: HudToolProgress,
    bodySize: androidx.compose.ui.unit.TextUnit,
) {
    val text = buildString {
        append("⚙ ")
        append(progress.toolName)
        progress.preview?.let { append(": ").append(it) }
        append("…")
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.outline,
        fontFamily = JetBrainsMono,
        fontStyle = FontStyle.Italic,
        fontSize = bodySize,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
    )
}

@Composable
private fun MenuBar(focus: FocusArea) {
    val items = HudRepository.MENU_ITEMS
    val labels = HudRepository.MenuLabels
    val selected = (focus as? FocusArea.Menu)?.selectedIndex
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        items.forEachIndexed { idx, item ->
            val highlighted = idx == selected
            Text(
                text = labels[item] ?: item.name,
                color = if (highlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                fontFamily = JetBrainsMono,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun RecordingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "● REC",
            color = Color(0xFFFF6464),
            fontFamily = JetBrainsMono,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun OfflineOverlay() {
    // Connection-update banner when the phone link drops. Lets the wearer
    // see at a glance that interactions won't reach the agent right now.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "○ OFFLINE",
                color = MaterialTheme.colorScheme.outline,
                fontFamily = JetBrainsMono,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "phone bridge unreachable",
                color = MaterialTheme.colorScheme.outline,
                fontFamily = JetBrainsMono,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
