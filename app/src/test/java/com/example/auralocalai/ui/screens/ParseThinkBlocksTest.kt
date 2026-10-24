package com.example.auralocalai.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.auralocalai.data.ModelPreset

/**
 * Unit tests for [parseThinkBlocks] and model reasoning resolution logic.
 * Pure Kotlin string manipulation with zero Android framework dependencies.
 */
class ParseThinkBlocksTest {

    @Test
    fun testSingleClosedThinkBlock() {
        val raw = "<think>Let's calculate 2 + 2 = 4.</think>The answer is 4."
        val result = parseThinkBlocks(raw)
        
        assertEquals("Let's calculate 2 + 2 = 4.", result.thinkContent)
        assertFalse("Block was closed cleanly", result.hasUnclosedThink)
        assertEquals("The answer is 4.", result.mainContent)
    }

    @Test
    fun testUnclosedThinkBlock_MidGenerationOrCancelled() {
        val raw = "<think>Currently analyzing the user's prompt step by step..."
        val result = parseThinkBlocks(raw)
        
        assertEquals("Currently analyzing the user's prompt step by step...", result.thinkContent)
        assertTrue("Block remains unclosed upon cancellation", result.hasUnclosedThink)
        assertEquals("", result.mainContent)
    }

    @Test
    fun testMultipleThinkBlocks_Interleaved() {
        val raw = "<think>First phase reasoning</think>Intermediate observation.<think>Second phase refinement</think>Final answer."
        val result = parseThinkBlocks(raw)
        
        assertEquals("First phase reasoning\n\n---\n\nSecond phase refinement", result.thinkContent)
        assertFalse("All blocks closed", result.hasUnclosedThink)
        assertEquals("Intermediate observation.\n\nFinal answer.", result.mainContent)
    }

    @Test
    fun testMultipleThinkBlocks_TrailingUnclosed() {
        val raw = "<think>Step 1 done</think>Midway.<think>Step 2 interrupted"
        val result = parseThinkBlocks(raw)
        
        assertEquals("Step 1 done\n\n---\n\nStep 2 interrupted", result.thinkContent)
        assertTrue("Trailing block is unclosed", result.hasUnclosedThink)
        assertEquals("Midway.", result.mainContent)
    }

    @Test
    fun testNoThinkBlock_StandardOrCodingOutput() {
        val raw = "fun main() {\n    println(\"Hello World\")\n}"
        val result = parseThinkBlocks(raw)
        
        assertNull("No think blocks present", result.thinkContent)
        assertFalse(result.hasUnclosedThink)
        assertEquals("fun main() {\n    println(\"Hello World\")\n}", result.mainContent)
    }

    @Test
    fun testThinkBlock_EmbeddedInCode() {
        val raw = "val x = 1\n<think>Reasoning about x</think>\nval y = x + 1"
        val result = parseThinkBlocks(raw)
        
        assertEquals("Reasoning about x", result.thinkContent)
        assertFalse(result.hasUnclosedThink)
        assertEquals("val x = 1\n\nval y = x + 1", result.mainContent)
    }

    @Test
    fun testEmptyAndWhitespaceThinkBlocks() {
        val emptyRaw = "<think></think>Direct response"
        val emptyResult = parseThinkBlocks(emptyRaw)
        assertNull("Empty think block resolves to null content", emptyResult.thinkContent)
        assertFalse(emptyResult.hasUnclosedThink)
        assertEquals("Direct response", emptyResult.mainContent)

        val whitespaceRaw = "<think>   \n\t   </think>Direct response"
        val wsResult = parseThinkBlocks(whitespaceRaw)
        assertNull("Whitespace-only think block resolves to null content", wsResult.thinkContent)
        assertFalse(wsResult.hasUnclosedThink)
        assertEquals("Direct response", wsResult.mainContent)
    }

    @Test
    fun testLeadingWhitespaceBeforeThink() {
        val raw = "\n\n  <think>Analyzing...</think>Here is the result."
        val result = parseThinkBlocks(raw)
        
        assertEquals("Analyzing...", result.thinkContent)
        assertFalse(result.hasUnclosedThink)
        assertEquals("Here is the result.", result.mainContent)
    }

    @Test
    fun testStripThinkingBlocks_ForEngineHistory() {
        val rawClosed = "<think>Let me analyze this problem step by step.\nThinking complete.</think>Here is the solution to your question."
        assertEquals("Here is the solution to your question.", com.example.auralocalai.ui.LlmViewModel.stripThinkingBlocks(rawClosed))

        val rawUnclosed = "<think>Still working on analyzing..."
        assertEquals("", com.example.auralocalai.ui.LlmViewModel.stripThinkingBlocks(rawUnclosed))

        val rawInterleaved = "<think>Initial thought</think>Middle answer.<think>Second thought</think>Final answer."
        assertEquals("Middle answer.Final answer.", com.example.auralocalai.ui.LlmViewModel.stripThinkingBlocks(rawInterleaved))

        val noThink = "Simple prompt without any reasoning tags."
        assertEquals("Simple prompt without any reasoning tags.", com.example.auralocalai.ui.LlmViewModel.stripThinkingBlocks(noThink))
    }

    @Test
    fun testModelPresetReasoningResolution() {
        // 1. Catalog reasoning models
        assertTrue(ModelPreset.isReasoningModel("deepseek-1.5b"))
        assertFalse(ModelPreset.isKnownNonReasoningModel("deepseek-1.5b"))
        assertTrue(ModelPreset.isReasoningModel("deepseek-r1-7b"))
        assertFalse(ModelPreset.isKnownNonReasoningModel("deepseek-r1-7b"))

        // 2. Catalog known non-reasoning models
        assertFalse(ModelPreset.isReasoningModel("qwen2.5-coder-1.5b"))
        assertTrue(ModelPreset.isKnownNonReasoningModel("qwen2.5-coder-1.5b"))
        assertFalse(ModelPreset.isReasoningModel("gemma4-e2b"))
        assertTrue(ModelPreset.isKnownNonReasoningModel("gemma4-e2b"))
        assertFalse(ModelPreset.isReasoningModel("gemma4-e2b-uncensored"))
        assertTrue(ModelPreset.isKnownNonReasoningModel("gemma4-e2b-uncensored"))

        // 3. Custom / unknown reasoning models
        assertTrue(ModelPreset.isReasoningModel("custom-deepseek-r1-7b.litertlm"))
        assertFalse(ModelPreset.isKnownNonReasoningModel("custom-deepseek-r1-7b.litertlm"))
        assertTrue(ModelPreset.isReasoningModel("qwq-32b-preview.litertlm"))
        assertFalse(ModelPreset.isKnownNonReasoningModel("qwq-32b-preview.litertlm"))

        // 4. Custom unknown model (fallback to startsWith("<think>") in UI layer)
        assertFalse(ModelPreset.isReasoningModel("arbitrary_model.litertlm"))
        assertFalse(ModelPreset.isKnownNonReasoningModel("arbitrary_model.litertlm"))

        // 5. Null handling
        assertFalse(ModelPreset.isReasoningModel(null))
        assertFalse(ModelPreset.isKnownNonReasoningModel(null))
    }
}
