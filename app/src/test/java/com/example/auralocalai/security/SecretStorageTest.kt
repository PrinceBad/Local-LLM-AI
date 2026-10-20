package com.example.auralocalai.security

import org.junit.Assert.*
import org.junit.Test

class SecretStorageTest {

    @Test
    fun testEncryptedStorageSaveGetClearSecret() {
        val prefs = FakeSharedPreferences()
        val storage = EncryptedTokenStorage(prefs)

        assertTrue(storage.isHardwareEncrypted)
        assertEquals("", storage.getSecret(SecretStorage.KEY_SEARCH_API_KEY))

        val saved = storage.saveSecret(SecretStorage.KEY_SEARCH_API_KEY, "test_brave_api_key_123")
        assertTrue(saved)
        assertEquals("test_brave_api_key_123", storage.getSecret(SecretStorage.KEY_SEARCH_API_KEY))

        val cleared = storage.clearSecret(SecretStorage.KEY_SEARCH_API_KEY)
        assertTrue(cleared)
        assertEquals("", storage.getSecret(SecretStorage.KEY_SEARCH_API_KEY))
    }

    @Test
    fun testDegradedStorageSaveGetClearSecret() {
        val prefs = FakeSharedPreferences()
        val storage = DegradedTokenStorage(prefs)

        assertFalse(storage.isHardwareEncrypted)
        assertEquals("", storage.getSecret(SecretStorage.KEY_SEARCH_API_KEY))

        val saved = storage.saveSecret(SecretStorage.KEY_SEARCH_API_KEY, "test_serper_api_key_456")
        assertTrue(saved)
        assertEquals("test_serper_api_key_456", storage.getSecret(SecretStorage.KEY_SEARCH_API_KEY))

        val cleared = storage.clearSecret(SecretStorage.KEY_SEARCH_API_KEY)
        assertTrue(cleared)
        assertEquals("", storage.getSecret(SecretStorage.KEY_SEARCH_API_KEY))
    }

    @Test
    fun testDegradedSearchSecretMigrationOnRecovery() {
        val appPrefs = FakeSharedPreferences()
        val degradedPrefs = FakeSharedPreferences()
        val encryptedPrefs = FakeSharedPreferences()

        // 1. Initial degraded mode
        TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-01-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = { throw RuntimeException("Keystore unavailable") }
        )

        // 2. User saves search API key while degraded
        degradedPrefs.edit().putString(SecretStorage.KEY_SEARCH_API_KEY, "brave_secret_key_789").commit()

        // 3. System patch update triggers auto-recovery re-probe
        val recoveredStorage = TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-03-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = { encryptedPrefs }
        )

        assertTrue(recoveredStorage.isHardwareEncrypted)
        assertEquals("brave_secret_key_789", recoveredStorage.getSecret(SecretStorage.KEY_SEARCH_API_KEY))
        assertNull(degradedPrefs.getString(SecretStorage.KEY_SEARCH_API_KEY, null))
    }
}
