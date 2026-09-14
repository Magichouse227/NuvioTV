package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import kotlin.random.Random

object RandomEpisodePolicy {
    fun choose(episodes: List<Video>, watched: Set<Pair<Int, Int>>, includeWatched: Boolean,
               random: Random = Random.Default): Video? =
        episodes.filter {
            val season = it.season
            val episode = it.episode
            season != null && season > 0 && episode != null && episode > 0 &&
                it.available != false && isEpisodeReleaseAired(it.released) != false &&
                (includeWatched || (season to episode) !in watched)
        }.randomOrNull(random)
}
