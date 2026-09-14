package com.nuvio.tv.ui.screens.livetv

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.core.livetv.*
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.UUID

@Composable
fun LiveTvSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    onBack: (() -> Unit)? = null,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val first = initialFocusRequester ?: remember { FocusRequester() }
    var editor by remember { mutableStateOf<LiveTvSource?>(null) }
    var removing by remember { mutableStateOf<LiveTvSource?>(null) }
    var reset by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                editor = LiveTvSource(UUID.randomUUID().toString(), "Imported playlist", LiveTvSourceType.LOCAL_M3U, uri.toString())
            } catch (_: Exception) { viewModel.showError("This file provider could not grant lasting access. Try another file provider or a playlist URL.") }
        }
    }
    LaunchedEffect(Unit) { if (initialFocusRequester == null) first.requestFocus() }
    LaunchedEffect(state.profileId) { editor = null; removing = null; reset = false }

    Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live TV sources", style = MaterialTheme.typography.headlineSmall)
        Text("Playlists and providers are saved for the active profile.", color = NuvioTheme.colors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { editor = LiveTvSource("", "", LiveTvSourceType.M3U, "") },
                modifier = Modifier.focusRequester(first), enabled = !state.unreadable) { Text("Add source") }
            Button(onClick = {
                try { importer.launch(arrayOf("*/*")) }
                catch (_: Exception) { viewModel.showError("No file picker is installed on this TV. Add a playlist URL instead.") }
            }, enabled = !state.unreadable) { Text("Import M3U") }
            if (onBack != null) Button(onClick = onBack) { Text("Back") }
        }
        Button(onClick = viewModel::toggleNavigation, enabled = !state.saving && !state.unreadable) {
            Text(if (state.config.showInNavigation) "Show Live TV in navigation: on" else "Show Live TV in navigation: off")
        }
        state.error?.let { Text(it, color = NuvioTheme.colors.TextSecondary) }
        if (state.unreadable) Button(onClick = { reset = true }) { Text("Reset Live TV settings") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.config.sources, key = { it.id }) { source ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                    Text(source.type.name.replace('_', ' '), color = NuvioTheme.colors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { editor = source }, enabled = !state.saving) { Text("Edit") }
                        Button(onClick = { viewModel.setEnabled(source) }, enabled = !state.saving) { Text(if (source.enabled) "Enabled" else "Disabled") }
                        Button(onClick = { removing = source }, enabled = !state.saving) { Text("Remove") }
                    }
                }
            }
        }
    }
    editor?.let { source ->
        key(source.id, state.profileId) {
            SourceEditor(source, state.error, state.saving, { editor = null }) { edited ->
                viewModel.save(edited) { editor = null }
            }
        }
    }
    removing?.let { source ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove source?") },
            text = { Text(source.name) },
            confirmButton = { Button(onClick = { viewModel.remove(source); removing = null }) { Text("Remove") } },
            dismissButton = { Button(onClick = { removing = null }) { Text("Cancel") } })
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Reset Live TV settings?") },
        text = { Text("This removes sources and favorites for the active profile so you can configure them again.") },
        confirmButton = { Button(onClick = { viewModel.reset(); reset = false }) { Text("Reset") } },
        dismissButton = { Button(onClick = { reset = false }) { Text("Cancel") } })
}

@Composable
private fun SourceEditor(source: LiveTvSource, error: String?, saving: Boolean,
                         onDismiss: () -> Unit, onSave: (LiveTvSource) -> Unit) {
    var value by remember(source) { mutableStateOf(source) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (source.id.isBlank()) "Add Live TV source" else "Edit Live TV source") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (source.type != LiveTvSourceType.LOCAL_M3U) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(LiveTvSourceType.M3U, LiveTvSourceType.XTREAM, LiveTvSourceType.STALKER).forEach { type ->
                            Button(onClick = { value = value.copy(type = type) }) {
                                Text((if (value.type == type) "✓ " else "") + when (type) {
                                    LiveTvSourceType.M3U -> "M3U"
                                    LiveTvSourceType.XTREAM -> "Xtream"
                                    else -> "Stalker"
                                })
                            }
                        }
                    }
                }
                OutlinedTextField(value.name, { value = value.copy(name = it) }, modifier = Modifier.focusRequester(focus),
                    label = { Text("Name") }, singleLine = true)
                if (value.type != LiveTvSourceType.LOCAL_M3U) {
                    OutlinedTextField(value.url, { value = value.copy(url = it) },
                        label = { Text(if (value.type == LiveTvSourceType.M3U) "Playlist URL" else "Server or portal URL") }, singleLine = true)
                }
                if (value.type == LiveTvSourceType.STALKER) {
                    OutlinedTextField(value.macAddress, { value = value.copy(macAddress = it.trim()) },
                        label = { Text("MAC address") }, singleLine = true)
                }
                if (value.type == LiveTvSourceType.XTREAM || value.type == LiveTvSourceType.STALKER) {
                    val optional = if (value.type == LiveTvSourceType.STALKER) " (optional)" else ""
                    OutlinedTextField(value.username, { value = value.copy(username = it) },
                        label = { Text("Username$optional") }, singleLine = true)
                    OutlinedTextField(value.password, { value = value.copy(password = it) },
                        label = { Text("Password$optional") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                error?.let { Text(it, color = NuvioTheme.colors.TextSecondary) }
            }
        },
        confirmButton = { Button(onClick = { onSave(value) }, enabled = !saving) { Text(if (saving) "Saving…" else "Save") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } })
}
