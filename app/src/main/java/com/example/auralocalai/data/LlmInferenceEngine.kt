package com.example.auralocalai.data

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setPreferredBackend(LlmInference.Backend.GPU) // Leverage mobile GPU (Vulkan/Metal)
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

