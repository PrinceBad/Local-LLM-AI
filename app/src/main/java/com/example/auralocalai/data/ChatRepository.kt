package com.example.auralocalai.data

import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.auralocalai.ui.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Immutable
@Serializable
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelId: String? = null,
    val messageCount: Int = 0
)

class ChatRepository(private val storageDir: File) {

    private val sessionMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    val historyFile: File
        get() = File(storageDir, "chat_history.json")

    val corruptBackupFile: File
        get() = File(storageDir, "chat_history.json.corrupted")

    val sessionsIndexFile: File
        get() = File(storageDir, "chat_sessions.json")

    val sessionsDir: File
        get() = File(storageDir, "sessions").apply { mkdirs() }

    internal fun sessionFile(sessionId: String): File = File(sessionsDir, "$sessionId.json")

    // ==========================================
    // Multi-Session Management API
    // ==========================================

    private fun readSessionsDirect(): List<ChatSession> {
        val file = sessionsIndexFile
        if (!file.exists()) return emptyList()
        return try {
            val raw = file.readText()
            if (raw.isBlank()) return emptyList()
            json.decodeFromString(ListSerializer(ChatSession.serializer()), raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode sessions index: ${e.message}", e)
            emptyList()
        }
    }

    private fun writeSessionsDirect(sessions: List<ChatSession>): Boolean {
        return try {
            storageDir.mkdirs()
            val tempFile = File(storageDir, "chat_sessions.json.tmp")
            val raw = json.encodeToString(ListSerializer(ChatSession.serializer()), sessions)
            tempFile.writeText(raw)
            if (!tempFile.renameTo(sessionsIndexFile)) {
                tempFile.copyTo(sessionsIndexFile, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sessions index: ${e.message}", e)
            false
        }
    }

    suspend fun loadSessions(): List<ChatSession> = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val file = sessionsIndexFile
            if (!file.exists()) {
                val migrated = migrateLegacyHistoryInternal()
                if (migrated != null) {
                    return@withContext listOf(migrated)
                }
                return@withContext emptyList()
            }
            readSessionsDirect()
        }
    }

    suspend fun saveSessions(sessions: List<ChatSession>): Boolean = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            writeSessionsDirect(sessions)
        }
    }

    suspend fun createSession(title: String = "New Chat", modelId: String? = null): ChatSession = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val newSession = ChatSession(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                modelId = modelId,
                messageCount = 0
            )
            val current = readSessionsDirect().toMutableList()
            current.add(0, newSession)
            writeSessionsDirect(current)

            // Initialize session messages file
            try {
                sessionsDir.mkdirs()
                val sFile = sessionFile(newSession.id)
                val tempFile = File(sessionsDir, "${newSession.id}.json.tmp")
                val raw = json.encodeToString(ListSerializer(ChatMessage.serializer()), emptyList())
                tempFile.writeText(raw)
                if (!tempFile.renameTo(sFile)) {
                    tempFile.copyTo(sFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session messages file: ${e.message}", e)
            }

            newSession
        }
    }

    suspend fun loadSessionMessages(sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val file = sessionFile(sessionId)
        if (!file.exists()) return@withContext emptyList()
        try {
            val raw = file.readText()
            if (raw.isBlank()) return@withContext emptyList()
            json.decodeFromString(ListSerializer(ChatMessage.serializer()), raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages for session $sessionId: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun saveSessionMessages(sessionId: String, messages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            try {
                sessionsDir.mkdirs()
                val sFile = sessionFile(sessionId)
                val tempFile = File(sessionsDir, "$sessionId.json.tmp")
                val raw = json.encodeToString(ListSerializer(ChatMessage.serializer()), messages)
                tempFile.writeText(raw)
                if (!tempFile.renameTo(sFile)) {
                    tempFile.copyTo(sFile, overwrite = true)
                    tempFile.delete()
                }

                // Update session index messageCount & updatedAt directly without triggering recursion
                val currentSessions = readSessionsDirect().toMutableList()
                val index = currentSessions.indexOfFirst { it.id == sessionId }
                if (index != -1) {
                    currentSessions[index] = currentSessions[index].copy(
                        updatedAt = System.currentTimeMillis(),
                        messageCount = messages.size
                    )
                    writeSessionsDirect(currentSessions)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save messages for session $sessionId: ${e.message}", e)
                false
            }
        }
    }

    suspend fun renameSession(sessionId: String, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val current = readSessionsDirect().toMutableList()
            val index = current.indexOfFirst { it.id == sessionId }
            if (index == -1) return@withContext false
            current[index] = current[index].copy(title = newTitle, updatedAt = System.currentTimeMillis())
            writeSessionsDirect(current)
        }
    }

    suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            try {
                val file = sessionFile(sessionId)
                if (file.exists()) file.delete()
                val current = readSessionsDirect().filterNot { it.id == sessionId }
                writeSessionsDirect(current)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session $sessionId: ${e.message}", e)
                false
            }
        }
    }

    private suspend fun migrateLegacyHistoryInternal(): ChatSession? {
        val legacy = historyFile
        if (!legacy.exists()) return null
        return try {
            val legacyMessages = loadMessagesInternal()
            if (legacyMessages.isEmpty()) return null
            val migratedSession = ChatSession(
                title = "Previous Chat",
                createdAt = legacyMessages.firstOrNull()?.timestamp ?: System.currentTimeMillis(),
                updatedAt = legacyMessages.lastOrNull()?.timestamp ?: System.currentTimeMillis(),
                messageCount = legacyMessages.size
            )
            writeSessionsDirect(listOf(migratedSession))
            
            sessionsDir.mkdirs()
            val sFile = sessionFile(migratedSession.id)
            val tempFile = File(sessionsDir, "${migratedSession.id}.json.tmp")
            val raw = json.encodeToString(ListSerializer(ChatMessage.serializer()), legacyMessages)
            tempFile.writeText(raw)
            if (!tempFile.renameTo(sFile)) {
                tempFile.copyTo(sFile, overwrite = true)
                tempFile.delete()
            }

            val backup = File(storageDir, "chat_history.json.migrated")
            legacy.renameTo(backup)
            Log.i(TAG, "Successfully migrated legacy chat_history.json to session ${migratedSession.id}")
            migratedSession
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate legacy chat_history: ${e.message}", e)
            null
        }
    }

    /**
     * Idempotently migrates any legacy single-file chat_history.json into a "Previous Chat" session.
     * Preserves the legacy file as chat_history.json.migrated once confirmed.
     */
    suspend fun migrateLegacyHistoryIfNeeded(): ChatSession? = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            migrateLegacyHistoryInternal()
        }
    }

    // ==========================================
    // Backward-Compatible Legacy Methods
    // ==========================================

    private fun loadMessagesInternal(): List<ChatMessage> {
        val file = historyFile
        if (!file.exists()) return emptyList()
        return try {
            val jsonString = file.readText()
            if (jsonString.isBlank()) return emptyList()
            json.decodeFromString(ListSerializer(ChatMessage.serializer()), jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Corrupted chat history JSON detected: ${e.message}. Creating backup.", e)
            try {
                file.copyTo(corruptBackupFile, overwrite = true)
                Log.w(TAG, "Corrupt chat history preserved at: ${corruptBackupFile.absolutePath}")
            } catch (copyEx: Exception) {
                Log.e(TAG, "Failed to create corrupt history backup", copyEx)
            }
            emptyList()
        }
    }

    suspend fun loadMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        loadMessagesInternal()
    }

    suspend fun saveMessages(messages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        try {
            storageDir.mkdirs()
            val tempFile = File(storageDir, "chat_history.json.tmp")
            val jsonString = json.encodeToString(ListSerializer(ChatMessage.serializer()), messages)
            tempFile.writeText(jsonString)
            if (!tempFile.renameTo(historyFile)) {
                tempFile.copyTo(historyFile, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chat history: ${e.message}", e)
            false
        }
    }

    suspend fun clearMessages(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (historyFile.exists()) {
                historyFile.delete()
            } else true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear chat history: ${e.message}", e)
            false
        }
    }

    companion object {
        private const val TAG = "ChatRepository"
    }
}
