package com.example.auralocalai.data

import android.util.Log
import com.example.auralocalai.ui.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class ChatRepository(private val storageDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    val historyFile: File
        get() = File(storageDir, "chat_history.json")

    val corruptBackupFile: File
        get() = File(storageDir, "chat_history.json.corrupted")

    /**
     * Loads chat history from disk.
     * If JSON decoding fails, preserves the bad file to chat_history.json.corrupted
     * before returning an empty list, preventing destructive overwrite on subsequent save.
     */
    suspend fun loadMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        val file = historyFile
        if (!file.exists()) return@withContext emptyList()
        try {
            val jsonString = file.readText()
            if (jsonString.isBlank()) return@withContext emptyList()
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

    /**
     * Atomically saves chat history using a temporary write-then-rename strategy.
     */
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

    /**
     * Clears chat history from disk.
     */
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
