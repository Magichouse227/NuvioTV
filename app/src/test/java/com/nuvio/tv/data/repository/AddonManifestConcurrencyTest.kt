package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Manifest loading is deliberately tested through the repository flow rather than only testing a
 * semaphore helper. This covers the cache-miss path used by the home screen, where a regression
 * could accidentally restore an unbounded async-per-addon sweep or reorder installed addons.
 */
class AddonManifestConcurrencyTest {

    private companion object {
        const val MANIFEST_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    }

    @Test
    fun `manifest requests stay bounded and installed order is retained`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val urls = (0 until 8).map { "https://addon-$it.example" }
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val api = mockk<AddonApi>()

        coEvery { api.getManifest(any()) } coAnswers {
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> maxOf(previous, current) }
            try {
                // Complete in reverse order to make an accidental completion-order emission
                // observable without changing the order of the installed URL preference.
                val manifestUrl = firstArg<String>()
                val index = urls.indexOfFirst { manifestUrl.startsWith(it) }
                delay((urls.size - index).toLong())
                Response.success(
                    AddonManifestDto(
                        id = "addon-$index",
                        name = "Addon $index",
                        version = "1"
                    )
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }

        val preferences = mockk<AddonPreferences>()
        every { preferences.installedAddonUrls } returns flowOf(urls)
        every { preferences.userSetNames } returns flowOf(emptyMap())
        every { preferences.addonEnabledStates } returns flowOf(emptyMap())

        val repository = AddonRepositoryImpl(
            api = api,
            preferences = preferences,
            addonSyncService = mockk<AddonSyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            context = mockk<Context>(relaxed = true),
            dispatcher = dispatcher,
            clock = { 0L }
        )

        val addons = repository.getInstalledAddons().first { addonList ->
            addonList.size == urls.size && addonList.all { it.version == "1" }
        }

        assertEquals(urls, addons.map { it.baseUrl })
        assertTrue(
            "expected at most ${AddonRepositoryImpl.MANIFEST_FETCH_CONCURRENCY} requests, " +
                "observed ${maxInFlight.get()}",
            maxInFlight.get() <= AddonRepositoryImpl.MANIFEST_FETCH_CONCURRENCY
        )
    }

    @Test
    fun `manifest collection cancellation releases in-flight requests`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val urls = MutableStateFlow(listOf("https://cancel-a.example", "https://cancel-b.example"))
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val api = mockk<AddonApi>()

        coEvery { api.getManifest(any()) } coAnswers {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } catch (error: CancellationException) {
                cancelled.complete(Unit)
                throw error
            }
        }

        val preferences = mockk<AddonPreferences>()
        every { preferences.installedAddonUrls } returns urls
        every { preferences.userSetNames } returns flowOf(emptyMap())
        every { preferences.addonEnabledStates } returns flowOf(emptyMap())

        val repository = AddonRepositoryImpl(
            api = api,
            preferences = preferences,
            addonSyncService = mockk<AddonSyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            context = mockk<Context>(relaxed = true),
            dispatcher = dispatcher,
            clock = { 0L }
        )
        val collector = launch {
            repository.getInstalledAddons().collect { }
        }

        withTimeout(5_000) { entered.await() }
        urls.value = emptyList()
        withTimeout(5_000) { cancelled.await() }
        collector.cancelAndJoin()
    }

    @Test
    fun `background refresh and cache miss share the repository request limit`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val initialUrls = listOf(
            "https://refresh-a.example",
            "https://refresh-b.example",
            "https://refresh-c.example"
        )
        val newUrl = "https://cache-miss.example"
        val installedUrls = MutableStateFlow(initialUrls)
        val userSetNames = MutableStateFlow<Map<String, String>>(emptyMap())
        val now = AtomicLong(0L)
        val refreshPhase = AtomicBoolean(false)
        val refreshInFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val refreshAllStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val newManifestStarted = CompletableDeferred<Unit>()
        val api = mockk<AddonApi>()

        coEvery { api.getManifest(any()) } coAnswers {
            val manifestUrl = firstArg<String>()
            val baseUrl = manifestUrl.substringBefore("/manifest.json")
            val current = refreshInFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> maxOf(previous, current) }
            try {
                if (refreshPhase.get() && baseUrl in initialUrls) {
                    if (refreshInFlight.get() == initialUrls.size) {
                        refreshAllStarted.complete(Unit)
                    }
                    releaseRefresh.await()
                    Response.success(
                        AddonManifestDto(
                            id = baseUrl,
                            name = baseUrl,
                            version = "2"
                        )
                    )
                } else {
                    if (baseUrl == newUrl) newManifestStarted.complete(Unit)
                    Response.success(
                        AddonManifestDto(
                            id = baseUrl,
                            name = baseUrl,
                            version = "1"
                        )
                    )
                }
            } finally {
                refreshInFlight.decrementAndGet()
            }
        }

        val preferences = mockk<AddonPreferences>()
        every { preferences.installedAddonUrls } returns installedUrls
        every { preferences.userSetNames } returns userSetNames
        every { preferences.addonEnabledStates } returns flowOf(emptyMap())

        val repository = AddonRepositoryImpl(
            api = api,
            preferences = preferences,
            addonSyncService = mockk<AddonSyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            context = mockk<Context>(relaxed = true),
            dispatcher = dispatcher,
            clock = { now.get() }
        )

        repository.getInstalledAddons().first { addonList ->
            addonList.size == initialUrls.size && addonList.all { it.version == "1" }
        }

        // All initial manifests are cached. The next recomputation therefore takes the stale
        // background-refresh branch instead of the cache-miss branch.
        refreshPhase.set(true)
        now.set(MANIFEST_CACHE_TTL_MS + 1)
        userSetNames.value = mapOf(initialUrls.first() to "renamed")
        withTimeout(5_000) { refreshAllStarted.await() }

        // Adding a URL starts a cache-miss batch while all refresh permits are occupied. It must
        // wait rather than creating a fourth HTTP request.
        installedUrls.value = initialUrls + newUrl
        assertFalse("cache-miss request bypassed shared limiter", newManifestStarted.isCompleted)
        releaseRefresh.complete(Unit)
        withTimeout(5_000) { newManifestStarted.await() }

        val finalAddons = repository.getInstalledAddons().first { addonList ->
            addonList.size == initialUrls.size + 1 &&
                addonList.any { it.baseUrl == newUrl && it.version == "1" }
        }
        assertEquals(initialUrls + newUrl, finalAddons.map { it.baseUrl })
        assertTrue(
            "overlapping batches exceeded shared limit: ${maxInFlight.get()}",
            maxInFlight.get() <= AddonRepositoryImpl.MANIFEST_FETCH_CONCURRENCY
        )
    }
}