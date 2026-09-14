package com.nuvio.tv.ui.screens.home

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.canRenderCachedHomeShell
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeProfileIdentityRegressionTest {
    @Test
    fun `loaded identity never treats default profile as the stored profile and covers old rows`() = runBlocking {
        val persistedActiveId = MutableStateFlow(2)
        val profiles = MutableStateFlow(
            listOf(
                UserProfile(id = 1, name = "Primary", avatarColorHex = "#000000"),
                UserProfile(id = 2, name = "Second", avatarColorHex = "#111111")
            )
        )
        val profileStore = mockk<ProfileDataStore> {
            every { activeProfileId } returns persistedActiveId
            every { profilesList } returns profiles
            every { hasEverSelectedProfile } returns MutableStateFlow(true)
            every { rememberLastProfileEnabled } returns MutableStateFlow(false)
            every { confirmExitEnabled } returns MutableStateFlow(false)
            every { startupSplashEnabled } returns MutableStateFlow(true)
        }
        val manager = ProfileManager(
            profileDataStore = profileStore,
            factory = mockk<ProfileDataStoreFactory>(relaxed = true),
            credentialStores = emptySet(),
            context = mockk<Context>(relaxed = true)
        )

        val initiallyLoaded = withTimeout(1_000L) {
            manager.activeProfileIdentity.filterNotNull().first()
        }
        assertEquals(2, initiallyLoaded.id)
        assertFalse(
            canRenderCachedHomeShell(
                activeProfileReady = true,
                activeProfileId = initiallyLoaded.id,
                cachedContentProfileId = 1,
                layoutPreferencesReady = true,
                hasCachedShellContent = true
            )
        )

        persistedActiveId.value = 1
        val switched = withTimeout(1_000L) {
            manager.activeProfileIdentity.filterNotNull().first { it.id == 1 }
        }
        assertEquals(1, switched.id)
    }
}