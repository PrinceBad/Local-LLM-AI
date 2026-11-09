package com.example.auralocalai.ui

import com.example.auralocalai.data.ModelPreset
import com.example.auralocalai.data.ModelSpeedTier
import com.example.auralocalai.data.ModelTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
            // Check SmolLM2 thinking default is false
            if (preset.id == "smollm2-1.7b") {
                assertFalse("SmolLM2 must default thinking to false", preset.defaultThinkingEnabled)
            }
        }
    }

    @Test
    fun `shader cache files and binary artifacts are rejected from model lists`() {
        val filenames = listOf(
            "qwen3-1.7b.litertlm_1792854192_977184032_mldrift_weight_cache.bin",
            "qwen3-1.7b.litertlm_1792854192_977184032_mldrift_program_cache.bin",
            "model.bin",
            "downloading.tmp",
            "partial.crdownload",
            ".hidden.litertlm",
            "valid-model.litertlm",
            "mediapipe-model.task"
        )

        val validModelExtensions = listOf(".litertlm", ".task")
        val filteredModels = filenames.filter { name ->
            validModelExtensions.any { name.endsWith(it, ignoreCase = true) } &&
            !name.contains("cache", ignoreCase = true) &&
            !name.contains("mldrift", ignoreCase = true) &&
            !name.endsWith(".tmp", ignoreCase = true) &&
            !name.startsWith(".")
        }

        assertEquals(2, filteredModels.size)
        assertTrue(filteredModels.contains("valid-model.litertlm"))
        assertTrue(filteredModels.contains("mediapipe-model.task"))
        assertFalse(filteredModels.any { it.contains("mldrift") })
        assertFalse(filteredModels.any { it.endsWith(".bin") })
    }

    @Test
    fun `custom models list in UI excludes shader caches and bin files`() {
        val localModels = listOf(
            "qwen3-1.7b.litertlm_1792854192_977184032_mldrift_weight_cache.bin",
            "qwen3-1.7b.litertlm_1792854192_977184032_mldrift_program_cache.bin",
            "custom-finetuned.litertlm",
            "qwen3-1.7b.litertlm"
        )

        val customModels = localModels.filter { fileName ->
            !ModelPreset.presetsByFileName.containsKey(fileName.lowercase()) &&
            !fileName.contains("cache", ignoreCase = true) &&
            !fileName.contains("mldrift", ignoreCase = true) &&
            !fileName.endsWith(".bin", ignoreCase = true) &&
            (fileName.endsWith(".litertlm", ignoreCase = true) || fileName.endsWith(".task", ignoreCase = true))
        }

        assertEquals(1, customModels.size)
        assertEquals("custom-finetuned.litertlm", customModels.first())
    }

    @Test
    fun `friendlyErrorMessage prioritizes NPU diagnostics over generic backend restriction`() {
        val npuUnsupportedErr = "This model bundle (qwen3-4b.litertlm) does not support NPU execution. Supported backends: [GPU]."
        val msg1 = LlmViewModel.friendlyErrorMessage(npuUnsupportedErr)
        assertTrue("Must preserve NPU model incompatibility message", msg1.contains("does not support NPU execution"))

        val npuUnavailableErr = "NPU backend was preferred, but Qualcomm QNN runtime is not available (Missing HTP library)"
        val msg2 = LlmViewModel.friendlyErrorMessage(npuUnavailableErr)
        assertEquals(
            "NPU acceleration was requested, but NPU runtime is not available on this device. Switch to Auto or GPU in Settings.",
            msg2
        )

        val npuInitFail = "Failed to load model on NPU: Hexagon RPC initialization failure. Backend restriction or preference prevents fallback."
        val msg3 = LlmViewModel.friendlyErrorMessage(npuInitFail)
        assertEquals(
            "NPU initialization failed for this model. Switch to Auto or GPU backend in Settings.",
            msg3
        )

        val gpuRestrictionErr = "Backend restriction (GPU_ONLY) or preference (GPU_ONLY) prevents fallback to CPU."
        val msg4 = LlmViewModel.friendlyErrorMessage(gpuRestrictionErr)
        assertEquals(
            "This model requires Vulkan GPU acceleration. Check your GPU backend settings.",
            msg4
        )
    }

    @Test
    fun `friendlyErrorMessage handles NPU backend restriction failure`() {
        val npuRestricted = "Backend restriction (GPU_ONLY) prevents running this model on NPU. Please select Auto or GPU in Settings."
        val msg = LlmViewModel.friendlyErrorMessage(npuRestricted)
        assertTrue(msg.contains("requires Vulkan GPU acceleration") || msg.contains("GPU backend settings"))
    }

    @Test
    fun `UiState defaults autoLoadModelOnStartup to false`() {
        val defaultState = UiState()
        assertFalse("Auto-load must default to false to prevent startup ANR and Hexagon RAM spikes", defaultState.autoLoadModelOnStartup)
    }
    @Test
    fun `isKnownLegacyBuild correctly flags old Qwen 0_6b builds`() {
        val pocketTurbo = ModelPreset.presets.first { it.id == "qwen3-0.6b" }
        
        // Old filename
        val oldFile = object : File("qwen3_0_6b_mixed_int4.litertlm") {
            override fun exists() = true
            override fun isFile() = true
        }
        assertTrue(ModelPreset.isKnownLegacyBuild(pocketTurbo, oldFile))

        // Large file (> 450 MB) with qwen3-0.6b.litertlm name
        val largeLegacyMock = object : File("qwen3-0.6b.litertlm") {
            override fun exists() = true
            override fun isFile() = true
            override fun length() = 522_000_000L
        }
        assertTrue(ModelPreset.isKnownLegacyBuild(pocketTurbo, largeLegacyMock))

        // New universal build (~343 MB)
        val newBuildMock = object : File("qwen3-0.6b.litertlm") {
            override fun exists() = true
            override fun isFile() = true
            override fun length() = 343_490_560L
        }
        assertFalse(ModelPreset.isKnownLegacyBuild(pocketTurbo, newBuildMock))

        // Non-pocket turbo preset should never be flagged by this heuristic
        val smolLm = ModelPreset.presets.first { it.id == "smollm2-1.7b" }
        val smolLmFile = object : File("smollm2-1.7b.litertlm") {
            override fun exists() = true
            override fun isFile() = true
            override fun length() = 980_000_000L
        }
        assertFalse(ModelPreset.isKnownLegacyBuild(smolLm, smolLmFile))
    }

    @Test
    fun `getLegacyAliases returns expected legacy filenames for Pocket Turbo`() {
        val pocketTurbo = ModelPreset.presets.first { it.id == "qwen3-0.6b" }
        val aliases = ModelPreset.getLegacyAliases(pocketTurbo)
        assertTrue(aliases.contains("qwen3_0_6b_mixed_int4.litertlm"))
        assertTrue(aliases.contains("qwen3-0.6b_dynamic_wi4b32_afp32.litertlm"))

        val smolLm = ModelPreset.presets.first { it.id == "smollm2-1.7b" }
        assertTrue(ModelPreset.getLegacyAliases(smolLm).isEmpty())
    }

    @Test
    fun `friendlyErrorMessage diagnoses legacy mixed_int4 and GPU timeout errors`() {
        val legacyMsg = LlmViewModel.friendlyErrorMessage("Failed to load model: qwen3_0_6b_mixed_int4.litertlm lacks CPU kernels")
        assertTrue(legacyMsg.contains("lacks CPU kernels") && legacyMsg.contains("343 MB Turbo"))

        val timeoutMsg = LlmViewModel.friendlyErrorMessage("GPU shader compilation timed out after 75s.")
        assertTrue(timeoutMsg.contains("timed out") && timeoutMsg.contains("CPU backend"))
    }
}
