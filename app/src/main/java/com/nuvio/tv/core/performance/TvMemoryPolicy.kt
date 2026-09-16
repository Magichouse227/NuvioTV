package com.nuvio.tv.core.performance

/** Budgets for 1 GB TV sticks. Values leave room for decoder surfaces and the UI. */
data class TvMemoryPolicy(val lightweight: Boolean) {
    val imageCacheBytes: Long get() = if (lightweight) 16L * 1024 * 1024 else Long.MAX_VALUE
    val imageDiskCacheBytes: Long get() = (if (lightweight) 64L else 200L) * 1024 * 1024
    val imageRequests: Int get() = if (lightweight) 6 else 32
    val imageRequestsPerHost: Int get() = if (lightweight) 3 else 16
    val imageDecoders: Int get() = if (lightweight) 1 else 4
    val playerBufferMb: Int get() = if (lightweight) 48 else Int.MAX_VALUE

    companion object {
        fun detect(manufacturer: String?, model: String?, totalRamBytes: Long, lowRam: Boolean): TvMemoryPolicy {
            val fireHd = manufacturer.equals("Amazon", ignoreCase = true) &&
                (model.equals("AFTSS", ignoreCase = true) || model.equals("AFTSSS", ignoreCase = true))
            val ramMb = totalRamBytes / (1024L * 1024L)
            return TvMemoryPolicy(lowRam || fireHd || ramMb in 1..1200)
        }
    }
}
