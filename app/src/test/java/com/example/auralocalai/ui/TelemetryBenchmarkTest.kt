package com.example.auralocalai.ui

import com.example.auralocalai.data.ModelPreset
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TelemetryBenchmarkTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun testStandardDecodeThroughputCalculation() {
        val t0 = 1_000_000_000L // 1.0s in ns
        val tFirst = 1_400_000_000L // 1.4s in ns (TTFT = 400ms)
        val tEnd = 6_400_000_000L // 6.4s in ns (Decode duration = 5.0s)
        val tokenCount = 101 // 1 initial + 100 decode tokens

        val ttftMs = (tFirst - t0) / 1_000_000
        val decodeDurationSec = (tEnd - tFirst) / 1_000_000_000.0
        val decodeSpeedTokPerSec = (tokenCount - 1) / decodeDurationSec

        val telemetry = InferenceTelemetry(
            ttftMs = ttftMs,
            promptTokens = 50,
            decodeSpeedTokPerSec = decodeSpeedTokPerSec,
            decodeTokens = tokenCount,
            wasCancelled = false
        )

        assertEquals(400L, telemetry.ttftMs)
        assertEquals(5.0, decodeDurationSec, 0.001)
        assertEquals(20.0, telemetry.decodeSpeedTokPerSec, 0.001)
        assertEquals(101, telemetry.decodeTokens)
        assertFalse(telemetry.wasCancelled)
    }

    @Test
    fun testCancelledRunPreservesValidThroughputWithFlag() {
        // User stopped mid-generation at token 41 of an expected 200
        val t0 = 0L
        val tFirst = 500_000_000L // 500ms TTFT
        val tEnd = 2_500_000_000L // Stopped at 2.5s (2.0s decode time for 40 decode tokens)
        val tokenCount = 41 // 1 initial + 40 decode tokens

        val ttftMs = (tFirst - t0) / 1_000_000
        val decodeDurationSec = (tEnd - tFirst) / 1_000_000_000.0
        val decodeSpeedTokPerSec = (tokenCount - 1) / decodeDurationSec

        val telemetry = InferenceTelemetry(
            ttftMs = ttftMs,
            promptTokens = 120,
            decodeSpeedTokPerSec = decodeSpeedTokPerSec,
            decodeTokens = tokenCount,
            wasCancelled = true
        )

        assertEquals(500L, telemetry.ttftMs)
        assertEquals(2.0, decodeDurationSec, 0.001)
        // 40 tokens / 2.0s = 20.0 tok/s
        assertEquals(20.0, telemetry.decodeSpeedTokPerSec, 0.001)
        assertEquals(41, telemetry.decodeTokens)
        assertTrue("Cancelled run must be flagged as stopped/cancelled", telemetry.wasCancelled)
    }

    @Test
    fun testPromptTokenEstimation() {
        fun estimate(prompt: String, hasImage: Boolean = false): Int {
            val textTokens = (prompt.length / 3.8).toInt().coerceAtLeast(1)
            val imageTokens = if (hasImage) 256 else 0
            return textTokens + imageTokens
        }

        // Short prompt
        val shortPrompt = "Hello, what is 2+2?"
        val shortTokens = estimate(shortPrompt)
        assertTrue(shortTokens in 4..10)

        // Long OCR / PDF injected text (~3800 chars -> ~1000 tokens)
        val longOcrText = "A".repeat(3800)
        val longTokens = estimate(longOcrText)
        assertEquals(1000, longTokens)

        // Multimodal image attachment adds 256 vision patch tokens
        val imageTokens = estimate("Describe this image", hasImage = true)
        assertTrue(imageTokens >= 256)
    }

    @Test
    fun testModelPresetCatalogMetadataIntegrity() {
        val presets = ModelPreset.presets
        assertEquals(10, presets.size)

        // 1. Qwen 3 0.6B (Pocket Turbo)
        val qwen06 = presets.first { it.id == "qwen3-0.6b" }
        assertEquals("mixed INT4", qwen06.quantization)
        assertEquals("0.6B", qwen06.parameterCount)
        assertEquals(2, qwen06.minRamGb)
        assertEquals("2 GB+ RAM", qwen06.ramRequirement)
        assertEquals(497516544L, qwen06.sizeBytes)
        assertEquals(com.example.auralocalai.data.ModelSpeedTier.TURBO, qwen06.speedTier)
        assertTrue(qwen06.isReasoningModel)
        assertFalse(qwen06.defaultThinkingEnabled)
        assertFalse(qwen06.supportsVision)
        assertTrue(qwen06.tags.contains(com.example.auralocalai.data.ModelTag.TURBO))

        // 2. Qwen 2.5 1.5B (Floor Model)
        val qwen15 = presets.first { it.id == "qwen-1.5b" }
        assertEquals("Q8 (8-bit)", qwen15.quantization)
        assertEquals("1.5B", qwen15.parameterCount)
        assertEquals(4, qwen15.minRamGb)
        assertEquals("4 GB+ RAM", qwen15.ramRequirement)
        assertEquals(1597922856L, qwen15.sizeBytes)
        assertEquals(com.example.auralocalai.data.ModelSpeedTier.BALANCED, qwen15.speedTier)
        assertFalse(qwen15.defaultThinkingEnabled)
        assertFalse(qwen15.supportsVision)

        // 3. Qwen 3 1.7B (Dynamic INT4)
        val qwen17 = presets.first { it.id == "qwen3-1.7b" }
        assertEquals("dynamic INT4", qwen17.quantization)
        assertEquals("1.7B", qwen17.parameterCount)
        assertEquals(4, qwen17.minRamGb)
        assertEquals("4 GB+ RAM", qwen17.ramRequirement)
        assertEquals(977184032L, qwen17.sizeBytes)
        assertEquals(com.example.auralocalai.data.ModelSpeedTier.BALANCED, qwen17.speedTier)
        assertTrue(qwen17.isReasoningModel)
        assertFalse(qwen17.defaultThinkingEnabled)
        assertFalse(qwen17.supportsVision)

        // 4. Qwen 2.5 Coder 1.5B
        val coder15 = presets.first { it.id == "qwen2.5-coder-1.5b" }
        assertEquals("INT4", coder15.quantization)
        assertEquals("1.5B", coder15.parameterCount)
        assertEquals(4, coder15.minRamGb)
        assertEquals("4 GB+ RAM", coder15.ramRequirement)
        assertEquals(1117385648L, coder15.sizeBytes)
        assertEquals(com.example.auralocalai.data.ModelSpeedTier.BALANCED, coder15.speedTier)
        assertFalse(coder15.defaultThinkingEnabled)
        assertFalse(coder15.supportsVision)
        assertTrue(coder15.tags.contains(com.example.auralocalai.data.ModelTag.CODING))

        // 5. DeepSeek R1 1.5B
        val deepseek = presets.first { it.id == "deepseek-1.5b" }
        assertEquals("Q8 (8-bit)", deepseek.quantization)
        assertEquals("1.5B", deepseek.parameterCount)
        assertEquals(6, deepseek.minRamGb)
        assertEquals("6 GB+ RAM", deepseek.ramRequirement)
        assertEquals(1833451520L, deepseek.sizeBytes)
        assertTrue(deepseek.isReasoningModel)
        assertTrue(deepseek.defaultThinkingEnabled)
        assertFalse(deepseek.supportsVision)

        // 6. Gemma 4 E2B (Multimodal Vision)
        val gemmaE2b = presets.first { it.id == "gemma4-e2b" }
        assertEquals("mixed 2/4/8-bit", gemmaE2b.quantization)
        assertEquals("2.4B", gemmaE2b.parameterCount)
        assertEquals(6, gemmaE2b.minRamGb)
        assertEquals("6 GB+ RAM", gemmaE2b.ramRequirement)
        assertEquals(2588147712L, gemmaE2b.sizeBytes)
        assertTrue(gemmaE2b.supportsVision)
        assertTrue(gemmaE2b.tags.contains(com.example.auralocalai.data.ModelTag.VISION))

        // 7. Gemma 4 E2B Uncensored MAX
        val gemmaUncensored = presets.first { it.id == "gemma4-e2b-uncensored" }
        assertEquals("INT4", gemmaUncensored.quantization)
        assertEquals("2.3B", gemmaUncensored.parameterCount)
        assertEquals(6, gemmaUncensored.minRamGb)
        assertEquals("6 GB+ RAM", gemmaUncensored.ramRequirement)
        assertEquals(2550041824L, gemmaUncensored.sizeBytes)
        assertFalse(gemmaUncensored.supportsVision)
        assertTrue(gemmaUncensored.tags.contains(com.example.auralocalai.data.ModelTag.UNCENSORED))

        // 8. SuperGemma 4 E4B Abliterated (Upgraded typomonster build)
        val supergemma = presets.first { it.id == "supergemma4-e4b-abliterated" }
        assertEquals("INT4", supergemma.quantization)
        assertEquals("4.0B", supergemma.parameterCount)
        assertEquals(8, supergemma.minRamGb)
        assertEquals("8 GB+ RAM", supergemma.ramRequirement)
        assertEquals(3654467584L, supergemma.sizeBytes)
        assertEquals(com.example.auralocalai.data.ModelSpeedTier.POWER, supergemma.speedTier)
        assertFalse(supergemma.supportsVision)
        assertTrue(supergemma.tags.contains(com.example.auralocalai.data.ModelTag.UNCENSORED))

        // 9. Qwen 3 4B
        val qwen3 = presets.first { it.id == "qwen3-4b" }
        assertEquals("mixed INT4", qwen3.quantization)
        assertEquals("4.0B", qwen3.parameterCount)
        assertEquals(8, qwen3.minRamGb)
        assertEquals("8 GB+ RAM", qwen3.ramRequirement)
        assertEquals(2659053760L, qwen3.sizeBytes)
        assertTrue(qwen3.isReasoningModel)
        assertFalse(qwen3.defaultThinkingEnabled)
        assertFalse(qwen3.supportsVision)

        // 10. Gemma 4 E4B (Flagship Multimodal)
        val gemmaE4b = presets.first { it.id == "gemma4-e4b" }
        assertEquals("mixed 2/4/8-bit", gemmaE4b.quantization)
        assertEquals("4.0B", gemmaE4b.parameterCount)
        assertEquals(8, gemmaE4b.minRamGb)
        assertEquals("8 GB+ RAM", gemmaE4b.ramRequirement)
        assertEquals(3659530240L, gemmaE4b.sizeBytes)
        assertEquals(com.example.auralocalai.data.ModelSpeedTier.POWER, gemmaE4b.speedTier)
        assertTrue(gemmaE4b.supportsVision)
        assertTrue(gemmaE4b.tags.contains(com.example.auralocalai.data.ModelTag.VISION))

        // Context budgets must reflect the real KV cache of the exported bundle
        assertEquals(2048, qwen06.effectiveContextTokens)
        assertEquals(4096, qwen15.effectiveContextTokens)
        assertEquals(4096, qwen17.effectiveContextTokens)
        assertEquals(4096, coder15.effectiveContextTokens)
        assertEquals(4096, deepseek.effectiveContextTokens)
        assertEquals(8192, gemmaE2b.effectiveContextTokens)
        assertEquals(8192, gemmaUncensored.effectiveContextTokens)
        assertEquals(8192, supergemma.effectiveContextTokens)
        assertEquals(2048, qwen3.effectiveContextTokens)
        assertEquals(8192, gemmaE4b.effectiveContextTokens)

        // Catalog integrity assertions across all presets
        val ids = presets.map { it.id }
        assertEquals("All preset IDs must be unique", ids.size, ids.toSet().size)

        val fileNames = presets.map { it.fileName }
        assertEquals("All model file names must be unique", fileNames.size, fileNames.toSet().size)

        for (preset in presets) {
            assertTrue("Preset ${preset.id} must have non-empty name", preset.name.isNotBlank())
            assertTrue("Preset ${preset.id} must have valid HTTPS download URL", preset.downloadUrl.startsWith("https://"))
            assertTrue("Preset ${preset.id} file must end with expected extension", preset.fileName.endsWith(preset.expectedExtension))
            val ramBytes = com.example.auralocalai.data.ModelSafetyValidator.getMinRequiredRamBytes(preset)
            assertTrue("Preset ${preset.id} RAM requirement must be parsed successfully", ramBytes > 0)
            assertTrue("Preset ${preset.id} sizeBytes must be positive", preset.sizeBytes > 0L)
            assertNotNull("Preset ${preset.id} must have pinned expectedSha256 checksum", preset.expectedSha256)
            assertEquals("Preset ${preset.id} SHA-256 must be 64-char hex string", 64, preset.expectedSha256?.length)
        }
    }

@Test
    fun testChatMessageTelemetrySerializationBackwardsCompatibility() {
        // Message without telemetry (representing legacy saved JSON)
        val legacyJson = """[{"content":"Hello","isUser":true,"timestamp":1000,"id":"test-id"}]"""
        val decodedLegacy = json.decodeFromString(ListSerializer(ChatMessage.serializer()), legacyJson)
        assertEquals(1, decodedLegacy.size)
        assertNull(decodedLegacy[0].telemetry)

        // Message with telemetry
        val telemetry = InferenceTelemetry(
            ttftMs = 380L,
            promptTokens = 42,
            decodeSpeedTokPerSec = 22.5,
            decodeTokens = 150,
            wasCancelled = false
        )
        val assistantMsg = ChatMessage(
            content = "Here is the answer",
            isUser = false,
            telemetry = telemetry
        )

        val encoded = json.encodeToString(ListSerializer(ChatMessage.serializer()), listOf(assistantMsg))
        val decoded = json.decodeFromString(ListSerializer(ChatMessage.serializer()), encoded)
        assertEquals(1, decoded.size)
        assertNotNull(decoded[0].telemetry)
        assertEquals(380L, decoded[0].telemetry?.ttftMs)
        assertEquals(42, decoded[0].telemetry?.promptTokens)
        assertEquals(22.5, decoded[0].telemetry?.decodeSpeedTokPerSec ?: 0.0, 0.001)
        assertEquals(150, decoded[0].telemetry?.decodeTokens)
        assertFalse(decoded[0].telemetry?.wasCancelled ?: true)
    }

    @Test
    fun testColdVsWarmMovingAverageLogic() {
        val coldLoadMs = 4500L
        val sample1Warm = 1200L
        // First warm load
        val initialWarm = sample1Warm

        // Second warm load with 900ms: rolling formula (existing * 2 + new) / 3
        val sample2Warm = 900L
        val updatedWarm = (initialWarm * 2 + sample2Warm) / 3

        assertEquals(1100L, updatedWarm)
        assertTrue("Cold load must be substantially larger than warm load", coldLoadMs > updatedWarm)
    }

    @Test
    fun testContextLengthScalingInRamGuard() {
        val model4k = ModelPreset(
            id = "model-4k",
            name = "4k Context Model",
            description = "",
            sizeLabel = "2.0 GB",
            ramRequirement = "6 GB+ RAM",
            downloadUrl = "",
            fileName = "test.litertlm",
            contextLength = "4,096 tokens"
        )
        val model8k = ModelPreset(
            id = "model-8k",
            name = "8k Context Model",
            description = "",
            sizeLabel = "2.0 GB",
            ramRequirement = "6 GB+ RAM",
            downloadUrl = "",
            fileName = "test.litertlm",
            contextLength = "8,192 tokens"
        )

        val ram4k = com.example.auralocalai.data.ModelSafetyValidator.getMinRequiredAvailableRamBytes(model4k, isGpu = false, contextTurns = 6)
        val ram8k = com.example.auralocalai.data.ModelSafetyValidator.getMinRequiredAvailableRamBytes(model8k, isGpu = false, contextTurns = 6)

        // 8k context window must allocate more KV cache memory headroom than 4k context window
        assertTrue("8k model must require more RAM than 4k model at same turns", ram8k > ram4k)
    }

    @Test
    fun testTopLevelIsValidModelFileDefaultSizeFloor() {
        val tempFile = java.io.File.createTempFile("test_model", ".litertlm")
        try {
            // Write valid TFL3 header but under 50MB (e.g. 1 KB)
            val header = byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())
            tempFile.writeBytes(header + ByteArray(1020))
            
            // Default call to top-level shim must enforce 50MB floor
            assertFalse("Top-level isValidModelFile must reject sub-50MB files by default", com.example.auralocalai.data.isValidModelFile(tempFile))
            
            // Passing explicit small minSizeBytes allows testing valid header structure
            assertTrue("Explicit minSizeBytes override passes valid header", com.example.auralocalai.data.isValidModelFile(tempFile, minSizeBytes = 8L))
        } finally {
            tempFile.delete()
        }
    }
}
