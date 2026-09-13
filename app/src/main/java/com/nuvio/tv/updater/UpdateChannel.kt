package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig

enum class UpdateChannel(val storedValue: String) {
    STABLE("stable"),
    BETA("beta"),
    /** NNTP fork-test prereleases (used by the debug/full native test build). */
    FORK_TEST("nntp-testing");

    companion object {
        fun fromStoredValue(value: String?): UpdateChannel? = entries.firstOrNull {
            it.storedValue.equals(value, ignoreCase = true)
        }

        fun defaultForVersion(versionName: String): UpdateChannel =
            when {
                BuildConfig.IS_DEBUG_BUILD -> FORK_TEST
                VersionUtils.isPrerelease(versionName) -> BETA
                else -> STABLE
            }
    }
}
