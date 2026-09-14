package com.nuvio.tv.core.startup

/**
 * Small pure buffer behind the local timing logger. Keeping the buffer bounded avoids retaining
 * process history, while the fixed caller-owned marker names prevent data from entering it.
 */
internal class StartupTimingMarkerBuffer(
    private val maxMarkers: Int
) {
    private val markers = LinkedHashMap<String, Long>(maxMarkers)

    fun record(name: String, elapsedMs: Long): Boolean {
        if (name in markers || markers.size >= maxMarkers) return false
        markers[name] = elapsedMs.coerceAtLeast(0L)
        return true
    }

    fun snapshot(): Map<String, Long> = markers.toMap()
}