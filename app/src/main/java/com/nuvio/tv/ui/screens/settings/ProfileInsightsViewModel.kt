package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private data class InsightsSnapshot(
    val watched: List<WatchedItem>, val saved: List<SavedLibraryItem>,
    val progress: List<WatchProgress>, val profile: UserProfile?
)
private data class InsightsRequest(val type: String, val id: String, val addon: String? = null) {
    val key get() = insightsTitleKey(type, id)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileInsightsViewModel @Inject constructor(
    profiles: ProfileManager,
    watched: WatchedItemsPreferences,
    library: LibraryPreferences,
    progress: WatchProgressPreferences,
    private val metadata: MetaRepository,
    avatars: AvatarRepository,
    membership: MemberAccessRepository
) : ViewModel() {
    private val refreshCounter = MutableStateFlow(0)
    fun refresh() { refreshCounter.update { it + 1 } }

    internal val avatar: StateFlow<Pair<Int?, String?>> = combine(profiles.profiles, profiles.activeProfileId, membership.access) { all, id, access ->
        all.firstOrNull { it.id == id } to access.entitlements.includes(CosmeticEntitlement.PROFILE_AVATARS)
    }.mapLatest { (profile, memberAccess) ->
        val url = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: profile?.avatarId?.let { id ->
            try {
                withTimeoutOrNull(8_000) { avatars.getAvatarImageUrl(id, avatars.getAvatarCatalog(memberAccess)) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        }
        profile?.id to url
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), null to null)

    internal val state = profiles.activeProfileId.flatMapLatest { id ->
        // All stores are scoped to this profile; switching cancels metadata requests.
        refreshCounter.flatMapLatest {
            combine(watched.observeAllItems(id), library.observeItems(id), progress.observeAllRawProgress(id),
                profiles.profiles) { items, saved, playback, allProfiles ->
                InsightsSnapshot(items, saved, playback, allProfiles.firstOrNull { it.id == id })
            }.flatMapLatest(::insights)
                .onStart { emit(ProfileInsights()) }
                .catch { emit(ProfileInsights(loading = false, error = true)) }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), ProfileInsights())

    private fun insights(snapshot: InsightsSnapshot): Flow<ProfileInsights> = flow {
        val requests = buildList {
            snapshot.saved.sortedByDescending { it.addedAt }.forEach { add(InsightsRequest(it.type, it.id, it.addonBaseUrl)) }
            snapshot.progress.sortedByDescending { it.lastWatched }.forEach { add(InsightsRequest(it.contentType, it.contentId, it.addonBaseUrl)) }
            snapshot.watched.sortedByDescending { it.watchedAt }.forEach { add(InsightsRequest(it.contentType, it.contentId)) }
        }.filter { it.key != null }.distinctBy { it.key }
        val info = mutableMapOf<InsightsTitleKey, InsightsTitleInfo>()
        fun result(refreshing: Boolean) = ProfileInsights.calculate(snapshot.watched, snapshot.saved, snapshot.progress, info)
            .copy(profile = snapshot.profile, refreshing = refreshing, metadataChecked = info.size, metadataTotal = requests.size)
        emit(result(requests.isNotEmpty()))
        requests.forEach { request ->
            currentCoroutineContext().ensureActive()
            metadata.getCachedMeta(request.type, request.id)?.let { info[request.key!!] = InsightsTitleInfo.from(it) }
        }
        emit(result(requests.size > info.size))
        // Bound each visit to 40 titles and two concurrent requests. Closing the dialog cancels the flow.
        for (batch in requests.filter { it.key !in info }.take(40).chunked(2)) {
            val loaded = coroutineScope { batch.map { request -> async(Dispatchers.IO) {
                request.key!! to try {
                    withTimeoutOrNull(12_000) {
                        when (val response = metadata.getMetaFromAllAddons(request.type, request.id, request.addon)
                            .firstOrNull { it is NetworkResult.Success || it is NetworkResult.Error }) {
                            is NetworkResult.Success -> InsightsTitleInfo.from(response.data)
                            else -> null
                        }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
            } }.awaitAll() }
            loaded.forEach { (key, value) -> if (value != null) info[key] = value }
            emit(result(true))
        }
        emit(result(false))
    }
}
