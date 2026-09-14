package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.data.local.EnhancedSettings
import com.nuvio.tv.data.local.EnhancedSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EnhancedSettingsViewModel @Inject constructor(private val dataStore: EnhancedSettingsDataStore) : ViewModel() {
    val settings = dataStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnhancedSettings())
    fun includeWatched(value: Boolean) { viewModelScope.launch { dataStore.setRandomIncludesWatched(value) } }
    fun dynamicBackground(value: Boolean) { viewModelScope.launch { dataStore.setDynamicBackground(value) } }
    fun catalogUnderline(value: Boolean) { viewModelScope.launch { dataStore.setCatalogUnderline(value) } }
}

@Composable
internal fun EnhancedPlaybackSettingsRow(viewModel: EnhancedSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsToggleRow(
        title = "Include watched episodes in random playback",
        subtitle = "Random playback always skips unavailable and future episodes",
        checked = settings.includeWatchedInRandom,
        onToggle = { viewModel.includeWatched(!settings.includeWatchedInRandom) }
    )
}

@Composable
internal fun EnhancedAppearanceRows(viewModel: EnhancedSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsToggleRow(title = "Dynamic background color", subtitle = "Tint the home screen using the featured artwork",
        checked = settings.dynamicBackground, onToggle = { viewModel.dynamicBackground(!settings.dynamicBackground) })
    SettingsToggleRow(title = "Catalog accent underline", subtitle = "Show an accent line below catalog headings",
        checked = settings.catalogUnderline, onToggle = { viewModel.catalogUnderline(!settings.catalogUnderline) })
}
