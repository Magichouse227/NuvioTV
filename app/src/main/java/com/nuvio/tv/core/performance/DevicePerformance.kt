package com.nuvio.tv.core.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build

object DevicePerformance {
    @Volatile var policy: TvMemoryPolicy = TvMemoryPolicy(false)
        private set
    val lightweight: Boolean get() = policy.lightweight

    fun initialize(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        policy = TvMemoryPolicy.detect(Build.MANUFACTURER, Build.MODEL, info.totalMem, manager?.isLowRamDevice == true)
    }
}
