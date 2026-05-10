package dev.wallner.hermesonglass.phone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.wallner.hermesonglass.phone.data.ws.ConnectionState
import dev.wallner.hermesonglass.shared.SessionInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val chat by viewModel.chatState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.wsUrl,
                onValueChange = viewModel::setWsUrl,
                label = { Text("Hermes WebSocket URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.sharedSecret,
                onValueChange = viewModel::setSharedSecret,
                label = { Text("Shared secret") },
                singleLine = true,
                visualTransformation = if (state.secretRevealed) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = viewModel::toggleSecretRevealed) {
                        Text(if (state.secretRevealed) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Debug events log")
                Switch(checked = state.debugEventsEnabled, onCheckedChange = viewModel::setDebugEnabled)
            }

            HorizontalDivider()

            Text("Connection", style = MaterialTheme.typography.titleSmall)
            Text(connection.describe(), style = MaterialTheme.typography.bodyMedium)
            Text("Device id: ${state.deviceId}", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = viewModel::applyAndReconnect,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply & reconnect") }

            HorizontalDivider()

            Text("Sessions", style = MaterialTheme.typography.titleSmall)
            if (chat.sessions.isEmpty()) {
                Text(
                    "No sessions yet — connect first or pick NEW from the glasses HUD.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                chat.sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        isCurrent = session.sessionKey == chat.currentSessionKey,
                        onRename = { viewModel.renameSession(session.sessionKey, it) },
                        onDelete = { viewModel.deleteSession(session.sessionKey) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            HorizontalDivider()

            GlassesAppSection(viewModel = viewModel)
        }
    }
}

@Composable
private fun GlassesAppSection(viewModel: SettingsViewModel) {
    val state by viewModel.glassesAppState.collectAsState()
    val capsConnected by viewModel.capsConnected.collectAsState()

    Text("Glasses app", style = MaterialTheme.typography.titleSmall)
    Text(describe(state), style = MaterialTheme.typography.bodyMedium)

    val busy = state is GlassesAppState.Installing || state is GlassesAppState.Launching
    Button(
        onClick = viewModel::triggerInstall,
        enabled = capsConnected && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                busy -> "Working…"
                state is GlassesAppState.Installed -> "Reinstall & launch glasses app"
                else -> "Install & launch glasses app"
            },
        )
    }
    if (!capsConnected) {
        Text(
            "Glasses link not connected — pair your glasses through Hi Rokid first.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun describe(state: GlassesAppState): String = when (state) {
    GlassesAppState.Unknown -> "Glasses app: status unknown"
    GlassesAppState.NotInstalled -> "Glasses app: not installed"
    GlassesAppState.Installed -> "Glasses app: installed"
    GlassesAppState.Installing -> "Glasses app: installing… (~30s on first push)"
    GlassesAppState.Launching -> "Glasses app: launching…"
    is GlassesAppState.Failed -> "Glasses app: failed — ${state.reason}"
}

@Composable
private fun SessionRow(
    session: SessionInfo,
    isCurrent: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var renamingTo by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val displayLabel = session.label?.takeIf { it.isNotBlank() } ?: session.sessionKey
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (if (isCurrent) "● " else "  ") + displayLabel,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { renamingTo = session.label.orEmpty() }) { Text("Rename") }
        TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
    }

    val pendingRename = renamingTo
    if (pendingRename != null) {
        var draft by remember(session.sessionKey) { mutableStateOf(pendingRename) }
        AlertDialog(
            onDismissRequest = { renamingTo = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Label") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(draft.trim())
                    renamingTo = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingTo = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("$displayLabel will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private fun ConnectionState.describe(): String = when (this) {
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Connected -> "Connected"
    ConnectionState.Disconnecting -> "Disconnecting…"
    is ConnectionState.Failed -> "Failed: $reason (retry in ${retryInMs} ms)"
}
