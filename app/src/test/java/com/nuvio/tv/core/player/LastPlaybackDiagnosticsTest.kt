package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastPlaybackDiagnosticsTest {

    @Test
    fun buildProvenanceRoundTripsWithSnapshot() {
        val snapshot = LastPlaybackDiagnostics(
            timestampMs = 1_757_797_927_000L,
            host = "stream.example",
            appVersionName = "0.9.2-beta",
            appVersionCode = 1058L,
            testBuildSha = "public-build-id",
            result = "Error: parser"
        )

        val restored = LastPlaybackDiagnostics.fromJson(snapshot.toJson())

        assertEquals(snapshot, restored)
    }

    @Test
    fun oldSnapshotDoesNotInferCurrentBuildIdentity() {
        val restored = LastPlaybackDiagnostics.fromJson(
            """{"timestampMs":1757797927000,"host":"stream.example","result":"Played"}"""
        )

        assertNull(restored.appVersionName)
        assertNull(restored.appVersionCode)
        assertNull(restored.testBuildSha)
    }
}