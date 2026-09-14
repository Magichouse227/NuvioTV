package com.nuvio.tv.data.repository

import com.nuvio.tv.core.player.LastPlaybackDiagnostics

private const val UNKNOWN_SNAPSHOT_PROVENANCE = "Unknown"

/**
 * Small allowlisted section for locally saved playback issue reports.
 *
 * This intentionally exports only the snapshot timestamp and build provenance. Stream URLs,
 * headers, credentials, and arbitrary diagnostic strings are not part of this section.
 */
internal fun LastPlaybackDiagnostics.toPlaybackSnapshotProvenanceLines(): List<String> = listOf(
    "playback_snapshot.capturedAtMs=${timestampMs.takeIf { it > 0L } ?: UNKNOWN_SNAPSHOT_PROVENANCE}",
    "playback_snapshot.versionName=${appVersionName.provenanceValue()}",
    "playback_snapshot.versionCode=${appVersionCode ?: UNKNOWN_SNAPSHOT_PROVENANCE}",
    "playback_snapshot.testBuildSha=${testBuildSha.provenanceValue()}"
)

private fun String?.provenanceValue(): String =
    this
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() }
        ?.take(120)
        ?: UNKNOWN_SNAPSHOT_PROVENANCE