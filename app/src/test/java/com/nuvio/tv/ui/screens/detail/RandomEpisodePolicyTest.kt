package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class RandomEpisodePolicyTest {
    private fun episode(number: Int, released: String? = "2020-01-01", available: Boolean? = true) =
        Video(id = "episode:$number", title = "Episode $number", released = released, thumbnail = null,
            season = 1, episode = number, overview = null, available = available)

    @Test fun neverSelectsFutureOrUnavailableEpisodes() {
        val future = episode(2, LocalDate.now().plusDays(2).toString())
        assertEquals("episode:1", RandomEpisodePolicy.choose(
            listOf(episode(1), future, episode(3, available = false)), emptySet(), true, Random(1))?.id)
    }

    @Test fun watchedEpisodesRequireExplicitOptIn() {
        val watched = setOf(1 to 1)
        assertNull(RandomEpisodePolicy.choose(listOf(episode(1)), watched, false))
        assertNotNull(RandomEpisodePolicy.choose(listOf(episode(1)), watched, true))
    }

    @Test fun emptyAndSpecialOnlyListsReturnNoEpisode() {
        assertNull(RandomEpisodePolicy.choose(emptyList(), emptySet(), false))
        assertNull(RandomEpisodePolicy.choose(listOf(episode(1).copy(season = 0)), emptySet(), true))
    }
}
