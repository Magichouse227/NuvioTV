package com.nuvio.tv.core.startup

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * In-process startup timing markers.
 *
 * These markers intentionally contain only a small, fixed set of elapsed durations. They are
 * never persisted, attached to diagnostics, or exported over the network, so they cannot include
 * account, profile, content, URL, or device-identifying data.
 */
object StartupTimingMarkers {
    private const val TAG = "NuvioStartupTiming"
    private const val MAX_MARKERS = 6

    private var startedAtMs: Long? = null
    private val recordedMarkers = StartupTimingMarkerBuffer(MAX_MARKERS)

    /**
     * Called from Application.attachBaseContext, before Application.onCreate or dependency setup.
     * API 24+ exposes the process start elapsed time; older devices use this earliest app hook.
     */
    @Synchronized
    fun initializeProcessStart() {
        if (startedAtMs != null) return
        startedAtMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Process.getStartElapsedRealtime()
        } else {
            SystemClock.elapsedRealtime()
        }
    }

    @Synchronized
    fun markApplicationCreated() = mark("cold_start")

    @Synchronized
    fun markActivityCreated() = mark("activity_created")

    @Synchronized
    fun markFirstFrame() = mark("first_frame")

    @Synchronized
    fun markFirstInput() = mark("first_input")

    @Synchronized
    fun markFirstInteractive() = mark("first_interactive")

    @Synchronized
    fun markHomeShellReady() = mark("home_shell_ready")

    private fun mark(name: String) {
        val now = SystemClock.elapsedRealtime()
        val start = startedAtMs ?: now.also { startedAtMs = it }
        val elapsedMs = (now - start).coerceAtLeast(0L)
        if (!recordedMarkers.record(name, elapsedMs)) return
        // Logcat is local to this device/process. Keep this fixed-format message free of PII.
        Log.i(TAG, "marker=$name elapsed_ms=$elapsedMs")
    }
}