package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.navigation.Screen

enum class StartupDestination {
    Loading,
    ProfileSelection,
    Setup,
    Home,
    Content
}

fun startupDestinationForRoute(route: String?): StartupDestination = when (route) {
    null -> StartupDestination.Loading
    Screen.ExperienceModeSelection.route, Screen.LayoutSelection.route -> StartupDestination.Setup
    Screen.Home.route -> StartupDestination.Home
    else -> StartupDestination.Content
}

fun shouldShowStartupSplash(
    enabled: Boolean,
    complete: Boolean,
    destination: StartupDestination
): Boolean = enabled && !complete &&
    (destination == StartupDestination.Loading || destination == StartupDestination.Home)

fun shouldShowHomeStartupLoader(
    loading: Boolean,
    sharedSplashEnabled: Boolean,
    startupComplete: Boolean
): Boolean = loading && (!sharedSplashEnabled || startupComplete)

/**
 * A home shell may only use local cached/placeholder data after the active profile is known.
 * Keeping this pure makes the profile boundary explicit at the call site and regression tests.
 */
fun canRenderCachedHomeShell(
    activeProfileReady: Boolean,
    activeProfileId: Int?,
    cachedContentProfileId: Int?,
    layoutPreferencesReady: Boolean,
    hasCachedShellContent: Boolean
): Boolean = activeProfileReady &&
    cachedContentProfileId == activeProfileId &&
    layoutPreferencesReady &&
    hasCachedShellContent
