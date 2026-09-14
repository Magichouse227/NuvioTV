package com.nuvio.tv.core.livetv

import kotlinx.serialization.Serializable

@Serializable
enum class LiveTvSourceType { M3U, LOCAL_M3U, XTREAM, STALKER }

@Serializable
data class LiveTvSource(
    val id: String,
    val name: String,
    val type: LiveTvSourceType,
    val url: String,
    val username: String = "",
    val password: String = "",
    val macAddress: String = "",
    val enabled: Boolean = true
)

@Serializable
data class LiveTvConfig(
    val sources: List<LiveTvSource> = emptyList(),
    val showInNavigation: Boolean = true,
    val favorites: Set<String> = emptySet(),
    val lastWatchedId: String? = null
)

data class LiveTvChannel(
    val id: String,
    val sourceId: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val stalkerCommand: String? = null
)
