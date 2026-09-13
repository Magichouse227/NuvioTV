package com.nuvio.tv.core.build

import android.app.ActivityManager
import android.content.Context

/** Small, centralized guard for Android TV devices with very little heap. */
object LowRamDevicePolicy {
    fun isLowRam(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        if (manager.isLowRamDevice) return true
        val memoryInfo = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem <= 2_048L * 1024L * 1024L
    }
}