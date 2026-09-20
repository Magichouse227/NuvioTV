package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

internal data class InsightsTitleKey(val type: String, val id: String)
internal data class InsightsActivityKey(val title: InsightsTitleKey, val season: Int?, val episode: Int?, val video: String? = null)
internal data class InsightsGenre(val name: String, val percent: Int)
internal enum class InsightsBadge { MOVIE_FAN, SERIES_FAN, EXPLORER, BINGE_READY, HIGHLY_ACTIVE, COLLECTOR, RELEASE_RADAR, COMPLETIONIST }

internal fun insightsTitleKey(type: String, id: String): InsightsTitleKey? {
    val normalized = when (type.trim().lowercase(Locale.ROOT)) {
        "movie" -> "movie"
        "series", "tv", "anime" -> "series"
        else -> return null
    }
    return id.trim().takeIf { it.isNotEmpty() }?.let { InsightsTitleKey(normalized, it) }
}

/** Retain only the metadata used by the dashboard, not artwork, cast or stream arrays. */
internal data class InsightsTitleInfo(
    val ended: Boolean = false,
    val episodes: Set<Pair<Int, Int>> = emptySet(),
    val runtimeMs: Long? = null,
    val episodeRuntimeMs: Map<Pair<Int, Int>, Long> = emptyMap(),
    val releaseDates: List<LocalDate> = emptyList()
) {
    companion object {
        fun from(meta: Meta): InsightsTitleInfo {
            val regular = meta.videos.filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
            return InsightsTitleInfo(
                ended = meta.status?.trim()?.lowercase(Locale.ROOT) in setOf("ended", "canceled", "cancelled"),
                episodes = regular.map { it.season!! to it.episode!! }.toSet(),
                runtimeMs = insightsRuntimeMs(meta.runtime),
                episodeRuntimeMs = regular.mapNotNull { video ->
                    video.runtime?.takeIf { it in 1..1440 }?.let { (video.season!! to video.episode!!) to it * 60_000L }
                }.toMap(),
                releaseDates = (listOf(meta.released) + regular.map { it.released })
                    .mapNotNull(::insightsReleaseDate).distinct()
            )
        }
    }
}

internal fun insightsReleaseDate(raw: String?): LocalDate? = raw?.trim()?.takeIf {
    // A year or a year range is not an announced release day.
    it.matches(Regex("\\d{4}-\\d{2}-\\d{2}(?:[T ].*)?"))
}?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

internal fun insightsRuntimeMs(raw: String?): Long? {
    val text = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
    val minutes = text.toLongOrNull() ?: run {
        val match = Regex("(?:(\\d+)\\s*(?:hours?|hrs?|h))?\\s*(?:(\\d+)\\s*(?:minutes?|mins?|m))?")
            .matchEntire(text) ?: return null
        val hours = match.groupValues[1].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
        val mins = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
        if (hours == null && mins == null) return null
        // Reject malformed values before arithmetic can overflow.
        if ((hours ?: 0) !in 0..24 || (mins ?: 0) !in 0..1440) return null
        (hours ?: 0) * 60 + (mins ?: 0)
    }
    return minutes.takeIf { it in 1..1440 }?.times(60_000L)
}

internal data class ProfileInsights(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val continueWatching: Int = 0,
    val upcoming: Int = 0,
    val completed: Int = 0,
    val ongoing: Int = 0,
    val saved: Int = 0,
    val watchTimeMs: Long = 0,
    val missingRuntime: Int = 0,
    val genres: List<InsightsGenre> = emptyList(),
    val movieShare: Float? = null,
    val badges: List<InsightsBadge> = emptyList(),
    val recent: List<WatchedItem> = emptyList(),
    val refreshing: Boolean = false,
    val metadataChecked: Int = 0,
    val metadataTotal: Int = 0,
    val error: Boolean = false
) {
    companion object {
        fun calculate(
            watched: List<WatchedItem>, library: List<SavedLibraryItem>, progress: List<WatchProgress>,
            metadata: Map<InsightsTitleKey, InsightsTitleInfo> = emptyMap(),
            today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()
        ): ProfileInsights {
            fun watchedKey(item: WatchedItem): InsightsActivityKey? = insightsTitleKey(item.contentType, item.contentId)?.let {
                InsightsActivityKey(it, item.season, item.episode)
            }
            fun progressKey(item: WatchProgress): InsightsActivityKey? = insightsTitleKey(item.contentType, item.contentId)?.let {
                InsightsActivityKey(it, item.season, item.episode,
                    if (it.type == "series" && (item.season == null || item.episode == null)) item.videoId else null)
            }
            val history = watched.sortedByDescending { it.watchedAt }.mapNotNull { item -> watchedKey(item)?.let { it to item } }
                .distinctBy { it.first }.toMap()
            val playback = progress.sortedByDescending { it.lastWatched }.mapNotNull { item -> progressKey(item)?.let { it to item } }
                .distinctBy { it.first }.toMap()
            val savedTitles = library.mapNotNull { item -> insightsTitleKey(item.type, item.id)?.let { it to item } }
                .distinctBy { it.first }.toMap()
            val completedActivities = history.keys + playback.filterValues { it.isCompleted() }.keys
            val completedTitles = completedActivities.filter { it.title.type == "movie" }.map { it.title }.toMutableSet()
            val startedSeries = (history.keys + playback.filterValues { it.position > 0 || it.progressPercentage > 0 }.keys)
                .map { it.title }.filter { it.type == "series" }.toSet()
            val explicitlyFinished = history.keys.filter { it.season == null && it.episode == null }.map { it.title }.toSet()
            val episodesByTitle = completedActivities.filter { it.season != null && it.episode != null }
                .groupBy { it.title }.mapValues { (_, episodes) -> episodes.map { it.season!! to it.episode!! }.toSet() }
            for (title in startedSeries) {
                val info = metadata[title]
                if (title in explicitlyFinished || (info?.ended == true && info.episodes.isNotEmpty() &&
                        episodesByTitle[title].orEmpty().containsAll(info.episodes))) completedTitles += title
            }
            // Use only the latest progress per title, so an old partial episode does not inflate Continue Watching.
            val continueTitles = playback.entries.sortedByDescending { it.value.lastWatched }.distinctBy { it.key.title }
                .filter { (key, item) -> key !in completedActivities && key.title !in completedTitles &&
                    (item.isInProgress() || (item.duration <= 0 && item.position > 0 && !item.isCompleted())) }
                .map { it.key.title }.toSet()
            val upcoming = savedTitles.count { (key, item) ->
                val dates = metadata[key]?.releaseDates.orEmpty() + listOfNotNull(insightsReleaseDate(item.releaseInfo))
                dates.any { !it.isBefore(today) }
            }

            // One duration per movie/episode. Synced watched flags and local progress must not count twice.
            var missingRuntime = 0
            val activityKeys = (history.keys + playback.keys).filter {
                it.title.type == "movie" || (it.season != null && it.episode != null) || it.video != null
            }
            val time = activityKeys.sumOf { key ->
                val played = playback[key]
                val info = metadata[key.title]
                val episodeRuntime = if (key.season != null && key.episode != null)
                    info?.episodeRuntimeMs?.get(key.season to key.episode) else null
                val duration = played?.duration?.takeIf { it > 0 } ?: if (key.title.type == "movie") info?.runtimeMs
                    else episodeRuntime ?: info?.runtimeMs
                when {
                    key in completedActivities && duration != null -> duration
                    key in completedActivities -> { missingRuntime++; played?.position?.coerceAtLeast(0) ?: 0L }
                    played != null && duration != null -> played.resolveResumePosition(duration)
                    played != null -> played.position.coerceAtLeast(0)
                    else -> 0L
                }
            }

            val genreCounts = savedTitles.values.flatMap { item -> item.genres.map(String::trim)
                .filter(String::isNotEmpty).distinctBy { it.lowercase(Locale.ROOT) } }
                .groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
            val genreNames = savedTitles.values.flatMap { it.genres }.associateBy { it.trim().lowercase(Locale.ROOT) }
            val genreTotal = genreCounts.values.sum().coerceAtLeast(1)
            val genres = genreCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3).map { InsightsGenre(genreNames[it.key]?.trim() ?: it.key, (100f * it.value / genreTotal).roundToInt()) }
            val allTitles = savedTitles.keys + history.keys.map { it.title } + playback.keys.map { it.title }
            val share = allTitles.takeIf { it.isNotEmpty() }?.let { titles -> titles.count { it.type == "movie" }.toFloat() / titles.size }
            val activityTimes = history.mapValues { it.value.watchedAt }.toMutableMap()
            playback.forEach { (key, item) -> activityTimes[key] = maxOf(activityTimes[key] ?: 0L, item.lastWatched) }
            val recentActivity = activityTimes.values.count {
                it > 0 && Instant.ofEpochMilli(it).atZone(zone).toLocalDate() in today.minusDays(6)..today
            }
            val badges = buildList {
                when {
                    share == null -> Unit
                    share >= 0.62f -> add(InsightsBadge.MOVIE_FAN)
                    share <= 0.38f -> add(InsightsBadge.SERIES_FAN)
                    else -> add(InsightsBadge.EXPLORER)
                }
                if (continueTitles.size >= 3) add(InsightsBadge.BINGE_READY)
                if (recentActivity >= 5) add(InsightsBadge.HIGHLY_ACTIVE)
                if (savedTitles.size >= 12) add(InsightsBadge.COLLECTOR)
                if (upcoming > 0) add(InsightsBadge.RELEASE_RADAR)
                if (completedTitles.size >= 10) add(InsightsBadge.COMPLETIONIST)
            }.take(4)
            return ProfileInsights(
                loading = false, continueWatching = continueTitles.size, upcoming = upcoming,
                completed = completedTitles.size, ongoing = (startedSeries - completedTitles).size,
                saved = savedTitles.size, watchTimeMs = time, missingRuntime = missingRuntime,
                genres = genres, movieShare = share, badges = badges,
                recent = history.values.sortedByDescending { it.watchedAt }.take(20)
            )
        }
    }
}
