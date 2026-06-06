package com.example.auralocalai.data

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream

class LlmInferenceEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String? = null

    val isModelLoaded: Boolean
        get() = engine != null

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

            // Enforce validation to prevent native C++ crashes on malformed files
            if (!isValidModelFile(modelFile)) {
                return@withContext Result.failure(IllegalArgumentException(
                    "The file '${modelFile.name}' is not a valid model package. " +
                    "It must be a LiteRT Flatbuffer (TFL3) or a valid MediaPipe Task ZIP archive. " +
                    "Please delete and re-download the model."
                ))
            }

            // Find matching preset to check backend restrictions
            val preset = ModelPreset.presets.firstOrNull { it.fileName.equals(modelFile.name, ignoreCase = true) }
            val restriction = preset?.backendRestriction ?: LlmBackendRestriction.ANY

            // Check for minimum size to prevent loading incomplete or corrupted files.
            val minSize = 50_000_000L // 50 MB universal minimum
            if (modelFile.length() < minSize) {
                return@withContext Result.failure(Exception(
                    "Model file is incomplete or corrupted " +
                    "(${modelFile.length() / (1024 * 1024)} MB is below the 50 MB minimum). " +
                    "Please delete and re-download the model."
                ))
            }

            // Emulator & Architecture Guard
            if (isEmulatorOrX86()) {
                return@withContext Result.failure(Exception("Offline LLM Inference is not supported on x86/x86_64 emulators due to native vector instruction translation limits. Please use a physical arm64-v8a device or an ARM64 virtual device."))
            }

            var loaded = false
            var lastError: Throwable? = null

            // 1. Attempt GPU initialization if allowed by restriction
            val attemptGpu = (restriction == LlmBackendRestriction.GPU_ONLY || restriction == LlmBackendRestriction.ANY)
            if (attemptGpu) {
                try {
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.GPU()
                    )
                    val newEngine = Engine(config)
                    newEngine.initialize()
                    engine = newEngine
                    conversation = newEngine.createConversation()
                    currentModelPath = modelPath
                    loaded = true
                } catch (e: Throwable) {
                    lastError = e
                    // If CPU fallback is NOT allowed, fail immediately
                    if (restriction != LlmBackendRestriction.ANY) {
                        return@withContext Result.failure(Exception(
                            "Failed to load model on GPU: ${e.localizedMessage}. " +
                            "Backend restriction (${restriction}) prevents fallback to CPU.", e
                        ))
                    }
                }
            }

            // 2. Attempt CPU fallback if not loaded and allowed by restriction
            if (!loaded) {
                val attemptCpu = (restriction == LlmBackendRestriction.CPU_ONLY || restriction == LlmBackendRestriction.ANY)
                if (attemptCpu) {
                    // Check RAM size before committing to CPU execution to prevent native OOM crashes
                    val ramCheck = verifyDeviceRamForCpu(preset)
                    if (ramCheck.isFailure) {
                        val ramError = ramCheck.exceptionOrNull() ?: Exception("RAM check failed for CPU execution")
                        return@withContext Result.failure(ramError)
                    }

                    try {
                        val config = EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.CPU()
                        )
                        val newEngine = Engine(config)
                        newEngine.initialize()
                        engine = newEngine
                        conversation = newEngine.createConversation()
                        currentModelPath = modelPath
                        loaded = true
                    } catch (fallbackEx: Throwable) {
                        val combinedMsg = if (lastError != null) {
                            "Failed to load model on GPU (${lastError.localizedMessage}) and CPU: ${fallbackEx.localizedMessage}"
                        } else {
                            "Failed to load model on CPU: ${fallbackEx.localizedMessage}"
                        }
                        return@withContext Result.failure(Exception(combinedMsg, fallbackEx))
                    }
                } else {
                    return@withContext Result.failure(Exception(
                        "Model loading failed. Backend restriction (${restriction}) does not permit CPU loading."
                    ))
                }
            }

            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Checks if the device has at least 8 GB of total RAM.
     * Throws an explicit exception if RAM is insufficient, avoiding a silent native OS OOM kill.
     */
    private fun verifyDeviceRamForCpu(preset: ModelPreset?): Result<Unit> {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager == null) {
                return Result.success(Unit)
            }

            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalRamBytes = memoryInfo.totalMem
            
            var requiredGb = 6.0
            if (preset != null) {
                if (preset.ramRequirement.contains("8 GB")) requiredGb = 8.0
                else if (preset.ramRequirement.contains("6 GB")) requiredGb = 6.0
                else if (preset.ramRequirement.contains("4 GB")) requiredGb = 4.0
            }
            
            val thresholdGb = requiredGb - 1.5
            val minRequiredRamBytes = (thresholdGb * 1024 * 1024 * 1024).toLong()

            if (totalRamBytes < minRequiredRamBytes) {
                val actualRamGb = String.format("%.2f", totalRamBytes.toDouble() / (1024 * 1024 * 1024))
                Result.failure(Exception(
                    "Inference Aborted: CPU-based model execution requires at least 8.00 GB of device RAM to prevent system crashes. " +
                    "This device has only $actualRamGb GB of RAM. Loading stopped to prevent native Out-Of-Memory (OOM) failures."
                ))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Failed to evaluate device memory configuration: ${e.localizedMessage}", e))
        }
    }



    /**
     * Helper to identify if running on an unsupported platform (e.g. x86 virtual device)
     */
    private fun isEmulatorOrX86(): Boolean {
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
        return isEmulator || isX86
    }

    /**
     * Generates a streaming response flow for the given prompt.
     * Optionally accepts a native image bitmap for multimodal vision models.
     */
    fun generateResponse(prompt: String, image: Bitmap? = null): Flow<String> = callbackFlow {
        val currentConversation = conversation
        if (currentConversation == null) {
            close(Exception("Model not loaded yet. Please load a model first."))
            return@callbackFlow
        }

        try {
            // Build multimodal message if an image input is present
            val message = if (image != null) {
                val stream = ByteArrayOutputStream()
                image.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val byteArray = stream.toByteArray()
                
                val imageContent = Content.ImageBytes(byteArray)
                val textContent = Content.Text(prompt)
                val contents = Contents.of(imageContent, textContent)
                Message.user(contents)
            } else {
                Message.user(prompt)
            }

            currentConversation.sendMessageAsync(message)
                .flowOn(Dispatchers.Default)
                .collect { chunk ->
                    trySend(chunk.toString())
                }
            channel.close()
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            // Cleanup on close
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Frees resources by closing the active LLM inference instance.
     */
    fun close() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            conversation = null
        }

        try {
            engine?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            engine = null
            currentModelPath = null
        }
    }
}
