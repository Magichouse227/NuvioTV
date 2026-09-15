package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun RecommendationsDialog(
    meta: Meta,
    source: MoreLikeThisSource,
    onDismiss: () -> Unit,
    onItemClick: (MetaPreview) -> Unit,
    viewModel: RecommendationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val grid = rememberLazyGridState()
    val first = remember { FocusRequester() }
    LaunchedEffect(meta.id, source) { viewModel.open(meta, source) }
    DisposableEffect(viewModel) { onDispose { viewModel.stop() } }
    LaunchedEffect(state.items.size, state.loading, state.endReached, state.error) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { index ->
                if (state.items.isNotEmpty() && index >= state.items.size - 6 &&
                    !state.loading && !state.endReached && state.error == null) viewModel.more()
            }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { first.requestFocus() }
        Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.focusRequester(first)) { Text("Back") }
                Column {
                    Text("More like " + meta.name, style = MaterialTheme.typography.headlineSmall)
                    Text(source.name, color = NuvioTheme.colors.TextSecondary)
                }
            }
            state.error?.let { Text(it) }
            if (state.loading) Text("Loading recommendations…")
            if (!state.loading && state.endReached && state.items.isEmpty()) Text("No recommendations found.")
            LazyVerticalGrid(columns = GridCells.Adaptive(250.dp), state = grid, modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(state.items, key = { it.apiType + ":" + it.id }) { item ->
                    GridContentCard(item = item, onClick = { onDismiss(); onItemClick(item) },
                        posterCardStyle = PosterCardStyle(width = 250.dp, height = 141.dp),
                        showLabel = true, imageCrossfade = false)
                }
            }
            if (!state.endReached) Button(onClick = viewModel::more, enabled = !state.loading) {
                Text(if (state.error == null) "Load more" else "Retry")
            }
        }
    }
}
