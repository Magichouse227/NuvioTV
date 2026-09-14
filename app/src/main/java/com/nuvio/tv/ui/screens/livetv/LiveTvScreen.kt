package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import coil3.compose.AsyncImage
import com.nuvio.tv.core.livetv.LiveTvChannel
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun LiveTvScreen(
    onSettings: () -> Unit,
    onPlay: (LiveTvChannel, Int) -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val query by viewModel.query.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val first = remember { FocusRequester() }
    var chooser by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.startBrowsing(); first.requestFocus() }

    Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live TV", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSettings, modifier = Modifier.focusRequester(first)) { Text("Sources") }
            Button(onClick = viewModel::refresh, enabled = !state.loading) { Text("Refresh") }
            Button(onClick = { viewModel.filter.value = filter.copy(favorites = !filter.favorites) }) {
                Text(if (filter.favorites) "Favorites: on" else "Favorites")
            }
            state.channels.find { it.id == state.config.lastWatchedId }?.let { last ->
                Button(onClick = { viewModel.play(last, onPlay) }, enabled = !state.preparing) { Text("Last watched") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = query, onValueChange = { viewModel.query.value = it },
                label = { Text("Search channels") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { chooser = "group" }) { Text(filter.group ?: "All groups", maxLines = 1) }
            Button(onClick = { chooser = "source" }) {
                Text(state.config.sources.find { it.id == filter.sourceId }?.name ?: "All sources", maxLines = 1)
            }
        }
        state.error?.let { Text(it, color = NuvioTheme.colors.TextSecondary) }
        when {
            state.preparing -> Text("Opening channel…")
            state.loading -> Text("Loading channels…")
            state.config.sources.isEmpty() -> Text("Add a playlist or provider in Sources to start watching.")
            state.channels.isEmpty() -> Text("No matching channels. Change the filters or refresh your sources.")
            else -> Text("${state.channels.size} of ${state.total} channels", color = NuvioTheme.colors.TextSecondary)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(260.dp), modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.channels, key = { it.id }) { channel ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.play(channel, onPlay) }, modifier = Modifier.weight(1f), enabled = !state.preparing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (channel.logo != null) AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(40.dp))
                            Column {
                                Text(channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                channel.group?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                    Button(onClick = { viewModel.favorite(channel) }) {
                        Text(if (channel.id in state.config.favorites) "★" else "☆")
                    }
                }
            }
        }
    }
    chooser?.let { kind ->
        val options = if (kind == "group") state.groups.map { it to it }
            else state.config.sources.map { it.id to it.name }
        AlertDialog(onDismissRequest = { chooser = null },
            title = { Text(if (kind == "group") "Channel group" else "Source") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Button(onClick = {
                            viewModel.filter.value = if (kind == "group") filter.copy(group = null) else filter.copy(sourceId = null)
                            chooser = null
                        }) { Text("All") }
                    }
                    items(options, key = { it.first }) { (id, label) ->
                        Button(onClick = {
                            viewModel.filter.value = if (kind == "group") filter.copy(group = id) else filter.copy(sourceId = id)
                            chooser = null
                        }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }, confirmButton = { Button(onClick = { chooser = null }) { Text("Close") } })
    }
}
