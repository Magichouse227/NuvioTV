package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseAddon
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonSyncService"

@Singleton
class AddonSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val addonPreferences: AddonPreferences,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity
) {
    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /**
     * Push local addon URLs to Supabase via RPC.
     * Uses a SECURITY DEFINER function to handle RLS for linked devices.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val activeProfile = profileManager.activeProfile
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "pushToRemote: activeProfile=${activeProfile?.id} isPrimary=${activeProfile?.isPrimary} usesPrimaryAddons=${activeProfile?.usesPrimaryAddons} profileId=$profileId")

            if (activeProfile != null && !activeProfile.isPrimary && activeProfile.usesPrimaryAddons) {
                Log.d(TAG, "Profile ${activeProfile.id} uses primary addons, skipping push")
                return@withContext Result.success(Unit)
            }

            val localUrls = addonPreferences.installedAddonUrls.first()
            val userSetNames = addonPreferences.userSetNames.first()
            val enabledStates = addonPreferences.addonEnabledStates.first()
            Log.d(TAG, "pushToRemote: localUrls count=${localUrls.size} for profile $profileId")

            val params = buildJsonObject {
                put("p_addons", buildJsonArray {
                    localUrls.forEachIndexed { index, url ->
                        val canonicalUrl = canonicalizeUrl(url)
                        addJsonObject {
                            put("url", url)
                            put("sort_order", index)
                            put("enabled", enabledStates[canonicalUrl] ?: true)
                            val name = userSetNames[canonicalUrl] ?: userSetNames[url]
                            if (!name.isNullOrBlank()) {
                                put("name", name)
                            }
                        }
                    }
                })
                put("p_profile_id", profileId)
                putSyncOriginClientId(syncClientIdentity)
            }
            Log.d(TAG, "pushToRemote: calling RPC sync_push_addons with profile_id=$profileId")
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_addons", params)
            }

            Log.d(TAG, "Pushed ${localUrls.size} addons to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push addons to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteAddonUrls(
        userId: String? = null,
        profileId: Int? = null,
        canApply: () -> Boolean = { true }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = userId ?: authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to resolve sync owner for addon sync")
                )

            val requestedProfileId = profileId ?: profileManager.activeProfileId.value
            val requestedProfile = profileManager.profiles.value
                .firstOrNull { it.id == requestedProfileId }
            val remoteProfileId = if (
                requestedProfile != null &&
                !requestedProfile.isPrimary &&
                requestedProfile.usesPrimaryAddons
            ) {
                1
            } else {
                requestedProfileId
            }

            val remoteAddons = withJwtRefreshRetry {
                postgrest.from("addons")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", remoteProfileId)
                    } }
                    .decodeList<SupabaseAddon>()
            }
            if (!canApply()) {
                throw CancellationException("Addon sync identity changed before applying metadata")
            }

            val nameMap = mutableMapOf<String, String>()
            val enabledMap = mutableMapOf<String, Boolean>()
            remoteAddons.forEach { addon ->
                val canonicalUrl = canonicalizeUrl(addon.url)
                if (!addon.name.isNullOrBlank()) {
                    nameMap[canonicalUrl] = addon.name
                }
                enabledMap[canonicalUrl] = addon.enabled
            }
            if (remoteAddons.isNotEmpty()) {
                // The caller may be completing after the active profile changed. Write the
                // captured profile's store, never whichever profile happens to be active now.
                addonPreferences.setUserSetNames(nameMap, requestedProfileId)
                if (!canApply()) {
                    throw CancellationException("Addon sync identity changed before applying metadata")
                }
                addonPreferences.setAddonEnabledStates(enabledMap, requestedProfileId)
            }

            Result.success(
                remoteAddons
                .sortedBy { it.sortOrder }
                .map { it.url }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote addon URLs", e)
            Result.failure(e)
        }
    }

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
            path.dropLast("/manifest.json".length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }
}
