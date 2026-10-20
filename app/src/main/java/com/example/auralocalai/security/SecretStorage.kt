package com.example.auralocalai.security

/**
 * Common contract for hardware-encrypted secret storage.
 * Shared between EncryptedTokenStorage and DegradedTokenStorage.
 */
interface SecretStorage {
    val isHardwareEncrypted: Boolean
    fun getSecret(key: String): String
    fun saveSecret(key: String, value: String): Boolean
    fun clearSecret(key: String): Boolean

    companion object {
        const val KEY_SEARCH_API_KEY = "search_api_key"
    }
}
