plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sentry.android.gradle)
}

import java.io.File
import java.util.Properties
import java.time.Duration
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

fun parseBooleanProperty(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: return false
    return normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
}

fun resolveProperty(dev: Properties, local: Properties, key: String, fallback: String = ""): String {
    return dev.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: local.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback
}

fun buildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

fun cmakePath(path: String): String {
    if (path.isBlank()) return ""
    val file = File(path)
    val resolved = if (file.isAbsolute) file else rootProject.file(path)
    return resolved.absolutePath.replace("\\", "/")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val devProperties = Properties().apply {
    val devPropertiesFile = rootProject.file("local.dev.properties")
    if (devPropertiesFile.exists()) {
        load(devPropertiesFile.inputStream())
    }
}

val enableDoviNative = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_NATIVE_ENABLED")
)
val doviExtractorHookReady = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_EXTRACTOR_HOOK_READY")
)
val doviEnableRealLink = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_ENABLE_REAL_LINK")
)
val doviStaticLibPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_STATIC_LIB")
val doviIncludeDirPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_INCLUDE_DIR")
val doviPrebuiltRootPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_PREBUILT_ROOT")
val sponsorNames = resolveProperty(devProperties, localProperties, "SPONSOR_NAMES", "ragmehos.")
val sentryDsn = providers.environmentVariable("SENTRY_DSN").orNull?.trim()?.takeIf { it.isNotBlank() }
    ?: resolveProperty(devProperties, localProperties, "SENTRY_DSN")
val sentryAuthToken = providers.environmentVariable("SENTRY_AUTH_TOKEN").orNull?.trim()?.takeIf { it.isNotBlank() }
    ?: resolveProperty(devProperties, localProperties, "SENTRY_AUTH_TOKEN").takeIf { it.isNotBlank() }
val sentryOrg = providers.environmentVariable("SENTRY_ORG").orNull?.trim()?.takeIf { it.isNotBlank() }
    ?: resolveProperty(devProperties, localProperties, "SENTRY_ORG").takeIf { it.isNotBlank() }
val sentryProject = providers.environmentVariable("SENTRY_PROJECT").orNull?.trim()?.takeIf { it.isNotBlank() }
    ?: resolveProperty(devProperties, localProperties, "SENTRY_PROJECT").takeIf { it.isNotBlank() }
val sentryMappingUploadEnabled = sentryAuthToken != null && sentryOrg != null && sentryProject != null

fun env(name: String): String? = providers.environmentVariable(name).orNull

fun truthy(value: String?): Boolean {
    return value.equals("true", ignoreCase = true) ||
        value.equals("1", ignoreCase = true) ||
        value.equals("yes", ignoreCase = true)
}

val buildingAppBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
val useDebugReleaseSigning = env("CI_USE_DEBUG_SIGNING").equals("true", ignoreCase = true)
val useLocalFfmpegDecoder = truthy(
    providers.gradleProperty("useLocalFfmpegDecoder").orNull
        ?: env("USE_LOCAL_FFMPEG_DECODER")
        ?: localProperties.getProperty("USE_LOCAL_FFMPEG_DECODER")
)
val releaseStoreFilePath = env("NUVIO_RELEASE_STORE_FILE")
    ?: localProperties.getProperty("NUVIO_RELEASE_STORE_FILE")
val releaseKeyAliasValue = env("NUVIO_RELEASE_KEY_ALIAS")
    ?: localProperties.getProperty("NUVIO_RELEASE_KEY_ALIAS", "nuviotv")
val releaseKeyPasswordValue = env("NUVIO_RELEASE_KEY_PASSWORD")
    ?: localProperties.getProperty("NUVIO_RELEASE_KEY_PASSWORD", "815787")
val releaseStorePasswordValue = env("NUVIO_RELEASE_STORE_PASSWORD")
    ?: localProperties.getProperty("NUVIO_RELEASE_STORE_PASSWORD", "815787")

val nntpLegalAssetsDir = layout.buildDirectory.dir("generated/nntp-legal-assets")
val generateNntpLegalAssets by tasks.registering(Sync::class) {
    into(nntpLegalAssetsDir.map { it.dir("licenses") })
    from(rootProject.file("nntp-engine/LICENSE")) {
        rename { "streamnzb-GPL-3.0.txt" }
    }
    from(rootProject.file("nntp-engine/third_party/rardecode/LICENSE")) {
        rename { "rardecode-BSD-2-Clause.txt" }
    }
    from(rootProject.file("nntp-engine/UPSTREAM.md")) {
        rename { "streamnzb-UPSTREAM.md" }
    }
}

val nntpEngineDir = rootProject.layout.projectDirectory.dir("nntp-engine")
val nntpEngineBuildScript = rootProject.layout.projectDirectory.file("nntp-engine/build-android.sh")
val nntpEngineWindowsBuildScript =
    rootProject.layout.projectDirectory.file("nntp-engine/build-android.ps1")
val nntpEngineAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val nntpEngineOutputs = nntpEngineAbis.map { abi ->
    rootProject.layout.projectDirectory.file("app/src/main/jniLibs/$abi/libnuvionntp.so")
}
val nntpEngineGoInputs = fileTree(nntpEngineDir) {
    include("**/*.go")
    include("go.mod", "go.sum")
    exclude(
        "**/.git/**",
        "**/.gradle/**",
        "**/build/**",
        "**/tmp/**",
        "**/.cache/**",
        "**/node_modules/**"
    )
}

/*
 * Keep the checked-in JNI files useful for source distributions, but never let
 * an APK build silently package them when the native toolchain is unavailable.
 * The toolchain check has no outputs on purpose and therefore runs even when
 * the compilation task is up to date. The compilation itself remains
 * incremental over the Go source/module files and all four ABI outputs.
 */
val verifyNntpEngineToolchain by tasks.registering {
    group = "build"
    description = "Checks the Go and Android NDK toolchain for the NNTP engine."
}
val buildNntpEngine by tasks.registering {
    group = "build"
    description = "Builds the NNTP engine PIE executable for every packaged ABI."
    inputs.files(nntpEngineGoInputs)
    inputs.file(nntpEngineBuildScript)
    inputs.file(nntpEngineWindowsBuildScript)
    outputs.files(nntpEngineOutputs)
    // CI must rebuild every ABI rather than accepting the checked-in native set.
    outputs.upToDateWhen { providers.environmentVariable("GITHUB_ACTIONS").orNull != "true" }
    dependsOn(verifyNntpEngineToolchain)
}

android {
    namespace = "com.nuvio.tv"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.nuvio.tv"
        minSdk = 24
        targetSdk = 36
        versionCode = 1060
        versionName = "0.9.2-beta"
        buildConfigField(
            "String",
            "TEST_BUILD_SHA",
            buildConfigString(providers.environmentVariable("GITHUB_SHA").orNull ?: "local")
        )
        // Only the fullDebug variant is overridden below for fork test releases.
        buildConfigField("boolean", "IS_FORK_TEST_BUILD", "false")

        buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${localProperties.getProperty("PARENTAL_GUIDE_API_URL", "")}\"")
        buildConfigField("String", "INTRODB_API_URL", "\"${localProperties.getProperty("INTRODB_API_URL", "")}\"")
        buildConfigField("String", "TRAILER_API_URL", "\"${localProperties.getProperty("TRAILER_API_URL", "")}\"")
        buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${localProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
        buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${localProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${localProperties.getProperty("TRAKT_CLIENT_ID", "")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${localProperties.getProperty("TRAKT_CLIENT_SECRET", "")}\"")
        buildConfigField("String", "TRAKT_API_URL", "\"${localProperties.getProperty("TRAKT_API_URL", "https://api.trakt.tv/")}\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", "\"${localProperties.getProperty("TRAKT_REDIRECT_URI", "urn:ietf:wg:oauth:2.0:oob")}\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", buildConfigString(resolveProperty(devProperties, localProperties, "SIMKL_CLIENT_ID")))
        buildConfigField("String", "SIMKL_APP_NAME", buildConfigString(resolveProperty(devProperties, localProperties, "SIMKL_APP_NAME", "nuvio")))
        buildConfigField("String", "TMDB_API_KEY", "\"${localProperties.getProperty("TMDB_API_KEY", "")}\"")
        buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://nuvio.tv/tv-login")}\"")
        buildConfigField("String", "DEVICE_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("DEVICE_LOGIN_WEB_BASE_URL", "https://nuvio.tv/link")}\"")
        buildConfigField("boolean", "DOVI_NATIVE_ENABLED", enableDoviNative.toString())
        buildConfigField("boolean", "DOVI_EXTRACTOR_HOOK_READY", doviExtractorHookReady.toString())
        if (enableDoviNative) {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DDOVI_ENABLE_LIBDOVI=${if (doviEnableRealLink) "ON" else "OFF"}",
                        "-DDOVI_LIBDOVI_STATIC_LIB=${cmakePath(doviStaticLibPath)}",
                        "-DDOVI_LIBDOVI_INCLUDE_DIR=${cmakePath(doviIncludeDirPath)}",
                        "-DDOVI_LIBDOVI_PREBUILT_ROOT=${cmakePath(doviPrebuiltRootPath)}"
                    )
                }
            }
        }
        buildConfigField("String", "SUPPORTERS_API_BASE_URL", buildConfigString(localProperties.getProperty("SUPPORTERS_API_BASE_URL", "https://nuvio.tv/")))
        buildConfigField("String", "SUPPORT_URL", buildConfigString(localProperties.getProperty("SUPPORT_URL", "https://nuvio.tv/support")))
        buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", "")}\"")
        buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"${localProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", "")}\"")
        buildConfigField("String", "PLAYBACK_REPORTS_BASE_URL", buildConfigString(localProperties.getProperty("PLAYBACK_REPORTS_BASE_URL", "")))
        buildConfigField("String", "PREMIUMIZE_CLIENT_ID", "\"${localProperties.getProperty("PREMIUMIZE_CLIENT_ID", "")}\"")
        buildConfigField("String", "SPONSOR_NAMES", buildConfigString(sponsorNames))
        buildConfigField("String", "SENTRY_DSN", buildConfigString(sentryDsn))

        // In-app updater (GitHub Releases)
        buildConfigField("String", "GITHUB_OWNER", "\"NuvioMedia\"")
        buildConfigField("String", "GITHUB_REPO", "\"NuvioTV\"")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "FEATURE_PLUGINS_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_IN_APP_UPDATES_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_IN_APP_TRAILERS_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_EXTERNAL_TRAILERS_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_EXTERNAL_PLAYBACK_KEEP_ALIVE_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED", "true")
        }
        create("playstore") {
            dimension = "distribution"
            applicationId = "com.nuvio.app"
            buildConfigField("boolean", "FEATURE_PLUGINS_ENABLED", "false")
            buildConfigField("boolean", "FEATURE_IN_APP_UPDATES_ENABLED", "false")
            buildConfigField("boolean", "FEATURE_IN_APP_TRAILERS_ENABLED", "false")
            buildConfigField("boolean", "FEATURE_EXTERNAL_TRAILERS_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_EXTERNAL_PLAYBACK_KEEP_ALIVE_ENABLED", "false")
            buildConfigField("boolean", "FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED", "false")
        }
    }

    if (enableDoviNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = releaseKeyAliasValue
            keyPassword = releaseKeyPasswordValue
            storeFile = releaseStoreFilePath?.let(::file) ?: file("../nuviotv.jks")
            storePassword = releaseStorePasswordValue
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            // Keep the test channel unshrunk until dynamic plugins, serializers and
            // every JNI playback engine pass an optimized-build runtime matrix.
            // Switching to release also changes login/updater identity; see docs/performance.md.
            isMinifyEnabled = false

            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")
            // Native NNTP test builds are published separately from upstream releases.
            buildConfigField("String", "GITHUB_OWNER", "\"Magichouse227\"")
            buildConfigField("String", "GITHUB_REPO", "\"NuvioTV\"")
            buildConfigField("String", "SENTRY_ENVIRONMENT", buildConfigString("debug"))

            // Dev environment (from local.dev.properties)
            buildConfigField("String", "SUPABASE_URL", buildConfigString(resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_URL", "https://api.nuvio.tv")))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_ANON_KEY", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI")))
            buildConfigField("String", "SUPABASE_FALLBACK_URL", buildConfigString(resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_FALLBACK_URL", "https://api-two.nuvioapp.space")))
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${devProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://nuvio.tv/tv-login")}\"")
            buildConfigField("String", "DEVICE_LOGIN_WEB_BASE_URL", "\"${devProperties.getProperty("DEVICE_LOGIN_WEB_BASE_URL", "https://nuvio.tv/link")}\"")
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${devProperties.getProperty("PARENTAL_GUIDE_API_URL", "")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${devProperties.getProperty("INTRODB_API_URL", "")}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${devProperties.getProperty("TRAILER_API_URL", "")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${devProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${devProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
            buildConfigField("String", "SUPPORTERS_API_BASE_URL", buildConfigString(resolveProperty(devProperties, localProperties, "SUPPORTERS_API_BASE_URL", "https://nuvio.tv/")))
            buildConfigField("String", "SUPPORT_URL", buildConfigString(resolveProperty(devProperties, localProperties, "SUPPORT_URL", "https://nuvio.tv/support")))
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${devProperties.getProperty("AVATAR_PUBLIC_BASE_URL", localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", ""))}\"")
            buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"${devProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", localProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", ""))}\"")
            buildConfigField("String", "PLAYBACK_REPORTS_BASE_URL", buildConfigString(resolveProperty(devProperties, localProperties, "PLAYBACK_REPORTS_BASE_URL")))
            buildConfigField("String", "PREMIUMIZE_CLIENT_ID", "\"${devProperties.getProperty("PREMIUMIZE_CLIENT_ID", localProperties.getProperty("PREMIUMIZE_CLIENT_ID", ""))}\"")
            buildConfigField("String", "SPONSOR_NAMES", buildConfigString(sponsorNames))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (useDebugReleaseSigning) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }

            buildConfigField("boolean", "IS_DEBUG_BUILD", "false")
            buildConfigField("String", "SENTRY_ENVIRONMENT", buildConfigString("production"))

            // Production environment (from local.properties)
            buildConfigField("String", "SUPABASE_URL", buildConfigString(localProperties.getProperty("NUVIO_SUPABASE_URL", "")))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(localProperties.getProperty("NUVIO_SUPABASE_ANON_KEY", "")))
            buildConfigField("String", "SUPABASE_FALLBACK_URL", buildConfigString(localProperties.getProperty("NUVIO_SUPABASE_FALLBACK_URL", "")))
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://nuvio.tv/tv-login")}\"")
            buildConfigField("String", "DEVICE_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("DEVICE_LOGIN_WEB_BASE_URL", "https://nuvio.tv/link")}\"")
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${localProperties.getProperty("PARENTAL_GUIDE_API_URL", "")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${localProperties.getProperty("INTRODB_API_URL", "")}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${localProperties.getProperty("TRAILER_API_URL", "")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${localProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${localProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
            buildConfigField("String", "SUPPORTERS_API_BASE_URL", buildConfigString(localProperties.getProperty("SUPPORTERS_API_BASE_URL", "https://nuvio.tv/")))
            buildConfigField("String", "SUPPORT_URL", buildConfigString(localProperties.getProperty("SUPPORT_URL", "https://nuvio.tv/support")))
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", "")}\"")
            buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"${localProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", "")}\"")
            buildConfigField("String", "PLAYBACK_REPORTS_BASE_URL", buildConfigString(localProperties.getProperty("PLAYBACK_REPORTS_BASE_URL", "")))
            buildConfigField("String", "PREMIUMIZE_CLIENT_ID", "\"${localProperties.getProperty("PREMIUMIZE_CLIENT_ID", "")}\"")
            buildConfigField("String", "SPONSOR_NAMES", buildConfigString(sponsorNames))
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")
            buildConfigField("String", "SENTRY_ENVIRONMENT", buildConfigString("benchmark"))
            applicationIdSuffix = ".debug"
            matchingFallbacks += "release"
        }
    }

    splits {
        abi {
            isEnable = !buildingAppBundle
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    bundle {
        language {
            // Keep all string resources in the
            // base install so Play Store installs can switch languages at runtime.
            // https://developer.android.com/guide/app-bundle/configure-base
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            assets.srcDir(nntpLegalAssetsDir)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Keep one consistent native set across dependencies.
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavdevice.so",
                "lib/*/libavfilter.so",
                "lib/*/libavformat.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so",
                "lib/*/libtorrserver.so",
                "lib/*/libnuvionntp.so"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Keep signed-test delivery checks in the native build, so the established signing
// workflow cannot upload a package before its startup/browsing regressions pass.
// A separate Test task is deliberate: the existing workflow restricts its unit-test
// task with --tests selectors, which must not accidentally exclude this coverage.
afterEvaluate {
    val standardTests = tasks.named<Test>("testFullDebugUnitTest")
    val performanceTests = tasks.register<Test>("testFullDebugPerformance") {
        group = "verification"
        description = "Checks combined Enhanced/NNTP playback, startup, profile isolation and bounded browsing."
        dependsOn(standardTests)
        val original = standardTests.get()
        testClassesDirs = original.testClassesDirs
        classpath = original.classpath
        systemProperties(original.systemProperties)
        maxHeapSize = "2g"
        timeout.set(Duration.ofMinutes(15))
        addTestListener(object : org.gradle.api.tasks.testing.TestListener {
            override fun beforeSuite(descriptor: org.gradle.api.tasks.testing.TestDescriptor) = Unit
            override fun beforeTest(descriptor: org.gradle.api.tasks.testing.TestDescriptor) = Unit
            override fun afterSuite(
                descriptor: org.gradle.api.tasks.testing.TestDescriptor,
                result: org.gradle.api.tasks.testing.TestResult
            ) {
                if (descriptor.parent == null) {
                    logger.lifecycle("::notice title=Native regression results::" +
                        "${result.testCount} tests, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped")
                }
            }
            override fun afterTest(
                descriptor: org.gradle.api.tasks.testing.TestDescriptor,
                result: org.gradle.api.tasks.testing.TestResult
            ) {
                if (result.resultType == org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE) {
                    // Annotations remain accessible even when GitHub's redirected log archive
                    // cannot be downloaded. Omit exception messages/arguments: mocked call
                    // descriptions can include build-time service configuration.
                    val detail = (descriptor.name + "\n" +
                        result.exceptions.joinToString("\n") { error ->
                            error.javaClass.name + "\n" +
                                error.stackTrace.take(40).joinToString("\n") { "at $it" }
                        })
                        .take(8_000).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
                    logger.lifecycle("::error title=${descriptor.className}::$detail")
                }
            }
        })
        filter {
            includeTestsMatching("com.nuvio.tv.core.sync.*")
            includeTestsMatching("com.nuvio.tv.core.startup.*")
            includeTestsMatching("com.nuvio.tv.ui.components.StartupLoadingPolicyTest")
            includeTestsMatching("com.nuvio.tv.ui.screens.home.*")
            includeTestsMatching("com.nuvio.tv.data.repository.*")
            includeTestsMatching("com.nuvio.tv.data.local.ImagePerformancePreferencesTest")
            includeTestsMatching("com.nuvio.tv.core.usenet.*")
            includeTestsMatching("com.nuvio.tv.core.livetv.*")
            includeTestsMatching("com.nuvio.tv.core.performance.*")
            includeTestsMatching("com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest")
            includeTestsMatching("com.nuvio.tv.ui.screens.detail.RandomEpisodePolicyTest")
            includeTestsMatching("com.nuvio.tv.data.mapper.StreamNzbMapperTest")
            includeTestsMatching("com.nuvio.tv.ui.screens.player.PlayerRuntimeErrorRecoveryPolicyTest")
        }
    }
    if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
        tasks.named("assembleFullDebug").configure {
            dependsOn(performanceTests)
            doLast {
                val tools = androidComponents.sdkComponents.sdkDirectory.get().asFile
                    .resolve("build-tools/${android.buildToolsVersion}")
                exec {
                    workingDir(rootProject.projectDir)
                    commandLine("python3", "-m", "unittest", "discover",
                        "-s", "scripts/tests", "-p", "test_verify_test_apk.py")
                }
                exec {
                    workingDir(rootProject.projectDir)
                    commandLine(
                        "python3", "scripts/verify-test-apk.py",
                        "app/build/outputs/apk/full/debug/app-full-universal-debug.apk",
                        "--build-sha", providers.environmentVariable("GITHUB_SHA").get(),
                        "--minimum-version", "1060",
                        "--native-dir",
                        "app/build/intermediates/stripped_native_libs/fullDebug/stripFullDebugDebugSymbols/out/lib",
                        "--aapt", tools.resolve("aapt").absolutePath,
                        "--apksigner", tools.resolve("apksigner").absolutePath,
                        "--output", "app/build/outputs/apk/full/debug/verification.json"
                    )
                }
            }
        }
    }
}

androidComponents {
    val nntpEngineNdkDirectory = sdkComponents.ndkDirectory
    verifyNntpEngineToolchain.configure {
        inputs.property(
            "androidNdkDirectory",
            nntpEngineNdkDirectory.map { it.asFile.absolutePath }
        )
        doLast {
            val ndkHome = nntpEngineNdkDirectory.get().asFile
            if (!ndkHome.isDirectory) {
                throw GradleException(
                    "Android NDK ${android.ndkVersion} was not resolved at ${ndkHome.absolutePath}."
                )
            }

            val goCommand = providers.environmentVariable("GO_COMMAND").orNull ?: "go"
            val goResult = try {
                project.exec {
                    commandLine(goCommand, "version")
                    isIgnoreExitValue = true
                }
            } catch (error: Exception) {
                throw GradleException(
                    "The NNTP native build requires Go on PATH (tried '$goCommand').",
                    error
                )
            }
            if (goResult.exitValue != 0) {
                throw GradleException(
                    "The NNTP native build requires a working Go command (tried '$goCommand')."
                )
            }
        }
    }
    buildNntpEngine.configure {
        inputs.property(
            "androidNdkDirectory",
            nntpEngineNdkDirectory.map { it.asFile.absolutePath }
        )
        doLast {
            val ndkHome = nntpEngineNdkDirectory.get().asFile
            val buildScript = if (System.getProperty("os.name").startsWith("Windows")) {
                nntpEngineWindowsBuildScript.asFile
            } else {
                nntpEngineBuildScript.asFile
            }
            if (!buildScript.isFile) {
                throw GradleException(
                    "Missing NNTP native build script: ${buildScript.absolutePath}"
                )
            }

            try {
                project.exec {
                    environment("ANDROID_NDK_HOME", ndkHome.absolutePath)
                    if (System.getProperty("os.name").startsWith("Windows")) {
                        commandLine(
                            "powershell",
                            "-NoProfile",
                            "-NonInteractive",
                            "-ExecutionPolicy",
                            "Bypass",
                            "-File",
                            buildScript.absolutePath,
                            "-NdkHome",
                            ndkHome.absolutePath
                        )
                    } else {
                        commandLine("bash", buildScript.absolutePath)
                    }
                }
            } catch (error: Exception) {
                throw GradleException(
                    "NNTP native engine compilation failed; refusing to package stale JNI binaries.",
                    error
                )
            }
        }
    }
    onVariants { variant ->
        val isFullDebug = variant.buildType == "debug" &&
            variant.productFlavors.any { it.second == "full" }
        if (isFullDebug) {
            requireNotNull(variant.buildConfigFields) {
                "BuildConfig must be enabled for the fullDebug test updater"
            }.put(
                "IS_FORK_TEST_BUILD",
                com.android.build.api.variant.BuildConfigField(
                    "boolean",
                    "true",
                    "NNTP fork-test updater channel"
                )
            )
        }
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        val isPlaystore = variant.productFlavors.any { it.second == "playstore" }
        variant.applicationId.set(if (isPlaystore) "com.nuvio.appdebug" else "com.nuviodebug.com")
    }
}

composeCompiler {
    // Enable Compose compiler metrics for performance analysis
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability_config.conf"))
}

// Globally exclude stock media3 modules — replaced by local :nuvio-exoplayer-engine module
configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-common")
    exclude(group = "androidx.media3", module = "media3-datasource")
    exclude(group = "androidx.media3", module = "media3-datasource-okhttp")
    exclude(group = "androidx.media3", module = "media3-exoplayer-hls")
    exclude(group = "androidx.media3", module = "media3-extractor")
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
    baselineProfileOutputDir = "generated/baselineProfiles"
    filter {
        include("com.nuvio.tv.**")
    }
}

sentry {
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryMappingUploadEnabled)
    uploadNativeSymbols.set(false)
    autoUploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(false)
    autoUploadSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    sentryAuthToken?.let(authToken::set)
    sentryOrg?.let(org::set)
    sentryProject?.let(projectName::set)
    ignoredBuildTypes.set(setOf("debug"))
    autoInstallation {
        enabled.set(false)
    }
    tracingInstrumentation {
        enabled.set(false)
    }
}

tasks.named("preBuild") {
    dependsOn(generateNntpLegalAssets)
    dependsOn(buildNntpEngine)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")

    // Source-retention nullness annotations (MonotonicNonNull / RequiresNonNull /
    // EnsuresNonNull) used by the vendored Matroska extractor in
    // com.nuvio.tv.core.player.dvmkv. Media3 keeps these compileOnly in its own
    // build, so they aren't on our classpath via the prebuilt AARs.
    compileOnly("org.checkerframework:checker-qual:3.43.0")

    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.profileinstaller)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.activity:activity-compose:1.11.0")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)
    implementation(libs.lottie.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // ViewModel
    implementation(libs.lifecycle.viewmodel.compose)

    // Media3 — remaining stock modules from Maven (not forked)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.decoder)
    implementation(libs.media3.session)
    implementation(libs.media3.container)

    // Transitive dependencies required by forked local AARs (not bundled in AARs):
    // - Guava: needed by lib-common (ImmutableList/ImmutableSet in Tracks, Player API)
    // - media3-database: needed by lib-datasource (cache/storage layer)
    // - annotation-experimental: needed by lib-common (OptIn annotations)
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.media3:media3-database:1.8.0")
    implementation("androidx.annotation:annotation-experimental:1.3.1")

    // Nuvio Engine local AARs (replaces lib-exoplayer, lib-common, lib-datasource, lib-datasource-okhttp, lib-exoplayer-hls, lib-extractor)
    implementation(files(
        "libs/lib-common-release.aar",
        "libs/lib-datasource-release.aar",
        "libs/lib-datasource-okhttp-release.aar",
        "libs/lib-exoplayer-release.aar",
        "libs/lib-exoplayer-hls-release.aar",
        "libs/lib-extractor-release.aar"
    ))
    implementation(libs.media3.ui)

    // Local decoder AARs (AV1, IAMF, MPEG-H)
    implementation(files(
        "libs/lib-decoder-av1-release.aar",
        "libs/lib-decoder-mpegh-release.aar"
    ))
    add("fullImplementation", files("libs/lib-decoder-iamf-release.aar"))
    if (useLocalFfmpegDecoder) {
        implementation(project(":ffmpeg-decoder-downmix"))
    } else {
        implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    }

    // libass-android for ASS/SSA subtitle support (from Maven Central)
    implementation("io.github.peerless2012:ass-media:0.4.0")
    // Local nextlib-mediainfo fork (static FFmpeg; no libav*.so in final AAR)
    implementation(files("libs/nextlib-mediainfo-local.aar"))
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
    implementation("dev.chrisbanes.haze:haze-android:1.7.2") {
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
    }

    implementation(libs.gson)
    add("fullImplementation", files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
    add("fullImplementation", libs.jsoup)
    add("fullImplementation", "com.fasterxml.jackson.core:jackson-databind:2.17.0")
    add("fullImplementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    add("fullImplementation", libs.nicehttp)
    add("fullImplementation", libs.conscrypt.android)
    add("fullImplementation", "com.github.recloudstream.cloudstream:library:${libs.versions.cloudstream.get()}") {
        exclude(group = "org.mozilla", module = "rhino")
        exclude(group = "com.github.AmarullisVFX", module = "newpipeextractor")
        exclude(group = "com.github.AmaryllisVFX", module = "newpipeextractor")
        exclude(group = "com.github.AmaryllisVFX.newpipeextractor")
        exclude(group = "info.debatty", module = "java-string-similarity")
    }

    // Markdown rendering
    implementation(libs.markdown.renderer.m3)

    add("fullImplementation", libs.crypto.js)
    // QR code + local server for addon management
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)


    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.sentry.android)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Performance profiling
    implementation("androidx.metrics:metrics-performance:1.0.0-rc01")  // JankStats
    debugImplementation("androidx.compose.runtime:runtime-tracing")

    add("fullImplementation", "org.webjars.npm:crypto-js:4.2.0")

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation("junit:junit:4.13.2")
    // JVM tests must parse the native error contract, not Android's no-op org.json stubs.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
