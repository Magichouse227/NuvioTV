package com.nuvio.tv.core.livetv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** URLs may contain credentials. Keep the complete configuration encrypted on this device. */
@Singleton
class LiveTvPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profiles: ProfileManager
) {
    private val key = stringPreferencesKey("encrypted_config_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private val alias = "nuvio_live_tv_v1"

    fun configFor(profileId: Int): Flow<Result<LiveTvConfig>> =
        factory.get(profileId, "live_tv").data.map { prefs ->
            runCatching { prefs[key]?.let { decode(it, profileId) } ?: LiveTvConfig() }
        }.flowOn(Dispatchers.IO)

    val config: Flow<LiveTvConfig> = profiles.activeProfileId.flatMapLatest { profileId ->
        configFor(profileId).map { it.getOrDefault(LiveTvConfig()) }
    }

    suspend fun update(profileId: Int, transform: (LiveTvConfig) -> LiveTvConfig) = withContext(Dispatchers.IO) {
        if (!factory.isProfileDeleted(profileId)) {
            factory.get(profileId, "live_tv").edit { prefs ->
                // Do not silently overwrite unreadable configuration after a device restore.
                val current = prefs[key]?.let { decode(it, profileId) } ?: LiveTvConfig()
                prefs[key] = encode(transform(current), profileId)
            }
        }
    }

    suspend fun reset(profileId: Int) = withContext(Dispatchers.IO) {
        if (!factory.isProfileDeleted(profileId)) factory.get(profileId, "live_tv").edit { it.remove(key) }
    }

    @Synchronized
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encode(config: LiveTvConfig, profileId: Int): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        cipher.updateAAD("live-tv:$profileId:v1".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(json.encodeToString(config).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decode(value: String, profileId: Int): LiveTvConfig {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28) { "Invalid encrypted configuration" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("live-tv:$profileId:v1".toByteArray(Charsets.UTF_8))
        return json.decodeFromString(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
}
