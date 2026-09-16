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
        assertEquals(6, presets.size)

        // 1. DeepSeek R1
        val deepseek = presets.first { it.id == "deepseek-1.5b" }
        assertEquals("Q8 (8-bit)", deepseek.quantization)
        assertEquals("1.5B", deepseek.parameterCount)

        // 2. Qwen 2.5 1.5B
        val qwen15 = presets.first { it.id == "qwen-1.5b" }
        assertEquals("Q8 (8-bit)", qwen15.quantization)
        assertEquals("1.5B", qwen15.parameterCount)

        // 3. Qwen 3 4B
        val qwen3 = presets.first { it.id == "qwen3-4b" }
        assertEquals("mixed INT4", qwen3.quantization)
        assertEquals("4.0B", qwen3.parameterCount)

        // 4. Qwen 2.5 Coder 3B
        val coder = presets.first { it.id == "qwen2.5-coder-3b" }
        assertEquals("INT4", coder.quantization)
        assertEquals("3.0B", coder.parameterCount)
        assertEquals("8 GB+ RAM", coder.ramRequirement)

        // 5. Gemma 4 E2B
        val gemmaE2b = presets.first { it.id == "gemma4-e2b" }
        assertEquals("mixed 2/4/8-bit", gemmaE2b.quantization)
        assertEquals("2.4B", gemmaE2b.parameterCount)

        // 6. Gemma 4 E4B
        val gemmaE4b = presets.first { it.id == "gemma4-e4b" }
        assertEquals("mixed 2/4/8-bit", gemmaE4b.quantization)
        assertEquals("4.0B", gemmaE4b.parameterCount)
        assertEquals("12 GB+ RAM", gemmaE4b.ramRequirement)
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
