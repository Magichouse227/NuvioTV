package com.nuvio.tv.updater

internal const val FORK_TEST_TAG = "nntp-testing"

enum class UpdateChannel(val storedValue: String) {
    STABLE("stable"),
    BETA("beta"),
    /** NNTP fork-test prereleases (used by the debug/full native test build). */
    FORK_TEST(FORK_TEST_TAG);

    companion object {
        fun fromStoredValue(value: String?): UpdateChannel? = entries.firstOrNull {
            it.storedValue.equals(value, ignoreCase = true)
        }

        fun defaultForVersion(
            versionName: String,
            isForkTestBuild: Boolean = false
        ): UpdateChannel =
            when {
                isForkTestBuild -> FORK_TEST
                VersionUtils.isPrerelease(versionName) -> BETA
                else -> STABLE
            }
    }
}
