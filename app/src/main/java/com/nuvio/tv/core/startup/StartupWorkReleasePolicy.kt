package com.nuvio.tv.core.startup

/**
 * Pure gate for work that is useful after startup but unnecessary for the first usable shell.
 *
 * An input while a profile picker is visible is deliberately insufficient: the chosen profile
 * must first own the shell. The time-bounded fallback covers inaccessible remotes and direct
 * content launches without making sync opt-in forever.
 */
fun shouldReleaseDeferredStartupWork(
    hasFirstInteraction: Boolean,
    homeShellReady: Boolean,
    fallbackElapsed: Boolean
): Boolean = fallbackElapsed || (hasFirstInteraction && homeShellReady)