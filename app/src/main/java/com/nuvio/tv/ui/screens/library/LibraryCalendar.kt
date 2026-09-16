package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.ui.components.FeatureDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

internal data class CalendarEpisode(val series: LibraryEntry, val season: Int, val episode: Int,
    val title: String, val date: LocalDate) {
    val key: String get() = "${series.type}:${series.id}:$season:$episode"
}

internal fun calendarEpisodes(series: LibraryEntry, videos: List<Video>, month: YearMonth): List<CalendarEpisode> =
    videos.mapNotNull { video ->
        val season = video.season?.takeIf { it > 0 } ?: return@mapNotNull null
        val number = video.episode?.takeIf { it > 0 } ?: return@mapNotNull null
        val date = parseEpisodeReleaseLocalDate(video.released) ?: return@mapNotNull null
        if (YearMonth.from(date) != month) null else CalendarEpisode(series, season, number, video.title, date)
    }.distinctBy { it.key }

internal data class CalendarState(val month: YearMonth = YearMonth.now(), val items: List<CalendarEpisode> = emptyList(),
    val loading: Boolean = false, val checked: Int = 0, val total: Int = 0, val failed: Int = 0, val error: String? = null)

@HiltViewModel
class LibraryCalendarViewModel @Inject constructor(private val metadata: MetaRepository,
    private val tmdb: TmdbApi, private val ids: TmdbService, profiles: ProfileManager) : ViewModel() {
    val profileId = profiles.activeProfileId
    private val mutableState = MutableStateFlow(CalendarState())
    internal val state = mutableState.asStateFlow()
    private var series = emptyList<LibraryEntry>()
    private var job: Job? = null

    fun open(entries: List<LibraryEntry>, month: YearMonth = YearMonth.now()) {
        job?.cancel()
        series = entries.filter { it.type.equals("series", true) || it.type.equals("tv", true) || it.type.equals("anime", true) }
            .distinctBy { it.type + ":" + it.id }.sortedBy { it.name }
        mutableState.value = CalendarState(month = month, total = series.size)
        more()
    }
    fun changeMonth(offset: Long) = open(series, state.value.month.plusMonths(offset))
    fun refresh() = open(series, state.value.month)
    fun stop() { job?.cancel(); series = emptyList(); mutableState.value = CalendarState() }

    fun more() {
        val before = state.value
        if (before.loading || before.checked >= series.size) return
        val batch = series.drop(before.checked).take(40)
        val owner = profileId.value
        mutableState.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                // Two series at a time; metadata and episode arrays are not retained by this screen.
                for (pair in batch.chunked(2)) {
                    val results = coroutineScope { pair.map { entry -> async(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(20_000) { load(entry, before.month) } ?: (emptyList<CalendarEpisode>() to true)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { emptyList<CalendarEpisode>() to true }
                    } }.awaitAll() }
                    ensureActive()
                    if (owner != profileId.value) return@launch
                    mutableState.update { current -> current.copy(
                        items = (current.items + results.flatMap { it.first }).distinctBy { it.key }
                            .sortedWith(compareBy<CalendarEpisode> { it.date }.thenBy { it.series.name }.thenBy { it.episode }),
                        checked = current.checked + pair.size, failed = current.failed + results.count { it.second }) }
                }
                mutableState.update { it.copy(loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, error = "Unable to load the calendar. Try Refresh.") } }
        }
    }

    private suspend fun load(entry: LibraryEntry, month: YearMonth): Pair<List<CalendarEpisode>, Boolean> {
        val cached = metadata.getCachedMeta(entry.type, entry.id)
        val meta = cached ?: when (val result = metadata.getMetaFromAllAddons(entry.type, entry.id, entry.addonBaseUrl)
            .firstOrNull { it is NetworkResult.Success || it is NetworkResult.Error }) {
            is NetworkResult.Success -> result.data
            else -> null
        }
        val videos = meta?.videos.orEmpty()
        val found = calendarEpisodes(entry, videos, month)
        if (found.isNotEmpty() || month < YearMonth.now()) return found to (meta == null)
        // One details request supplies TMDB's next announced episode, without fetching every season.
        if (BuildConfig.TMDB_API_KEY.isBlank()) return found to (meta == null)
        val id = entry.tmdbId ?: ids.ensureTmdbId(entry.id, "series")?.toIntOrNull() ?: return found to (meta == null)
        val response = tmdb.getTvDetails(id, BuildConfig.TMDB_API_KEY)
        if (!response.isSuccessful) return found to true
        val next = response.body()?.nextEpisodeToAir ?: return found to (meta == null && response.body() == null)
        val date = parseEpisodeReleaseLocalDate(next.airDate) ?: return found to false
        val season = next.seasonNumber?.takeIf { it > 0 } ?: return found to false
        val number = next.episodeNumber?.takeIf { it > 0 } ?: return found to false
        return (if (YearMonth.from(date) == month) listOf(CalendarEpisode(entry, season, number, next.name.orEmpty(), date)) else found) to false
    }
}

@Composable
fun LibraryCalendarDialog(entries: List<LibraryEntry>, onDismiss: () -> Unit,
    onNavigateToDetail: (String, String, String?) -> Unit, viewModel: LibraryCalendarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile by viewModel.profileId.collectAsStateWithLifecycle()
    val initialProfile = remember { profile }
    LaunchedEffect(profile) { if (profile != initialProfile) onDismiss() }
    LaunchedEffect(entries) { viewModel.open(entries) }
    DisposableEffect(viewModel) { onDispose { viewModel.stop() } }
    FeatureDialog("Library calendar", onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { viewModel.changeMonth(-1) }) { Text("Previous month") }
            Text(state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy")))
            Button(onClick = { viewModel.changeMonth(1) }) { Text("Next month") }
            Button(onClick = viewModel::refresh, enabled = !state.loading) { Text("Refresh") }
        }
        Text("${state.checked} of ${state.total} series checked" + if (state.loading) " · Loading…" else "",
            color = NuvioTheme.colors.TextSecondary)
        Text("Announced dates from your addons and TMDB; schedules may change.", color = NuvioTheme.colors.TextSecondary)
        if (state.failed > 0) Text("${state.failed} series could not be checked. Refresh to retry.")
        state.error?.let { Text(it) }
        if (state.items.isEmpty() && !state.loading) Text(if (state.total == 0)
            "Save a series to your library to see its episodes here." else "No announced episodes found for this month.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.items, key = { it.key }) { item ->
                Button(onClick = { onDismiss(); onNavigateToDetail(item.series.id, item.series.type, item.series.addonBaseUrl) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("${item.date.format(DateTimeFormatter.ofPattern("EEE d MMM"))}  ·  ${item.series.name}  ·  S${item.season} E${item.episode}  ${item.title}")
                }
            }
        }
        if (state.checked < state.total) Button(onClick = viewModel::more, enabled = !state.loading) { Text("Check next 40 series") }
    }
}
