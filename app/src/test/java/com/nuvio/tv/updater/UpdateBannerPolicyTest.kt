package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateBannerPolicyTest {

    @Test
    fun `automatic check shows a new update when the banner is enabled`() {
        assertTrue(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = true,
                force = false,
                bannerEnabled = true,
                dismissedTag = null,
                updateTag = "v1.2.0"
            )
        )
    }

    @Test
    fun `dismissed update stays hidden during automatic checks`() {
        assertFalse(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = true,
                force = false,
                bannerEnabled = true,
                dismissedTag = "v1.2.0",
                updateTag = "v1.2.0"
            )
        )
    }

    @Test
    fun `a newer tag is shown after the previous update was dismissed`() {
        assertTrue(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = true,
                force = false,
                bannerEnabled = true,
                dismissedTag = "v1.2.0",
                updateTag = "v1.3.0"
            )
        )
    }

    @Test
    fun `a new fork build marker is shown after the previous marker was dismissed`() {
        val dismissedIdentity = UpdateIdentity.of(FORK_TEST_TAG, "old-sha")
        val currentIdentity = UpdateIdentity.of(FORK_TEST_TAG, "new-sha")

        assertTrue(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = true,
                force = false,
                bannerEnabled = true,
                dismissedTag = dismissedIdentity,
                updateTag = currentIdentity
            )
        )
    }

    @Test
    fun `a stale downloaded apk is invalidated when the fork build marker changes`() {
        assertFalse(
            UpdateDownloadPolicy.shouldRetainDownloadedApk(
                previousUpdateIdentity = UpdateIdentity.of(FORK_TEST_TAG, "old-sha"),
                currentUpdateIdentity = UpdateIdentity.of(FORK_TEST_TAG, "new-sha"),
                downloadedApkPath = "/cache/old.apk",
                pathExists = { true }
            )
        )
    }

    @Test
    fun `a downloaded apk is retained for the same fork build identity`() {
        val identity = UpdateIdentity.of(FORK_TEST_TAG, "same-sha")

        assertTrue(
            UpdateDownloadPolicy.shouldRetainDownloadedApk(
                previousUpdateIdentity = identity,
                currentUpdateIdentity = identity,
                downloadedApkPath = "/cache/current.apk",
                pathExists = { true }
            )
        )
    }

    @Test
    fun `manual check shows the update despite dismissal and disabled banner`() {
        assertTrue(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = true,
                force = true,
                bannerEnabled = false,
                dismissedTag = "v1.2.0",
                updateTag = "v1.2.0"
            )
        )
    }

    @Test
    fun `current version never shows as an update`() {
        assertFalse(
            UpdateBannerPolicy.shouldShow(
                isRemoteNewer = false,
                force = true,
                bannerEnabled = true,
                dismissedTag = null,
                updateTag = "v1.2.0"
            )
        )
    }
}
