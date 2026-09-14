package com.nuvio.tv.core.sync

/**
 * Serializes startup-pull ownership without owning the coroutine that performs the pull.
 *
 * Same-identity follow-ups never replace a running pull. The service cancels only when the
 * account/profile identity changes, before any stale request can apply further mutations. Call
 * [finish] before starting its returned follow-up so the new request cannot observe the completed
 * job as still active.
 */
internal class StartupSyncCoordinator {
    data class Request(
        val userId: String,
        val profileId: Int,
        val force: Boolean,
        val includeProfileSettings: Boolean
    )

    private var active: Request? = null
    private var pending: Request? = null
    private var activityActive = false

    fun enqueue(request: Request): Request? = synchronized(this) {
        if (active == null && !activityActive) {
            active = request
            request
        } else {
            pending = pending?.merge(request) ?: request
            null
        }
    }

    /**
     * Clears the active request before returning a coalesced follow-up. A stale completion cannot
     * clear a newer request because it must still own [active].
     */
    fun finish(request: Request): Request? = synchronized(this) {
        if (active != request) return null
        active = null
        pending.also { pending = null }
    }

    fun hasActiveRequest(): Boolean = synchronized(this) { active != null }

    /** Activity and startup pulls touch the same profile stores and must not overlap. */
    fun beginActivity(): Boolean = synchronized(this) {
        if (active != null || activityActive) return false
        activityActive = true
        true
    }

    /** Clears activity ownership before returning a startup request queued while it ran. */
    fun finishActivity(): Request? = synchronized(this) {
        if (!activityActive) return null
        activityActive = false
        pending.also { pending = null }
    }

    /** Pure identity guard used immediately before every startup-pull mutation. */
    fun matchesCurrentIdentity(
        request: Request,
        userId: String?,
        profileId: Int?
    ): Boolean = request.userId == userId && request.profileId == profileId

    /** Clears queued work after the service cancels the stale in-flight request. */
    fun discardPending() = synchronized(this) {
        pending = null
    }

    /**
     * Every identity transition must cancel all three kinds of owned work, including a direct
     * signed-in account A -> B transition. Keep ownership until the cancelled jobs' finally
     * blocks finish, so a new account's queued request cannot overlap the old owner.
     */
    fun cancelIdentityWork(
        cancelStartupPull: () -> Unit,
        cancelActivityPull: () -> Unit,
        cancelAuxiliaryPulls: () -> Unit
    ) {
        discardPending()
        cancelStartupPull()
        cancelActivityPull()
        cancelAuxiliaryPulls()
    }

    private fun Request.merge(newer: Request): Request {
        // The most recently resolved account/profile identity wins. Settings coverage and force
        // are monotonic so a manual request is never silently weakened by a lifecycle request.
        return newer.copy(
            force = force || newer.force,
            includeProfileSettings = includeProfileSettings || newer.includeProfileSettings
        )
    }
}