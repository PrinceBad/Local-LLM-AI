package com.example.auralocalai.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.auralocalai.BuildConfig

sealed interface TokenStorage {
    val isHardwareEncrypted: Boolean
    fun getToken(): String
    fun saveToken(token: String): Boolean // synchronous .commit()
    fun clearToken(): Boolean
}

class EncryptedTokenStorage(private val prefs: SharedPreferences) : TokenStorage {
    override val isHardwareEncrypted: Boolean = true
    override fun getToken(): String = prefs.getString(KEY_HF_TOKEN, "") ?: ""
    override fun saveToken(token: String): Boolean = prefs.edit().putString(KEY_HF_TOKEN, token).commit()
    override fun clearToken(): Boolean = prefs.edit().remove(KEY_HF_TOKEN).commit()

    companion object {
        const val KEY_HF_TOKEN = "hf_token"
    }
}

class DegradedTokenStorage(private val prefs: SharedPreferences) : TokenStorage {
    override val isHardwareEncrypted: Boolean = false
    override fun getToken(): String = prefs.getString(KEY_HF_TOKEN, "") ?: ""
    override fun saveToken(token: String): Boolean = prefs.edit().putString(KEY_HF_TOKEN, token).commit()
    override fun clearToken(): Boolean = prefs.edit().remove(KEY_HF_TOKEN).commit()

    companion object {
        const val KEY_HF_TOKEN = "hf_token"
    }
}

object TokenStorageCoordinator {
    const val TAG = "TokenStorage"
    const val PREFS_APP_SETTINGS = "app_settings"
    const val PREFS_DEGRADED_SETTINGS = "degraded_app_settings"
    const val PREFS_ENCRYPTED_SETTINGS = "secure_user_prefs"
    const val KEY_LEGACY_TOKEN = "hf_token"

    const val KEYSTORE_DEGRADED = "keystore_degraded"
    const val KEYSTORE_DEGRADED_PATCH = "keystore_degraded_patch"
    const val KEYSTORE_DEGRADED_APP_VERSION = "keystore_degraded_app_version"

    /**
     * Performs rollback-protected token migration:
     * 1. Reads legacy plaintext token from legacy SharedPreferences.
     * 2. Synchronously commits token into target storage.
     * 3. Only wipes legacy plaintext key after confirmed disk sync.
     */
    fun migrateTokenIfPresent(legacyPrefs: SharedPreferences, targetStorage: TokenStorage): Boolean {
        val legacyToken = legacyPrefs.getString(KEY_LEGACY_TOKEN, null)
        if (!legacyToken.isNullOrBlank()) {
            val writeSuccess = targetStorage.saveToken(legacyToken)
            if (writeSuccess) {
                legacyPrefs.edit().remove(KEY_LEGACY_TOKEN).commit()
                Log.i(TAG, "Migrated token to target storage and wiped legacy plaintext key.")
                return true
            } else {
                Log.e(TAG, "Write to target storage failed; legacy plaintext key preserved intact.")
                return false
            }
        }
        return false
    }

    /**
     * Instantiates TokenStorage with sticky degraded mode and auto-recovery re-probe mechanism.
     * Allows exactly one clean re-probe attempt when OS security patch or app version changes.
     * If re-probe succeeds, automatically migrates any token saved during degraded mode into
     * hardware-backed encrypted storage before clearing degraded flags.
     */
    fun create(
        appPrefs: SharedPreferences,
        degradedPrefs: SharedPreferences,
        currentPatch: String = try { Build.VERSION.SECURITY_PATCH ?: "" } catch (_: Throwable) { "" },
        currentAppVersion: Int = try { BuildConfig.VERSION_CODE } catch (_: Throwable) { 1 },
        encryptedPrefsProvider: () -> SharedPreferences
    ): TokenStorage {
        val isDegraded = appPrefs.getBoolean(KEYSTORE_DEGRADED, false)
        val lastPatch = appPrefs.getString(KEYSTORE_DEGRADED_PATCH, "") ?: ""
        val lastAppVersion = appPrefs.getInt(KEYSTORE_DEGRADED_APP_VERSION, -1)

        val shouldAttemptReprobe = isDegraded && (currentPatch != lastPatch || currentAppVersion != lastAppVersion)

        if (!isDegraded || shouldAttemptReprobe) {
            try {
                val encryptedPrefs = encryptedPrefsProvider()
                val storage = EncryptedTokenStorage(encryptedPrefs)

                if (isDegraded) {
                    Log.i(TAG, "System updated (patch: $currentPatch, ver: $currentAppVersion). Keystore re-probe succeeded! Restoring encrypted mode.")

                    // Critical: Migrate token saved while in degraded mode into restored encrypted storage
                    val degradedToken = degradedPrefs.getString(DegradedTokenStorage.KEY_HF_TOKEN, null)
                    if (!degradedToken.isNullOrBlank()) {
                        val writeSuccess = storage.saveToken(degradedToken)
                        if (writeSuccess) {
                            degradedPrefs.edit().remove(DegradedTokenStorage.KEY_HF_TOKEN).commit()
                            Log.i(TAG, "Successfully migrated degraded token into restored encrypted storage.")
                        } else {
                            Log.e(TAG, "Failed to write degraded token into encrypted storage; aborting recovery and keeping degraded mode.")
                            // Rollback: Keep degraded mode active and return degraded storage so token is preserved and accessible
                            return DegradedTokenStorage(degradedPrefs)
                        }
                    }

                    // Reset degraded flags ONLY after confirmed migration write
                    appPrefs.edit()
                        .putBoolean(KEYSTORE_DEGRADED, false)
                        .remove(KEYSTORE_DEGRADED_PATCH)
                        .remove(KEYSTORE_DEGRADED_APP_VERSION)
                        .commit()
                }

                // Migrate legacy plaintext token from app_settings if present
                migrateTokenIfPresent(appPrefs, storage)

                // Edge case: If encrypted storage is still blank but degradedPrefs has an orphaned token
                if (storage.getToken().isBlank()) {
                    val orphanedDegradedToken = degradedPrefs.getString(DegradedTokenStorage.KEY_HF_TOKEN, null)
                    if (!orphanedDegradedToken.isNullOrBlank()) {
                        val writeSuccess = storage.saveToken(orphanedDegradedToken)
                        if (writeSuccess) {
                            degradedPrefs.edit().remove(DegradedTokenStorage.KEY_HF_TOKEN).commit()
                            Log.i(TAG, "Migrated orphaned degraded token into encrypted storage.")
                        }
                    }
                }

                return storage
            } catch (e: Throwable) {
                Log.w(TAG, "Keystore initialization failed; entering or maintaining sticky degraded mode.", e)
                appPrefs.edit()
                    .putBoolean(KEYSTORE_DEGRADED, true)
                    .putString(KEYSTORE_DEGRADED_PATCH, currentPatch)
                    .putInt(KEYSTORE_DEGRADED_APP_VERSION, currentAppVersion)
                    .commit()
            }
        }

        val degradedStorage = DegradedTokenStorage(degradedPrefs)
        migrateTokenIfPresent(appPrefs, degradedStorage)
        return degradedStorage
    }

    fun create(context: Context): TokenStorage {
        val appPrefs = context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE)
        val degradedPrefs = context.getSharedPreferences(PREFS_DEGRADED_SETTINGS, Context.MODE_PRIVATE)
        return create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = Build.VERSION.SECURITY_PATCH ?: "",
            currentAppVersion = BuildConfig.VERSION_CODE,
            encryptedPrefsProvider = {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_ENCRYPTED_SETTINGS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        )
    }
}
