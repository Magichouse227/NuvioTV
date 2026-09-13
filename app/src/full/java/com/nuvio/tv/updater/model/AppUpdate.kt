package com.nuvio.tv.updater.model

import com.nuvio.tv.updater.UpdateIdentity

data class AppUpdate(
    val tag: String,
    val buildMarker: String?,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?
) {
    val identity: String
        get() = UpdateIdentity.of(tag, buildMarker)
}
