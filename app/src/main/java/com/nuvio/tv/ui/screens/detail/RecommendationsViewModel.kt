package com.nuvio.tv.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.core.tmdb.TmdbService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecommendationsState(
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class RecommendationsViewModel @Inject constructor(
    private val tmdb: TmdbMetadataService,
    private val tmdbIds: TmdbService,
    private val settings: TmdbSettingsDataStore,
    private val trakt: TraktRelatedService
) : ViewModel() {
    private val state = MutableStateFlow(RecommendationsState())
    val uiState = state.asStateFlow()
    private var meta: Meta? = null
    private var source = MoreLikeThisSource.TMDB
    private var page = 1
    private var job: Job? = null

    fun open(value: Meta, provider: MoreLikeThisSource) {
        job?.cancel()
        meta = value
        source = provider
        page = 1
        state.value = RecommendationsState()
        more()
    }

    fun stop() { job?.cancel() }

    fun more() {
        if (state.value.loading || state.value.endReached) return
        val target = meta ?: return
        val requestedPage = page
        state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val (items, end) = if (source == MoreLikeThisSource.TRAKT) {
                    val results = trakt.getRelated(target, page = requestedPage)
                    results to (results.size < 20 || requestedPage >= 500)
                } else {
                    val config = settings.settings.first()
                    val id = tmdbIds.ensureTmdbId(target.id, target.apiType)
                        ?: throw IllegalStateException("No TMDB identifier")
                    val result = tmdb.fetchRecommendationPage(id, target.type, config.language, requestedPage)
                    result.items to result.endReached
                }
                val previous = state.value.items
                val combined = (previous + items).distinctBy { it.apiType + ":" + it.id }
                // Stop if a provider ignores pagination and repeats a page.
                state.update { it.copy(items = combined, loading = false,
                    endReached = end || (requestedPage > 1 && combined.size == previous.size)) }
                page++
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                state.update { it.copy(loading = false, error = "Could not load recommendations. Try again.") }
            }
        }
    }
}
