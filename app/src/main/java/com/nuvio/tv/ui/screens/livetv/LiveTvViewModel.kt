package com.nuvio.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.livetv.*
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import javax.inject.Inject

data class LiveTvUiState(
    val profileId: Int = 1,
    val config: LiveTvConfig = LiveTvConfig(),
    val channels: List<LiveTvChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val preparing: Boolean = false,
    val saving: Boolean = false,
    val unreadable: Boolean = false,
    val error: String? = null
)

data class LiveTvFilter(val favorites: Boolean = false, val group: String? = null, val sourceId: String? = null)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val profiles: ProfileManager,
    private val preferences: LiveTvPreferences,
    private val client: LiveTvClient
) : ViewModel() {
    private val data = MutableStateFlow(LiveTvUiState())
    private val channels = MutableStateFlow<List<LiveTvChannel>>(emptyList())
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LiveTvFilter())
    private var browsing = false
    private var loadingJob: Job? = null
    private var playingJob: Job? = null

    val uiState = combine(data, channels, query.debounce(250), filter) { state, all, search, selection ->
        state.copy(channels = all.filter {
            (!selection.favorites || it.id in state.config.favorites) &&
                (selection.group == null || it.group == selection.group) &&
                (selection.sourceId == null || it.sourceId == selection.sourceId) &&
                (search.isBlank() || it.name.contains(search.trim(), true) || it.group.orEmpty().contains(search.trim(), true))
        }, total = all.size)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveTvUiState())

    init {
        viewModelScope.launch {
            profiles.activeProfileId.collectLatest { id ->
                loadingJob?.cancel(); playingJob?.cancel()
                channels.value = emptyList(); query.value = ""; filter.value = LiveTvFilter()
                data.value = LiveTvUiState(profileId = id)
                var previousSources: List<LiveTvSource>? = null
                preferences.configFor(id).catch { emit(Result.failure(it)) }.collect { result ->
                    val config = result.getOrNull()
                    if (config == null) {
                        data.update { it.copy(unreadable = true, error = "Live TV settings could not be opened. Reset them below to configure your sources again.") }
                    } else {
                        data.update { it.copy(config = config, unreadable = false, error = if (it.unreadable) null else it.error) }
                        if (config.sources != previousSources) {
                            previousSources = config.sources
                            channels.value = emptyList()
                            if (browsing) refresh()
                        }
                    }
                }
            }
        }
    }

    fun startBrowsing() { browsing = true; refresh() }

    fun refresh() {
        loadingJob?.cancel()
        val profileId = data.value.profileId
        val sources = data.value.config.sources.filter { it.enabled }
        loadingJob = viewModelScope.launch {
            data.update { it.copy(loading = true, error = null) }
            val loaded = ArrayList<LiveTvChannel>()
            val failed = ArrayList<String>()
            for (source in sources) {
                ensureActive()
                try {
                    val result = client.channels(source)
                    loaded.addAll(result.take(M3uParser.MAX_CHANNELS - loaded.size))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed.add(source.name) }
                if (loaded.size >= M3uParser.MAX_CHANNELS) break
            }
            if (profiles.activeProfileId.value != profileId) return@launch
            channels.value = loaded.distinctBy { it.id }
            val groups = withContext(Dispatchers.Default) { loaded.mapNotNull { it.group?.takeIf(String::isNotBlank) }.distinct().sorted() }
            data.update { it.copy(loading = false, groups = groups, error = when {
                failed.isNotEmpty() -> "Could not load: ${failed.joinToString()}. Check the source settings and connection."
                loaded.size >= M3uParser.MAX_CHANNELS -> "Showing the first ${M3uParser.MAX_CHANNELS} channels."
                else -> null
            }) }
        }
    }

    fun save(source: LiveTvSource, onSaved: () -> Unit) {
        val url = source.url.trim()
        val validUrl = if (source.type == LiveTvSourceType.LOCAL_M3U) url.startsWith("content://") else url.toHttpUrlOrNull() != null
        val error = when {
            source.name.isBlank() -> "Enter a source name."
            !validUrl -> "Enter a valid HTTP or HTTPS address."
            source.type == LiveTvSourceType.XTREAM && (source.username.isBlank() || source.password.isBlank()) -> "Enter the Xtream username and password."
            source.type == LiveTvSourceType.STALKER && !source.macAddress.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) -> "Enter a MAC address in the format 00:1A:79:00:00:00."
            else -> null
        }
        if (error != null) { data.update { it.copy(error = error) }; return }
        val saved = source.copy(id = source.id.ifBlank { UUID.randomUUID().toString() }, name = source.name.trim(), url = url)
        mutate(onSaved) { current ->
            require(current.sources.size < 20 || current.sources.any { it.id == saved.id })
            current.copy(sources = current.sources.filterNot { it.id == saved.id } + saved)
        }
    }

    fun remove(source: LiveTvSource) = mutate {
        val prefix = source.id + ":"
        it.copy(sources = it.sources.filterNot { entry -> entry.id == source.id },
            favorites = it.favorites.filterNotTo(mutableSetOf()) { id -> id.startsWith(prefix) },
            lastWatchedId = it.lastWatchedId?.takeUnless { id -> id.startsWith(prefix) })
    }
    fun setEnabled(source: LiveTvSource) = mutate { it.copy(sources = it.sources.map { entry -> if (entry.id == source.id) entry.copy(enabled = !entry.enabled) else entry }) }
    fun toggleNavigation() = mutate { it.copy(showInNavigation = !it.showInNavigation) }
    fun favorite(channel: LiveTvChannel) = mutate { it.copy(favorites = if (channel.id in it.favorites) it.favorites - channel.id else it.favorites + channel.id) }

    fun reset() {
        val id = data.value.profileId
        viewModelScope.launch {
            try { preferences.reset(id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (profiles.activeProfileId.value == id) showError("Could not reset Live TV settings.") }
        }
    }
    fun showError(message: String) { data.update { it.copy(error = message) } }

    private fun mutate(onSaved: () -> Unit = {}, transform: (LiveTvConfig) -> LiveTvConfig) {
        val profileId = data.value.profileId
        viewModelScope.launch {
            data.update { it.copy(saving = true, error = null) }
            try {
                preferences.update(profileId, transform)
                if (profiles.activeProfileId.value == profileId) onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (profiles.activeProfileId.value == profileId) showError("Could not save Live TV settings. Up to 20 sources are supported.") }
            finally { if (profiles.activeProfileId.value == profileId) data.update { it.copy(saving = false) } }
        }
    }

    fun play(channel: LiveTvChannel, onPlay: (LiveTvChannel, Int) -> Unit) {
        val profileId = data.value.profileId
        val source = data.value.config.sources.find { it.id == channel.sourceId && it.enabled } ?: return
        playingJob?.cancel()
        playingJob = viewModelScope.launch {
            data.update { it.copy(preparing = true, error = null) }
            try {
                val playable = client.prepare(channel, source)
                ensureActive()
                if (profiles.activeProfileId.value == profileId) {
                    preferences.update(profileId) { it.copy(lastWatchedId = channel.id) }
                    ensureActive()
                    if (profiles.activeProfileId.value == profileId) onPlay(playable, profileId)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (profiles.activeProfileId.value == profileId) showError("The channel could not be opened. Check the source or try another channel.") }
            finally { if (profiles.activeProfileId.value == profileId) data.update { it.copy(preparing = false) } }
        }
    }
}
