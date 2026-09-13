package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateIdentityTest {
    @Test
    fun `fixed tags have distinct identities for different builds`() {
        assertFalse(UpdateIdentity.of(FORK_TEST_TAG, "old") == UpdateIdentity.of(FORK_TEST_TAG, "new"))
        assertEquals("1.2.3", UpdateIdentity.of("1.2.3", null))
        assertEquals(FORK_TEST_TAG, UpdateIdentity.of(FORK_TEST_TAG, " "))
    }

    @Test
    fun `download from prior build is never retained for a new build`() {
        assertFalse(
            UpdateDownloadPolicy.shouldRetainDownloadedApk(
                UpdateIdentity.of(FORK_TEST_TAG, "old"),
                UpdateIdentity.of(FORK_TEST_TAG, "new"),
                "/cache/update.apk"
            )
        )
    }

    @Test
    fun `same build download is retained only if its file exists`() {
        val identity = UpdateIdentity.of(FORK_TEST_TAG, "same")
        assertTrue(UpdateDownloadPolicy.shouldRetainDownloadedApk(identity, identity, "/cache/update.apk") { true })
        assertFalse(UpdateDownloadPolicy.shouldRetainDownloadedApk(identity, identity, "/cache/update.apk") { false })
        assertFalse(UpdateDownloadPolicy.shouldRetainDownloadedApk(identity, identity, null))
        assertFalse(UpdateDownloadPolicy.shouldRetainDownloadedApk(null, null, "/cache/update.apk"))
    }
}