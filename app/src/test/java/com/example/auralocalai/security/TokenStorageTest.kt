package com.example.auralocalai.security

import org.junit.Assert.*
import org.junit.Test

class TokenStorageTest {

    @Test
    fun testSuccessfulMigrationFromLegacyPlaintext() {
        val appPrefs = FakeSharedPreferences()
        appPrefs.edit().putString("hf_token", "hf_legacy_token_123").commit()

        val degradedPrefs = FakeSharedPreferences()
        val encryptedPrefs = FakeSharedPreferences()

        val storage = TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-03-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = { encryptedPrefs }
        )

        assertTrue("Storage should be hardware encrypted", storage.isHardwareEncrypted)
        assertEquals("hf_legacy_token_123", storage.getToken())
        // Legacy plaintext token must be wiped after confirmed write
        assertNull("Legacy plaintext key should be wiped from app_settings", appPrefs.getString("hf_token", null))
    }

    @Test
    fun testRollbackProtectionWhenTargetWriteFails() {
        val appPrefs = FakeSharedPreferences()
        appPrefs.edit().putString("hf_token", "hf_critical_token").commit()

        val failingStoragePrefs = FakeSharedPreferences(shouldFailCommit = true)
        val targetStorage = EncryptedTokenStorage(failingStoragePrefs)

        val success = TokenStorageCoordinator.migrateTokenIfPresent(appPrefs, targetStorage)

        assertFalse("Migration must signal failure", success)
        // Legacy key must remain intact
        assertEquals(
            "Legacy key must be preserved on write failure",
            "hf_critical_token",
            appPrefs.getString("hf_token", null)
        )
    }

    @Test
    fun testStickyDegradedModePersistenceWithoutReprobe() {
        val appPrefs = FakeSharedPreferences()
        val degradedPrefs = FakeSharedPreferences()
        var providerCallCount = 0

        // First attempt: Keystore throws
        val initialStorage = TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-03-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = {
                providerCallCount++
                throw RuntimeException("Simulated Keystore hardware crash")
            }
        )

        assertFalse("Should be degraded", initialStorage.isHardwareEncrypted)
        assertTrue("Sticky flag must be set", appPrefs.getBoolean("keystore_degraded", false))
        assertEquals(1, providerCallCount)

        // Second startup on same patch and app version: should NOT probe Keystore again
        val secondStorage = TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-03-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = {
                providerCallCount++
                FakeSharedPreferences()
            }
        )

        assertFalse("Should still be degraded without flip-flopping", secondStorage.isHardwareEncrypted)
        assertEquals("Keystore provider must not be probed on same build", 1, providerCallCount)
    }

    @Test
    fun testDegradedAutoRecoveryOnOsSecurityPatchUpdate() {
        val appPrefs = FakeSharedPreferences()
        val degradedPrefs = FakeSharedPreferences()

        // 1. Enter degraded mode on old patch
        TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-01-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = { throw RuntimeException("Keystore broken in old firmware") }
        )
        assertTrue(appPrefs.getBoolean("keystore_degraded", false))

        // 2. Reboot after firmware update (new patch: 2026-03-01) - Keystore now works!
        val recoveredStorage = TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-03-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = { FakeSharedPreferences() }
        )

        assertTrue("Should automatically recover hardware encrypted mode", recoveredStorage.isHardwareEncrypted)
        assertFalse("Sticky degraded flag must be cleared", appPrefs.getBoolean("keystore_degraded", false))
        assertEquals("", appPrefs.getString("keystore_degraded_patch", ""))
    }

    @Test
    fun testDegradedAutoRecoveryOnAppVersionCodeUpdate() {
        val appPrefs = FakeSharedPreferences()
        val degradedPrefs = FakeSharedPreferences()

        // 1. Enter degraded mode on app version 1
        TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-03-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = { throw RuntimeException("Buggy Keystore in v1") }
        )
        assertTrue(appPrefs.getBoolean("keystore_degraded", false))

        // 2. Launch after app update to version 2
        val recoveredStorage = TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-03-01",
            currentAppVersion = 2,
            encryptedPrefsProvider = { FakeSharedPreferences() }
        )

        assertTrue("Should recover on app version update", recoveredStorage.isHardwareEncrypted)
        assertFalse("Sticky degraded flag must be cleared", appPrefs.getBoolean("keystore_degraded", false))
    }

    @Test
    fun testTokenSaveAndClear() {
        val prefs = FakeSharedPreferences()
        val storage = EncryptedTokenStorage(prefs)

        assertTrue(storage.saveToken("test_token_xyz"))
        assertEquals("test_token_xyz", storage.getToken())

        assertTrue(storage.clearToken())
        assertEquals("", storage.getToken())
    }
    @Test
    fun testDegradedToEncryptedTokenMigrationOnKeystoreRecovery() {
        val appPrefs = FakeSharedPreferences()
        val degradedPrefs = FakeSharedPreferences()
        val encryptedPrefs = FakeSharedPreferences()

        // 1. Device was in degraded mode
        appPrefs.edit()
            .putBoolean("keystore_degraded", true)
            .putString("keystore_degraded_patch", "2026-01-01")
            .putInt("keystore_degraded_app_version", 1)
            .commit()

        // 2. User saved a token while the device was in degraded mode
        degradedPrefs.edit().putString("hf_token", "hf_user_saved_while_degraded_456").commit()

        // 3. System updates (patch bump: 2026-03-01) and Keystore re-probe succeeds
        val recoveredStorage = TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-03-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = { encryptedPrefs }
        )

        // Assert: hardware encrypted mode active
        assertTrue("Restored storage should be hardware encrypted", recoveredStorage.isHardwareEncrypted)
        // Assert: token saved in degraded mode is NOT lost and is now returned by encrypted storage!
        assertEquals("Token from degraded mode must survive recovery into encrypted storage",
            "hf_user_saved_while_degraded_456", recoveredStorage.getToken())
        // Assert: token in encryptedPrefs is populated
        assertEquals("hf_user_saved_while_degraded_456", encryptedPrefs.getString("hf_token", null))
        // Assert: degraded plaintext storage is wiped after successful migration
        assertNull("Degraded plaintext token must be wiped after sync", degradedPrefs.getString("hf_token", null))
        // Assert: sticky degraded flags are cleared
        assertFalse("keystore_degraded flag must be cleared", appPrefs.getBoolean("keystore_degraded", false))
    }

    @Test
    fun testDegradedToEncryptedTokenMigrationRollbackOnWriteFailure() {
        val appPrefs = FakeSharedPreferences()
        val degradedPrefs = FakeSharedPreferences()
        // Encrypted prefs will fail on commit
        val failingEncryptedPrefs = FakeSharedPreferences(shouldFailCommit = true)

        // 1. Device in degraded mode
        appPrefs.edit()
            .putBoolean("keystore_degraded", true)
            .putString("keystore_degraded_patch", "2026-01-01")
            .putInt("keystore_degraded_app_version", 1)
            .commit()

        // 2. User saved token while degraded
        degradedPrefs.edit().putString("hf_token", "hf_important_token_789").commit()

        // 3. System updates (patch bump) but encryptedStorage.saveToken fails during migration
        val storage = TokenStorageCoordinator.create(
            appPrefs = appPrefs,
            degradedPrefs = degradedPrefs,
            currentPatch = "2026-03-01",
            currentAppVersion = 1,
            encryptedPrefsProvider = { failingEncryptedPrefs }
        )

        // Assert: Recovery aborted; stays in degraded mode
        assertFalse("Must remain in degraded mode when migration write fails", storage.isHardwareEncrypted)
        // Assert: User token is preserved in degraded storage
        assertEquals("hf_important_token_789", storage.getToken())
        assertEquals("hf_important_token_789", degradedPrefs.getString("hf_token", null))
        // Assert: Sticky degraded flag is NOT cleared
        assertTrue("Sticky flag must remain true on rollback", appPrefs.getBoolean("keystore_degraded", false))
    }
}