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

    @Test
    fun testCreateAndLoadSessions() = runTest {
        val storageDir = tempFolder.newFolder("sessions_create_dir")
        val repo = ChatRepository(storageDir)

        val created = repo.createSession(title = "Research on Local AI", modelId = "gemma-3-1b")
        assertNotNull(created.id)
        assertEquals("Research on Local AI", created.title)
        assertEquals("gemma-3-1b", created.modelId)
        assertEquals(0, created.messageCount)

        val sessions = repo.loadSessions()
        assertEquals(1, sessions.size)
        assertEquals(created.id, sessions[0].id)
        assertEquals("Research on Local AI", sessions[0].title)
    }

    @Test
    fun testSaveAndLoadSessionMessages() = runTest {
        val storageDir = tempFolder.newFolder("sessions_msg_dir")
        val repo = ChatRepository(storageDir)

        val session = repo.createSession(title = "Coding Chat")
        val messages = listOf(
            ChatMessage(content = "How to write a coroutine?", isUser = true),
            ChatMessage(content = "Use launch or async in a CoroutineScope.", isUser = false)
        )

        val saved = repo.saveSessionMessages(session.id, messages)
        assertTrue(saved)

        val loaded = repo.loadSessionMessages(session.id)
        assertEquals(2, loaded.size)
        assertEquals("How to write a coroutine?", loaded[0].content)
        assertEquals("Use launch or async in a CoroutineScope.", loaded[1].content)

        // Verify session index updated messageCount
        val sessions = repo.loadSessions()
        val updatedSession = sessions.find { it.id == session.id }
        assertNotNull(updatedSession)
        assertEquals(2, updatedSession?.messageCount)
    }

    @Test
    fun testRenameSession() = runTest {
        val storageDir = tempFolder.newFolder("sessions_rename_dir")
        val repo = ChatRepository(storageDir)

        val session = repo.createSession(title = "Initial Title")
        assertTrue(repo.renameSession(session.id, "Updated Meaningful Title"))

        val sessions = repo.loadSessions()
        val found = sessions.find { it.id == session.id }
        assertNotNull(found)
        assertEquals("Updated Meaningful Title", found?.title)
    }

    @Test
    fun testDeleteSession() = runTest {
        val storageDir = tempFolder.newFolder("sessions_delete_dir")
        val repo = ChatRepository(storageDir)

        val sessionA = repo.createSession(title = "Session A")
        val sessionB = repo.createSession(title = "Session B")

        repo.saveSessionMessages(sessionA.id, listOf(ChatMessage(content = "A", isUser = true)))
        repo.saveSessionMessages(sessionB.id, listOf(ChatMessage(content = "B", isUser = true)))

        assertTrue(repo.sessionFile(sessionA.id).exists())

        assertTrue(repo.deleteSession(sessionA.id))

        assertFalse(repo.sessionFile(sessionA.id).exists())
        val remaining = repo.loadSessions()
        assertEquals(1, remaining.size)
        assertEquals(sessionB.id, remaining[0].id)
    }

    @Test
    fun testMigrateLegacyHistoryIdempotent() = runTest {
        val storageDir = tempFolder.newFolder("legacy_migrate_dir")
        val repo = ChatRepository(storageDir)

        // 1. Initially no sessions and no legacy file -> returns empty
        val initialSessions = repo.loadSessions()
        assertTrue(initialSessions.isEmpty())

        // 2. Save legacy messages directly using backward-compatible saveMessages
        val legacy = listOf(
            ChatMessage(content = "Legacy turn 1", isUser = true),
            ChatMessage(content = "Legacy turn 2", isUser = false)
        )
        repo.saveMessages(legacy)
        assertTrue(repo.historyFile.exists())

        // 3. Migrate legacy history
        val migrated = repo.migrateLegacyHistoryIfNeeded()
        assertNotNull(migrated)
        assertEquals("Previous Chat", migrated?.title)
        assertEquals(2, migrated?.messageCount)

        // 4. Verify migrated file is preserved with .migrated suffix and original is gone
        assertFalse(repo.historyFile.exists())
        val backupFile = File(storageDir, "chat_history.json.migrated")
        assertTrue(backupFile.exists())

        // 5. Verify session messages were loaded properly
        val sessionMessages = repo.loadSessionMessages(migrated!!.id)
        assertEquals(2, sessionMessages.size)
        assertEquals("Legacy turn 1", sessionMessages[0].content)

        // 6. Calling migration again must be a no-op (idempotency)
        val repeatMigration = repo.migrateLegacyHistoryIfNeeded()
        assertNull("Second migration attempt must be null (no-op)", repeatMigration)
    }
}
