// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.sentry.android.gradle) apply false
}

// Surface Kotlin/AAPT failures through the Checks API as well as downloadable job logs.
// This is diagnostic only; it never changes whether a build succeeds or fails.
if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
    println("::add-matcher::${rootDir.resolve("scripts/ci-problem-matchers.json").absolutePath}")
}
