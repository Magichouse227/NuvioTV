package com.nuvio.tv.updater

internal object UpdateIdentity {
    fun of(tag: String, buildMarker: String?): String =
        buildMarker
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "$tag:$it" }
            ?: tag
}

internal object UpdateDownloadPolicy {
    fun shouldRetainDownloadedApk(
        previousUpdateIdentity: String?,
        currentUpdateIdentity: String?,
        downloadedApkPath: String?,
        pathExists: (String) -> Boolean = { true }
    ): Boolean {
        return downloadedApkPath != null &&
            currentUpdateIdentity != null &&
            previousUpdateIdentity == currentUpdateIdentity &&
            pathExists(downloadedApkPath)
    }
}