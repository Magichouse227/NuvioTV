package com.nuvio.tv.data.repository

import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotProvenanceTest {

    @Test
    fun localExportIncludesCapturedSnapshotIdentity() {
        val lines = LastPlaybackDiagnostics(
            timestampMs = 1_757_797_927_000L,
            appVersionName = "0.9.2-beta",
            appVersionCode = 1058L,
            testBuildSha = "public-build-id"
        ).toPlaybackSnapshotProvenanceLines()

        assertEquals(
            listOf(
                "playback_snapshot.capturedAtMs=1757797927000",
                "playback_snapshot.versionName=0.9.2-beta",
                "playback_snapshot.versionCode=1058",
                "playback_snapshot.testBuildSha=public-build-id"
            ),
            lines
        )
    }

    @Test
    fun localExportMarksLegacyBuildIdentityUnknown() {
        val lines = LastPlaybackDiagnostics(
            timestampMs = 1_757_797_927_000L
        ).toPlaybackSnapshotProvenanceLines()

        assertTrue(lines.contains("playback_snapshot.capturedAtMs=1757797927000"))
        assertTrue(lines.contains("playback_snapshot.versionName=Unknown"))
        assertTrue(lines.contains("playback_snapshot.versionCode=Unknown"))
        assertTrue(lines.contains("playback_snapshot.testBuildSha=Unknown"))
    }
}