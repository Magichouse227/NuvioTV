package com.nuvio.tv.core.startup

/**
 * Pure lifecycle/profile gate for deferred startup work.
 *
 * The foreground bit is intentionally part of the state machine. This prevents a delayed
 * fallback from releasing work after Activity.onStop, where it could mark launcher channels as
 * foreground or start periodic work while the app is hidden.
 */
internal data class StartupWorkReleaseState(
    val appInForeground: Boolean = false,
    val released: Boolean = false,
    val hasFirstInteraction: Boolean = false,
    val shellProfileId: Int? = null,
    val fallbackArmed: Boolean = false
)

internal sealed interface StartupWorkReleaseEvent {
    object Foreground : StartupWorkReleaseEvent
    object Background : StartupWorkReleaseEvent
    object FirstInteraction : StartupWorkReleaseEvent
    data class HomeShellReady(val profileId: Int) : StartupWorkReleaseEvent
    data class ActiveProfileChanged(val profileId: Int) : StartupWorkReleaseEvent
    object FallbackElapsed : StartupWorkReleaseEvent
}

internal data class StartupWorkReleaseTransition(
    val state: StartupWorkReleaseState,
    val releaseNow: Boolean,
    val armFallback: Boolean,
    val cancelFallback: Boolean
)

internal fun transitionStartupWorkRelease(
    state: StartupWorkReleaseState,
    event: StartupWorkReleaseEvent
): StartupWorkReleaseTransition {
    val next = when (event) {
        StartupWorkReleaseEvent.Foreground -> {
            state.copy(appInForeground = true, fallbackArmed = !state.released)
        }
        StartupWorkReleaseEvent.Background -> {
            state.copy(appInForeground = false, fallbackArmed = false)
        }
        StartupWorkReleaseEvent.FirstInteraction -> {
            state.copy(hasFirstInteraction = true)
        }
        is StartupWorkReleaseEvent.HomeShellReady -> {
            state.copy(shellProfileId = event.profileId)
        }
        is StartupWorkReleaseEvent.ActiveProfileChanged -> {
            if (state.shellProfileId == event.profileId) state
            else state.copy(shellProfileId = null, hasFirstInteraction = false)
        }
        StartupWorkReleaseEvent.FallbackElapsed -> {
            state.copy(fallbackArmed = false)
        }
    }

    val fallbackElapsed = event == StartupWorkReleaseEvent.FallbackElapsed && state.fallbackArmed
    val releaseNow = !next.released &&
        next.appInForeground &&
        shouldReleaseDeferredStartupWork(
            hasFirstInteraction = next.hasFirstInteraction,
            homeShellReady = next.shellProfileId != null,
            fallbackElapsed = fallbackElapsed
        )
    val releasedState = if (releaseNow) next.copy(released = true, fallbackArmed = false) else next
    return StartupWorkReleaseTransition(
        state = releasedState,
        releaseNow = releaseNow,
        armFallback = !state.fallbackArmed && releasedState.fallbackArmed,
        cancelFallback = state.fallbackArmed && !releasedState.fallbackArmed
    )
}