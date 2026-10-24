package com.example.auralocalai.ui

import com.example.auralocalai.data.ModelPreset
import com.example.auralocalai.data.ModelSpeedTier
import com.example.auralocalai.data.ModelTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmViewModelLogicTest {

    @Test
    fun `stripThinkingBlocks removes single and unclosed think blocks`() {
        val raw = "<think>I need to think about 2+2.\nIt is 4.</think>The answer is 4."
        val stripped = LlmViewModel.stripThinkingBlocks(raw)
        assertEquals("The answer is 4.", stripped)

        val unclosed = "<think>Still thinking..."
        val strippedUnclosed = LlmViewModel.stripThinkingBlocks(unclosed)
        assertEquals("", strippedUnclosed)
    }

    @Test
    fun `history sanitization drops leading assistant message`() {
        val rawMessages = listOf(
            ChatMessage(content = "Initial assistant greeting", isUser = false),
            ChatMessage(content = "What is the capital of France?", isUser = true),
            ChatMessage(content = "<think>Paris is capital</think>Paris", isUser = false)
        )

        val candidates = mutableListOf<ChatMessage>()
        for (msg in rawMessages.asReversed()) {
            val contentToSend = if (!msg.isUser) {
                LlmViewModel.stripThinkingBlocks(msg.content)
            } else {
                msg.content
            }
            if (contentToSend.isNotBlank()) {
                candidates.add(msg.copy(content = contentToSend))
            }
        }
        candidates.reverse()

        // Drop any leading assistant message so history strictly starts with a user turn
        while (candidates.isNotEmpty() && !candidates.first().isUser) {
            candidates.removeAt(0)
        }

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.first().isUser)
        assertEquals("What is the capital of France?", candidates.first().content)
        assertEquals("Paris", candidates.last().content)
    }

    @Test
    fun `regeneration resolution drops user turn to avoid history duplication`() {
        val messages = listOf(
            ChatMessage(content = "First question", isUser = true),
            ChatMessage(content = "First answer", isUser = false),
            ChatMessage(content = "Second question", isUser = true),
            ChatMessage(content = "Second answer", isUser = false)
        )

        val last = messages.lastOrNull()
        assertNotNull(last)
        assertFalse(last!!.isUser)

        val userIndex = messages.indexOfLast { it.isUser }
        assertEquals(2, userIndex)

        val userTurn = messages[userIndex]
        val historyBeforeTurn = messages.subList(0, userIndex)

        assertEquals(2, historyBeforeTurn.size)
        assertEquals("First question", historyBeforeTurn[0].content)
        assertEquals("First answer", historyBeforeTurn[1].content)
        assertEquals("Second question", userTurn.content)
    }

    @Test
    fun `model catalog invariant checks`() {
        assertEquals(10, ModelPreset.presets.size)
        for (preset in ModelPreset.presets) {
            assertNotNull(preset.id)
            val sha = preset.expectedSha256
            assertNotNull("Expected non-null SHA for ${preset.id}", sha)
            assertTrue("Expected 64-char hex SHA-256 for ${preset.id}", sha!!.matches(Regex("^[a-f0-9]{64}$")))
            assertTrue("Expected pinned commit URL for ${preset.id}", preset.downloadUrl.contains("/resolve/"))
            assertTrue("Expected positive byte size for ${preset.id}", preset.sizeBytes > 300_000_000L)
            
            // Check Qwen thinking default is false
            if (preset.id.startsWith("qwen3")) {
                assertFalse("Qwen3 models must default thinking to false", preset.defaultThinkingEnabled)
            }
            // Check DeepSeek thinking default is true
            if (preset.id == "deepseek-1.5b") {
                assertTrue("DeepSeek R1 must default thinking to true", preset.defaultThinkingEnabled)
            }
        }
    }
}
