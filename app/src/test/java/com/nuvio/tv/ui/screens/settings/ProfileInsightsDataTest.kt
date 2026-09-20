package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class ProfileInsightsDataTest {
    private val today = LocalDate.of(2026, 9, 17)
    private val now = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun watched(id: String = "one", type: String = "movie", episode: Int? = null) =
        WatchedItem(id, type, id, season = episode?.let { 1 }, episode = episode, watchedAt = now)
    private fun saved(id: String = "one", type: String = "movie", date: String? = null, genres: List<String> = emptyList()) =
        SavedLibraryItem(id, type, id, null, PosterShape.POSTER, null, null, date, null, genres, null)
    private fun progress(id: String = "one", type: String = "movie", episode: Int? = null, position: Long = 30_000L,
        duration: Long = 60_000L, lastWatched: Long = now) =
        WatchProgress(id, type, id, null, null, null, "$id:${episode ?: 0}", episode?.let { 1 }, episode,
            null, position, duration, lastWatched)
    private fun calculate(watched: List<WatchedItem> = emptyList(), library: List<SavedLibraryItem> = emptyList(),
        progress: List<WatchProgress> = emptyList(), metadata: Map<InsightsTitleKey, InsightsTitleInfo> = emptyMap()) =
        ProfileInsights.calculate(watched, library, progress, metadata, today, ZoneOffset.UTC)

    @Test fun watchedSyncAndPlaybackCountOncePerMovie() {
        val result = calculate(listOf(watched(), watched().copy(watchedAt = now - 100)),
            listOf(saved(), saved()), listOf(progress(position = 59_000), progress(position = 10_000, lastWatched = now - 10)))
        assertEquals(1, result.completed)
        assertEquals(1, result.saved)
        assertEquals(60_000L, result.watchTimeMs)
        assertEquals(0, result.continueWatching)
    }

    @Test fun completingAnEpisodeDoesNotCompleteASeries() {
        val result = calculate(listOf(watched(type = "series", episode = 1)))
        assertEquals(0, result.completed)
        assertEquals(1, result.ongoing)
        assertEquals(1, result.missingRuntime)
        assertEquals(0L, result.watchTimeMs)
    }

    @Test fun endedSeriesRequiresEveryKnownEpisodeAndCountsOneTitle() {
        val key = InsightsTitleKey("series", "one")
        val info = mapOf(key to InsightsTitleInfo(ended = true, episodes = setOf(1 to 1, 1 to 2), runtimeMs = 60_000L))
        assertEquals(0, calculate(listOf(watched(type = "series", episode = 1)), metadata = info).completed)
        val result = calculate(listOf(watched(type = "series", episode = 1), watched(type = "tv", episode = 2)), metadata = info)
        assertEquals(1, result.completed)
        assertEquals(0, result.ongoing)
        assertEquals(120_000L, result.watchTimeMs)
        assertEquals(0, calculate(listOf(watched(type = "series", episode = 1), watched(type = "series", episode = 2)),
            metadata = mapOf(key to info.getValue(key).copy(ended = false))).completed)
    }

    @Test fun explicitSeriesMarkIsRespectedWithoutInventingWatchTime() {
        val result = calculate(listOf(watched(type = "series")))
        assertEquals(1, result.completed)
        assertEquals(0, result.ongoing)
        assertEquals(0L, result.watchTimeMs)
    }

    @Test fun upcomingUsesAnnouncedDatesIncludingEpisodesAndIgnoresBareYears() {
        val library = listOf(saved("today", date = today.toString()), saved("past", date = "2020-01-01"),
            saved("year", date = "2027"), saved("range", date = "2026–"), saved("invalid", date = "2026-02-30"),
            saved("series", type = "series"))
        val result = calculate(library = library, metadata = mapOf(InsightsTitleKey("series", "series") to
            InsightsTitleInfo(releaseDates = listOf(today.plusDays(4), today.plusDays(11)))))
        assertEquals(2, result.upcoming)
    }

    @Test fun continueWatchingUsesLatestEpisodeAndRetainsUnknownDurationResume() {
        val playback = listOf(progress(type = "series", episode = 1, lastWatched = now - 20),
            progress(type = "series", episode = 2, position = 60_000), progress("unknown", duration = 0))
        val result = calculate(progress = playback)
        assertEquals(1, result.continueWatching)
        assertEquals(1, result.ongoing)
        assertEquals(120_000L, result.watchTimeMs)
    }

    @Test fun genresDeduplicateCaseAndBalanceCountsTitlesInsteadOfEpisodes() {
        val result = calculate(
            watched = (1..20).map { watched(type = "series", episode = it) },
            library = listOf(saved(genres = listOf("Horror", " horror ", "Science Fiction")),
                saved("one", "series", genres = listOf("HORROR"))))
        assertEquals(0.5f, result.movieShare!!, 0.001f)
        assertEquals(listOf(67, 33), result.genres.map { it.percent })
    }

    @Test fun emptyOrOtherProfileDataNeverCarriesOverPreviousStatistics() {
        assertEquals(1, calculate(listOf(watched())).completed)
        val empty = calculate()
        assertEquals(0, empty.completed)
        assertEquals(0, empty.saved)
        assertNull(empty.movieShare)
        assertTrue(empty.genres.isEmpty())
        assertEquals(0, calculate(library = listOf(saved(type = "tvchannel")), progress = listOf(progress(type = "tvchannel"))).saved)
    }

    @Test fun syncedProgressDoesNotInflateActivityBadge() {
        val result = calculate((1..3).map { watched("$it") }, progress = (1..3).map { progress("$it", position = 60_000) })
        assertFalse(InsightsBadge.HIGHLY_ACTIVE in result.badges)
    }

    @Test fun runtimeParsingHandlesHoursAndRejectsAmbiguousOrInvalidValues() {
        assertEquals(9_000_000L, insightsRuntimeMs("2h 30min"))
        assertEquals(2_700_000L, insightsRuntimeMs("45 minutes"))
        assertEquals(5_400_000L, insightsRuntimeMs("90"))
        assertNull(insightsRuntimeMs("Unknown"))
        assertNull(insightsRuntimeMs("0 min"))
        assertNull(insightsRuntimeMs("-45 min"))
        assertNull(insightsRuntimeMs("unknown 45 min"))
        assertNull(insightsRuntimeMs("999999999999999999999 hours"))
        assertNull(insightsReleaseDate("2027"))
    }
}
