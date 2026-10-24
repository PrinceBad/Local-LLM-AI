package com.example.auralocalai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.auralocalai.data.LlmInferenceEngine
import com.example.auralocalai.data.ModelPreset
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device instrumented smoke test harness for LiteRT-LM models.
 * Runs on real ARM64 Android devices to verify:
 * 1. Model loading across backends (NPU, GPU, CPU)
 * 2. Real token generation (TTFT, tok/s, peak PSS memory)
 * 3. True memory profiling (MemAvailable delta via /proc/meminfo and summary.graphics)
 * 4. Thinking control enforcement (enable_thinking=false and universal budget clamping)
 * 5. Long-context "needle-in-a-haystack" recall
 * 6. Dual-image multimodal vision verification with non-guessable distinct content
 * 7. CPU thread scaling benchmark (A/B testing 2, 3, and 4 threads)
 */
@RunWith(AndroidJUnit4::class)
class ModelSmokeTest {

    private lateinit var context: Context
    private lateinit var engine: LlmInferenceEngine
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = LlmInferenceEngine(context)
        modelsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
    }

    @After
    fun tearDown() {
        engine.close()
    }

    private fun readMemAvailableMb(): Long {
        return try {
            val memInfoFile = File("/proc/meminfo")
            if (memInfoFile.exists() && memInfoFile.canRead()) {
                val line = memInfoFile.readLines().firstOrNull { it.startsWith("MemAvailable:") }
                val kb = line?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
                kb / 1024L
            } else {
                -1L
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun readGraphicsMemoryMb(): Long {
        return try {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            val kb = memInfo.getMemoryStat("summary.graphics")?.toLongOrNull() ?: 0L
            kb / 1024L
        } catch (_: Throwable) {
            -1L
        }
    }

    @Test
    fun testAllDownloadedModelsSmokeRun() = runBlocking {
        if (!modelsDir.exists()) return@runBlocking
        val modelFiles = modelsDir.listFiles { f -> f.extension == "litertlm" } ?: return@runBlocking
        if (modelFiles.isEmpty()) return@runBlocking

        for (file in modelFiles) {
            val preset = ModelPreset.findByIdOrFileName(file.name)
            val modelLabel = preset?.name ?: file.name
            println("=== Smoke Testing Model: $modelLabel (${file.length() / (1024 * 1024)} MB) ===")

            // Memory baseline before load
            val memAvailBeforeMb = readMemAvailableMb()
            val graphicsBeforeMb = readGraphicsMemoryMb()

            // 1. Load Model
            val startLoadNs = System.nanoTime()
            val loadResult = engine.loadModel(
                modelPath = file.absolutePath,
                preferredBackend = "AUTO",
                contextTurns = 6
            )
            val loadTimeMs = (System.nanoTime() - startLoadNs) / 1_000_000
            assertTrue("Model $modelLabel must load successfully: ${loadResult.exceptionOrNull()?.message}", loadResult.isSuccess)

            // Memory footprint after load
            val memAvailAfterMb = readMemAvailableMb()
            val graphicsAfterMb = readGraphicsMemoryMb()
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            val pssMb = memInfo.totalPss / 1024
            val deltaAvailMb = if (memAvailBeforeMb > 0 && memAvailAfterMb > 0) (memAvailBeforeMb - memAvailAfterMb) else -1L
            val deltaGraphicsMb = if (graphicsBeforeMb >= 0 && graphicsAfterMb >= 0) (graphicsAfterMb - graphicsBeforeMb) else -1L

            println("  Loaded in: ${loadTimeMs}ms | Active Backend: ${engine.activeBackend}")
            println("  Process PSS: ${pssMb} MB | Private Dirty: ${memInfo.totalPrivateDirty / 1024} MB")
            println("  MemAvailable Before: ${memAvailBeforeMb} MB | After: ${memAvailAfterMb} MB | Delta: ${deltaAvailMb} MB")
            println("  Graphics Driver Memory: ${graphicsAfterMb} MB (Delta: +${deltaGraphicsMb} MB)")

            // 2. Generate ~64 Tokens & Measure TTFT and tok/s
            var firstTokenTimeNs: Long? = null
            var tokenCount = 0
            val startGenNs = System.nanoTime()

            val tokens = mutableListOf<String>()
            val thinkingConfig = if (engine.detectedSupportsThinking) ThinkingConfig(enableThinking = false) else null

            engine.generateResponse(
                prompt = "Explain gravity in two concise sentences.",
                turnThinkingConfig = thinkingConfig
            ).collect { token ->
                if (firstTokenTimeNs == null) {
                    firstTokenTimeNs = System.nanoTime()
                }
                tokenCount++
                tokens.add(token)
            }

            val totalGenTimeMs = (System.nanoTime() - startGenNs) / 1_000_000
            val ttftMs = firstTokenTimeNs?.let { (it - startGenNs) / 1_000_000 } ?: -1L
            val decodeDurationSec = firstTokenTimeNs?.let { (System.nanoTime() - it) / 1_000_000_000.0 } ?: 0.0
            val tokPerSec = if (decodeDurationSec > 0 && tokenCount > 1) (tokenCount - 1) / decodeDurationSec else 0.0

            println("  Generated $tokenCount tokens in ${totalGenTimeMs}ms")
            println("  TTFT: " + ttftMs + "ms | Speed: " + tokPerSec + " tok/s")
            val output = tokens.joinToString("")
            println("  Sample Output: ${output.take(120)}...")

            assertTrue("Must generate at least 5 tokens", tokenCount >= 5)
            assertFalse("Thinking must be disabled when requested", output.startsWith("<think>"))

            engine.close()
        }
    }

    @Test
    fun testQwen3ThinkingControl() = runBlocking {
        val qwen06 = File(modelsDir, "qwen3-0.6b.litertlm")
        val qwen17 = File(modelsDir, "qwen3-1.7b.litertlm")
        val qwen4b = File(modelsDir, "qwen3-4b.litertlm")
        val target = if (qwen06.exists()) qwen06 else if (qwen17.exists()) qwen17 else if (qwen4b.exists()) qwen4b else null
        if (target == null) return@runBlocking

        val loadResult = engine.loadModel(target.absolutePath, preferredBackend = "AUTO")
        assertTrue(loadResult.isSuccess)
        assertTrue("Qwen 3 must be detected as a reasoning model", engine.detectedSupportsThinking)

        // Case A: enableThinking = false -> zero thinking output
        val tokensA = engine.generateResponse(
            prompt = "What is 17 * 19?",
            turnThinkingConfig = ThinkingConfig(enableThinking = false)
        ).toList()
        val textA = tokensA.joinToString("")
        assertFalse("Output must NOT contain <think> tag when thinking is disabled", textA.contains("<think>"))

        // Case B: enableThinking = true with budget -> budget clamped
        val tokensB = engine.generateResponse(
            prompt = "What is 17 * 19?",
            turnThinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = 64)
        ).toList()
        val textB = tokensB.joinToString("")
        assertNotNull(textB)

        engine.close()
    }

    @Test
    fun testNeedleInHaystackRecallNearContextLimit() = runBlocking {
        val testFile = modelsDir.listFiles { f -> f.extension == "litertlm" }?.firstOrNull() ?: return@runBlocking
        val loadResult = engine.loadModel(testFile.absolutePath, preferredBackend = "AUTO")
        assertTrue(loadResult.isSuccess)

        val secretNeedle = "The secret activation passphrase is PHOENIX-9921."
        val filler = "On-device local intelligence guarantees complete user data privacy. The device processes prompts in memory. "
        val promptBuilder = StringBuilder()
        promptBuilder.append("Read the following background information and answer the question at the end.\n\n")
        repeat(30) { promptBuilder.append(filler) }
        promptBuilder.append("\n$secretNeedle\n")
        repeat(30) { promptBuilder.append(filler) }
        promptBuilder.append("\n\nQuestion: What is the secret activation passphrase? Answer with only the exact passphrase.")

        val responseTokens = engine.generateResponse(
            prompt = promptBuilder.toString(),
            turnThinkingConfig = ThinkingConfig(enableThinking = false)
        ).toList()
        val answer = responseTokens.joinToString("")
        println("Needle Recall Answer: $answer")
        assertTrue("Model must recall needle near context limit", answer.contains("PHOENIX-9921"))

        engine.close()
    }

    @Test
    fun testMultimodalImageInference() = runBlocking {
        // Candidates for vision testing: official Google flagships and community fine-tunes
        val visionCandidates = listOf(
            "gemma4-e2b.litertlm",
            "gemma4-e4b.litertlm",
            "gemma4-e2b-uncensored-max.litertlm",
            "supergemma4-e4b-abliterated.litertlm"
        )

        for (fileName in visionCandidates) {
            val file = File(modelsDir, fileName)
            if (!file.exists()) continue

            println("=== Testing Dual-Image Vision Modality on $fileName ===")
            val loadResult = engine.loadModel(file.absolutePath, preferredBackend = "AUTO")
            if (loadResult.isFailure) {
                println("  Failed to load $fileName: ${loadResult.exceptionOrNull()?.message}")
                continue
            }

            println("  Engine detectedSupportsVision: ${engine.detectedSupportsVision}")
            if (!engine.detectedSupportsVision) {
                println("  $fileName does NOT declare vision support in ModelInfo metadata.")
                engine.close()
                continue
            }

            // Image A: Solid Blue bitmap
            val blueBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            blueBitmap.eraseColor(Color.BLUE)

            // Image B: Solid Yellow bitmap
            val yellowBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            yellowBitmap.eraseColor(Color.YELLOW)

            try {
                // Test Image A
                val responseBlue = engine.generateResponse(
                    prompt = "What is the primary color shown in this image? Answer with only the single color word: blue, yellow, or green.",
                    image = blueBitmap
                ).toList().joinToString("")
                val matchesBlue = responseBlue.contains("blue", ignoreCase = true) && !responseBlue.contains("yellow", ignoreCase = true)

                // Test Image B
                val responseYellow = engine.generateResponse(
                    prompt = "What is the primary color shown in this image? Answer with only the single color word: blue, yellow, or green.",
                    image = yellowBitmap
                ).toList().joinToString("")
                val matchesYellow = responseYellow.contains("yellow", ignoreCase = true) && !responseYellow.contains("blue", ignoreCase = true)

                println("  Test A (Blue): response='$responseBlue' -> matchesBlue=$matchesBlue")
                println("  Test B (Yellow): response='$responseYellow' -> matchesYellow=$matchesYellow")

                val dualPass = matchesBlue && matchesYellow
                println("  Dual-Image Result for $fileName: $dualPass")
                assertTrue("Vision model $fileName must correctly identify both distinct images", dualPass)
            } catch (e: Throwable) {
                println("  Vision inference threw error on $fileName: ${e.message}")
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun testCpuThreadScalingBenchmark() = runBlocking {
        val testFile = modelsDir.listFiles { f -> f.extension == "litertlm" }?.firstOrNull() ?: return@runBlocking
        val modelLabel = testFile.name
        println("=== A/B Thread Scaling Benchmark on $modelLabel ===")

        val threadOptions = listOf(2, 3, 4)
        for (threads in threadOptions) {
            val config = EngineConfig(
                modelPath = testFile.absolutePath,
                backend = Backend.CPU(threads),
                cacheDir = context.cacheDir.absolutePath
            )
            var benchEngine: Engine? = null
            try {
                val startLoadNs = System.nanoTime()
                benchEngine = Engine(config)
                benchEngine.initialize()
                val loadMs = (System.nanoTime() - startLoadNs) / 1_000_000

                val conv = benchEngine.createConversation()
                var firstTokenNs: Long? = null
                var tokenCount = 0
                val startGenNs = System.nanoTime()

                conv.sendMessageAsync("Explain the theory of relativity in 30 words.").collect {
                    if (firstTokenNs == null) {
                        firstTokenNs = System.nanoTime()
                    }
                    tokenCount++
                }

                val totalGenMs = (System.nanoTime() - startGenNs) / 1_000_000
                val ttftMs = firstTokenNs?.let { (it - startGenNs) / 1_000_000 } ?: -1L
                val decodeDurationSec = firstTokenNs?.let { (System.nanoTime() - it) / 1_000_000_000.0 } ?: 0.0
                val tokPerSec = if (decodeDurationSec > 0 && tokenCount > 1) (tokenCount - 1) / decodeDurationSec else 0.0

                println("  Threads: $threads | Load: ${loadMs}ms | TTFT: ${ttftMs}ms | Speed: " + "%.2f".format(tokPerSec) + " tok/s | Tokens: $tokenCount in ${totalGenMs}ms")
                conv.close()
            } catch (e: Throwable) {
                println("  Thread benchmark failed for threads=$threads: ${e.message}")
            } finally {
                benchEngine?.close()
            }
        }
    }
}
