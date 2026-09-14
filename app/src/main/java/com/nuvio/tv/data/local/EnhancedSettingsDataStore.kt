package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class EnhancedSettings(
    val includeWatchedInRandom: Boolean = false,
    val dynamicBackground: Boolean = false,
    val catalogUnderline: Boolean = false
)

@Singleton
class EnhancedSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profiles: ProfileManager
) {
    private val randomKey = booleanPreferencesKey("random_include_watched")
    private val backgroundKey = booleanPreferencesKey("dynamic_background")
    private val underlineKey = booleanPreferencesKey("catalog_underline")
    val settings: Flow<EnhancedSettings> = profiles.activeProfileId.flatMapLatest { id ->
        factory.get(id, "enhanced_settings").data.map {
            EnhancedSettings(it[randomKey] ?: false, it[backgroundKey] ?: false, it[underlineKey] ?: false)
        }
    }
    suspend fun setRandomIncludesWatched(value: Boolean) {
        val id = profiles.activeProfileId.value
        factory.get(id, "enhanced_settings").edit { it[randomKey] = value }
    }
    suspend fun setDynamicBackground(value: Boolean) {
        val id = profiles.activeProfileId.value
        factory.get(id, "enhanced_settings").edit { it[backgroundKey] = value }
    }
    suspend fun setCatalogUnderline(value: Boolean) {
        val id = profiles.activeProfileId.value
        factory.get(id, "enhanced_settings").edit { it[underlineKey] = value }
    }
}
