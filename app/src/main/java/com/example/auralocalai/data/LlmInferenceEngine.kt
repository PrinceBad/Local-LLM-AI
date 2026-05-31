package com.example.auralocalai.data

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.example.auralocalai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import android.os.Build
import java.io.File

class LlmInferenceEngine(private val context: Context) {

    private var llmInference: LlmInference? = null
    private var currentModelPath: String? = null

    val isModelLoaded: Boolean
        get() = llmInference != null

    /**
     * Loads the model asynchronously from the specified absolute file path.
     * Shuts down any previously loaded model.
     */
    suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Close previous session
            close()

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                return@withContext Result.failure(Exception("Model file does not exist at: $modelPath"))
            }

            // Check for minimum size to prevent loading incomplete or corrupted files
            val isPreset = modelFile.name.contains("qwen-1.5b") 
                    || modelFile.name.contains("deepseek-r1") 
                    || modelFile.name.contains("gemma-2b") 
                    || modelFile.name.contains("phi-2")
            val minSize = if (isPreset) 1_000_000_000L else 10_000_000L
            if (modelFile.length() < minSize) {
                return@withContext Result.failure(Exception("Model file is incomplete or corrupted (Size is only ${modelFile.length() / (1024 * 1024)} MB). Please delete and re-download the model."))
            }

            val isEmulator = Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("unknown")
                    || Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.HARDWARE.contains("goldfish")
                    || Build.HARDWARE.contains("ranchu")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || Build.PRODUCT.contains("sdk_google")
                    || Build.PRODUCT.contains("google_sdk")
                    || Build.PRODUCT.contains("sdk")
                    || Build.PRODUCT.contains("sdk_x86")
                    || Build.PRODUCT.contains("vbox86p")
                    || Build.PRODUCT.contains("emulator")
                    || Build.PRODUCT.contains("simulator")
            val isX86 = Build.SUPPORTED_ABIS.any { it.contains("x86") }

            if (isEmulator || isX86) {
                return@withContext Result.failure(Exception("Offline LLM Inference is not supported on x86/x86_64 emulators due to native ARM64 Neon vector instruction translation limits (Berberis SIGSEGV). Please use a physical arm64-v8a device or an ARM64 virtual device."))
            }

            val backend = LlmInference.Backend.GPU
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setPreferredBackend(backend)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            currentModelPath = modelPath
            Result.success(Unit)
        } catch (e: Exception) {
            // If GPU creation fails, try falling back to CPU
            try {
                val fallbackOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                llmInference = LlmInference.createFromOptions(context, fallbackOptions)
                currentModelPath = modelPath
                Result.success(Unit)
            } catch (fallbackEx: Exception) {
                Result.failure(Exception("Failed to load model on GPU/CPU: ${fallbackEx.localizedMessage}", fallbackEx))
            }
        }
    }

    /**
     * Generates a streaming response flow for the given prompt.
     */
    fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        val inference = llmInference
        if (inference == null) {
            close(Exception("Model not loaded yet. Please load a model first."))
            return@callbackFlow
        }

        try {
            inference.generateResponseAsync(prompt) { partialResult, done ->
                trySend(partialResult)
                if (done) {
                    channel.close()
                }
            }
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            // MediaPipe's async inference does not support interactive cancellation mid-generation,
            // but closing the flow stops rendering token emissions on the UI.
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Frees resources by closing the active LLM inference instance.
     */
    fun close() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            // Ignore clean up errors
        } finally {
            llmInference = null
            currentModelPath = null
        }
    }
}

