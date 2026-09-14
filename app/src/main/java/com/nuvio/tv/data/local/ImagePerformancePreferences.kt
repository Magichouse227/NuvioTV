package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.build.LowRamDevicePolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ImagePerformancePreferences @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val reduceHomeEffectsDefault = LowRamDevicePolicy.isLowRam(context)
    private val _reduceHomeEffects = MutableStateFlow(
        resolveReduceHomeEffects(
            lowRamDevice = reduceHomeEffectsDefault,
            override = readReduceHomeEffectsOverride()
        )
    )

    val rgb565Enabled: Boolean
        get() = preferences.getBoolean(KEY_RGB565_ENABLED, true)

    val reduceHomeEffects: Boolean
        get() = _reduceHomeEffects.value

    val reduceHomeEffectsFlow: StateFlow<Boolean> = _reduceHomeEffects.asStateFlow()

    fun setRgb565Enabled(enabled: Boolean): Boolean {
        return preferences.edit()
            .putBoolean(KEY_RGB565_ENABLED, enabled)
            .commit()
    }

    fun setReduceHomeEffects(enabled: Boolean): Boolean {
        val committed = preferences.edit()
            .putBoolean(KEY_REDUCE_HOME_EFFECTS, enabled)
            .commit()
        if (committed) {
            _reduceHomeEffects.value = enabled
        }
        return committed
    }

    private fun readReduceHomeEffectsOverride(): Boolean? =
        if (preferences.contains(KEY_REDUCE_HOME_EFFECTS)) {
            preferences.getBoolean(KEY_REDUCE_HOME_EFFECTS, reduceHomeEffectsDefault)
        } else {
            null
        }

    private companion object {
        const val PREFERENCES_NAME = "image_performance"
        const val KEY_RGB565_ENABLED = "rgb565_enabled"
        const val KEY_REDUCE_HOME_EFFECTS = "reduce_home_effects"
    }
}

internal fun resolveReduceHomeEffects(
    lowRamDevice: Boolean,
    override: Boolean?
): Boolean = override ?: lowRamDevice

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImagePerformancePreferencesEntryPoint {
    fun imagePerformancePreferences(): ImagePerformancePreferences
}

internal fun imagePerformancePreferences(context: Context): ImagePerformancePreferences =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ImagePerformancePreferencesEntryPoint::class.java
    ).imagePerformancePreferences()
