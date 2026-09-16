package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.mutationKey
import com.nuvio.tv.ui.components.FeatureDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

internal data class ProfileInsights(
    val loading: Boolean = true,
    val movies: Int = 0,
    val episodes: Int = 0,
    val series: Int = 0,
    val activeDays: Int = 0,
    val saved: Int = 0,
    val genres: List<Pair<String, Int>> = emptyList(),
    val recent: List<WatchedItem> = emptyList(),
    val error: String? = null
) {
    companion object {
        fun calculate(watched: List<WatchedItem>, library: List<SavedLibraryItem>): ProfileInsights {
            val unique = watched.distinctBy { it.mutationKey() }
            val episodes = unique.filter { it.season != null && it.episode != null }
            return ProfileInsights(
                loading = false,
                movies = unique.count { it.contentType.equals("movie", true) && it.episode == null },
                episodes = episodes.size,
                series = episodes.map { it.contentId }.distinct().size,
                activeDays = unique.filter { it.watchedAt > 0 }.map {
                    Instant.ofEpochMilli(it.watchedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                }.distinct().size,
                saved = library.size,
                genres = library.flatMap { it.genres.distinct() }.groupingBy { it }.eachCount()
                    .toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).take(12),
                recent = unique.sortedByDescending { it.watchedAt }.take(30)
            )
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileInsightsViewModel @Inject constructor(
    profiles: ProfileManager, watched: WatchedItemsPreferences, library: LibraryPreferences
) : ViewModel() {
    internal val state = profiles.activeProfileId.flatMapLatest { id ->
        combine(watched.observeAllItems(id), library.observeItems(id)) { items, saved ->
            ProfileInsights.calculate(items, saved)
        }.flowOn(Dispatchers.Default).onStart { emit(ProfileInsights()) }
            .catch { emit(ProfileInsights(loading = false, error = "Could not load this profile. Reopen Insights to retry.")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), ProfileInsights())
}

@Composable
internal fun ProfileInsightsDialog(onDismiss: () -> Unit, viewModel: ProfileInsightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var taste by remember { mutableStateOf(false) }
    FeatureDialog("Profile insights", onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { taste = false }) { Text(if (!taste) "Overview ✓" else "Overview") }
            Button(onClick = { taste = true }) { Text(if (taste) "Taste ✓" else "Taste") }
        }
        when {
            state.loading -> Text("Loading insights…")
            state.error != null -> Text(state.error.orEmpty())
            taste -> {
                Text("Genres in this profile’s saved Nuvio library", color = NuvioTheme.colors.TextSecondary)
                if (state.genres.isEmpty()) Text("Save titles with genre information to see your taste profile.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.genres, key = { it.first }) { (genre, count) ->
                        InsightsRow("$genre  ·  $count titles")
                    }
                }
            }
            else -> {
                Text("${state.movies} movies  ·  ${state.episodes} episodes  ·  ${state.series} series",
                    style = MaterialTheme.typography.titleLarge)
                Text("${state.activeDays} active days  ·  ${state.saved} saved titles")
                Text("Based on this profile’s recorded watched items. Rewatches and viewing duration are not recorded here.",
                    color = NuvioTheme.colors.TextSecondary)
                Text("Recently watched", style = MaterialTheme.typography.titleMedium)
                if (state.recent.isEmpty()) Text("Your completed movies and episodes will appear here.")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.recent, key = { "${it.contentType}:${it.contentId}:${it.season}:${it.episode}" }) { item ->
                        val episode = if (item.season != null && item.episode != null) " · S${item.season} E${item.episode}" else ""
                        InsightsRow(item.title + episode)
                    }
                }
            }
        }
    }
}

/** Focusable read-only rows let a TV remote reveal every item in the lazy list. */
@Composable
private fun InsightsRow(text: String) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = text,
        color = NuvioTheme.colors.TextPrimary,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) NuvioTheme.colors.Primary.copy(alpha = 0.24f) else Color.Transparent)
            .focusable()
            .padding(12.dp)
    )
}
