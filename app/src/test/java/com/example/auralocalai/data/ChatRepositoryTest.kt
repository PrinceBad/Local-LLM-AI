package com.example.auralocalai.data

import com.example.auralocalai.ui.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRoundTripMessagePersistence() = runTest {
        val storageDir = tempFolder.newFolder("chat_dir")
        val repo = ChatRepository(storageDir)

        val messages = listOf(
            ChatMessage(content = "Hello offline AI", isUser = true),
            ChatMessage(content = "Hello, how can I help you?", isUser = false)
        )

        assertTrue(repo.saveMessages(messages))

        val loaded = repo.loadMessages()
        assertEquals(2, loaded.size)
        assertEquals("Hello offline AI", loaded[0].content)
        assertTrue(loaded[0].isUser)
        assertEquals("Hello, how can I help you?", loaded[1].content)
        assertFalse(loaded[1].isUser)
    }

    @Test
    fun testCorruptedJsonFallbackWithPreservation() = runTest {
        val storageDir = tempFolder.newFolder("corrupted_chat_dir")
        val repo = ChatRepository(storageDir)

        // Intentionally write corrupt malformed JSON
        val malformedJson = "{ unclosed_json_bracket: true, content: 'broken' "
        repo.historyFile.writeText(malformedJson)

        val loaded = repo.loadMessages()
        // Must safely return empty list to prevent UI crash
        assertTrue("Corrupted file must yield empty message list", loaded.isEmpty())

        // Must preserve the corrupted file for recovery instead of silently destroying it
        assertTrue("corrupted backup file must exist", repo.corruptBackupFile.exists())
        assertEquals(malformedJson, repo.corruptBackupFile.readText())
    }

    @Test
    fun testClearMessages() = runTest {
        val storageDir = tempFolder.newFolder("clear_chat_dir")
        val repo = ChatRepository(storageDir)

        repo.saveMessages(listOf(ChatMessage(content = "Test message", isUser = true)))
        assertTrue(repo.historyFile.exists())

        assertTrue(repo.clearMessages())
        assertFalse(repo.historyFile.exists())
        assertTrue(repo.loadMessages().isEmpty())
    }
}
