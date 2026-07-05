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
    /** The backend that successfully loaded the current model ("NPU", "GPU", "CPU", or "None"). */
    var activeBackend: String = "None"
        private set
    var lastNpuError: Throwable? = null
        private set

    val isModelLoaded: Boolean
        get() = engine != null

    /**
     * Detects whether the device has a Qualcomm Snapdragon SoC with NPU (Hexagon HTP) support.
     * Uses Build.SOC_MODEL (API 31+) for precise detection, with Build.HARDWARE as fallback.
     */
    private val isNpuCapableDevice: Boolean by lazy {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.uppercase() else ""
        val hardware = Build.HARDWARE.uppercase()
        val board = Build.BOARD.uppercase()
        
        val combined = "$socModel|$hardware|$board"
        
        // 1. Check for Qualcomm Snapdragon model numbers (e.g. SM8650, SM8635, SM7675)
        combined.contains("QCOM") || combined.contains("QUALCOMM") || combined.contains("SNAPDRAGON") || combined.contains("SM8") ||
        combined.contains("SM7") ||
        combined.contains("SM6") ||
        // 2. Platform/SoC codenames
        combined.contains("KONA") ||      // Snapdragon 865 / 870
        combined.contains("LAHAINA") ||   // Snapdragon 888
        combined.contains("TARO") ||      // Snapdragon 8 Gen 1
        combined.contains("CAPE") ||      // Snapdragon 8+ Gen 1
        combined.contains("KALAMA") ||    // Snapdragon 8 Gen 2
        combined.contains("PINEAPPLE") || // Snapdragon 8 Gen 3
        combined.contains("CLIFFS") ||    // Snapdragon 8s Gen 3 (SM8635)
        combined.contains("PALAWAN") ||   // Snapdragon 8s Gen 3
        combined.contains("SUN")          // Snapdragon 8 Elite / Gen 4
    }

    /**
     * Loads the model asynchronously from the specified absolute file path.
     * Shuts down any previously loaded model.
     * Uses a 3-tier loading waterfall: NPU -> GPU -> CPU
     */
    suspend fun loadModel(modelPath: String, onStageUpdate: ((String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Close previous session
            close()
            lastNpuError = null

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                return@withContext Result.failure(Exception("Model file does not exist at: $modelPath"))
            }

            onStageUpdate?.invoke("Validating model file\u2026")
            // Enforce validation to prevent native C++ crashes on malformed files
            if (!isValidModelFile(modelFile)) {
                return@withContext Result.failure(IllegalArgumentException(
                    "The file '${modelFile.name}' is not a valid model package. " +
                    "It must be a LiteRT Flatbuffer (TFL3) or a valid MediaPipe Task ZIP archive. " +
                    "Please delete and re-download the model."
                ))
            }

            // Find matching preset to check backend restrictions.
            // All .litertlm bundles have section_backend_constraint: gpu baked in,
            // so default to GPU_ONLY for unrecognized .litertlm files to prevent
            // a wasteful CPU fallback that always fails with INVALID_ARGUMENT.
            val preset = ModelPreset.presets.firstOrNull { it.fileName.equals(modelFile.name, ignoreCase = true) }
            val restriction = preset?.backendRestriction
                ?: if (modelPath.endsWith(".litertlm", ignoreCase = true)) {
                    LlmBackendRestriction.ANY
                } else {
                    LlmBackendRestriction.ANY
                }

            // Check for minimum size to prevent loading incomplete or corrupted files.
            val minSize = 50_000_000L // 50 MB realistic minimum
            if (modelFile.length() < minSize) {
                return@withContext Result.failure(Exception(
                    "Model file is incomplete or corrupted " +
                    "(${modelFile.length() / (1024 * 1024)} MB is below the 50 MB minimum). " +
                    "Please delete and re-download the model."
                ))
            }

            // Emulator & Architecture Guard
            if (isEmulatorOrX86) {
                return@withContext Result.failure(Exception("Offline LLM Inference is not supported on x86/x86_64 emulators due to native vector instruction translation limits. Please use a physical arm64-v8a device or an ARM64 virtual device."))
            }

            var loaded = false
            var npuError: Throwable? = null
            var gpuError: Throwable? = null

            // ============================================================
            // STEP 1: Attempt NPU initialization (Qualcomm Hexagon HTP)
            // ============================================================
            val attemptNpu = isNpuCapableDevice &&
                (restriction == LlmBackendRestriction.NPU_ONLY ||
                 restriction == LlmBackendRestriction.ANY ||
                 restriction == LlmBackendRestriction.ANY)
            if (attemptNpu) {
                onStageUpdate?.invoke("Snapdragon NPU detected \u2014 initializing NPU backend\u2026")
                try {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    try {
                        val adspLibraryPath = "$nativeLibDir;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp"
                        android.system.Os.setenv("ADSP_LIBRARY_PATH", adspLibraryPath, true)
                        android.util.Log.d("LlmInferenceEngine", "Set ADSP_LIBRARY_PATH to: $adspLibraryPath")
                    } catch (envEx: Throwable) {
                        android.util.Log.e("LlmInferenceEngine", "Failed to set ADSP_LIBRARY_PATH", envEx)
                    }
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.NPU(nativeLibraryDir = nativeLibDir),
                        cacheDir = context.cacheDir.absolutePath
                    )
                    val newEngine = Engine(config)
                    newEngine.initialize()
                    engine = newEngine
                    conversation = newEngine.createConversation()
                    currentModelPath = modelPath
                    activeBackend = "NPU"
                    loaded = true
                } catch (e: Throwable) {
                    npuError = e
                    lastNpuError = e
                    android.util.Log.e("LlmInferenceEngine", "Failed to load model on NPU", e)
                    // NPU failed â€” fall through to GPU
                    // If restriction is NPU_ONLY, fail immediately
                    if (restriction == LlmBackendRestriction.NPU_ONLY) {
                        return@withContext Result.failure(Exception(
                            "Failed to load model on NPU: ${e.localizedMessage}. " +
                            "Backend restriction (NPU_ONLY) prevents fallback.", e
                        ))
                    }
                }
            }

            // ============================================================
            // STEP 2: Attempt GPU initialization (Vulkan)
            // ============================================================
            if (!loaded) {
                val attemptGpu = (restriction == LlmBackendRestriction.ANY || restriction == LlmBackendRestriction.ANY)
                if (attemptGpu) {
                    val gpuStage = if (npuError != null) "NPU unavailable \u2014 initializing GPU backend\u2026" else "Initializing GPU backend\u2026"
                    onStageUpdate?.invoke(gpuStage)
                    try {
                        val config = EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.GPU(),
                            cacheDir = context.cacheDir.absolutePath
                        )
                        val newEngine = Engine(config)
                        newEngine.initialize()
                        engine = newEngine
                        conversation = newEngine.createConversation()
                        currentModelPath = modelPath
                        activeBackend = "GPU"
                        loaded = true
                    } catch (e: Throwable) {
                        gpuError = e
                        // If CPU fallback is NOT allowed, fail immediately
                        if (restriction != LlmBackendRestriction.ANY) {
                            val msg = buildString {
                                if (npuError != null) append("NPU failed: ${npuError.localizedMessage}. ")
                                append("GPU failed: ${e.localizedMessage}. ")
                                append("Backend restriction ($restriction) prevents fallback to CPU.")
                            }
                            return@withContext Result.failure(Exception(msg, e))
                        }
                    }
                }
            }

            // ============================================================
            // STEP 3: Attempt CPU fallback
            // ============================================================
            if (!loaded) {
                val attemptCpu = (restriction == LlmBackendRestriction.CPU_ONLY || restriction == LlmBackendRestriction.ANY)
                if (attemptCpu) {
                    onStageUpdate?.invoke("GPU unavailable \u2014 checking device RAM for CPU fallback\u2026")
                    // Check RAM size before committing to CPU execution to prevent native OOM crashes
                    val ramCheck = verifyDeviceRamForCpu(preset)
                    if (ramCheck.isFailure) {
                        val ramError = ramCheck.exceptionOrNull() ?: Exception("RAM check failed for CPU execution")
                        return@withContext Result.failure(ramError)
                    }

                    onStageUpdate?.invoke("Initializing CPU backend\u2026")
                    try {
                        val threadCount = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2
                        val config = EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.CPU(threadCount),
                            cacheDir = context.cacheDir.absolutePath
                        )
                        val newEngine = Engine(config)
                        newEngine.initialize()
                        engine = newEngine
                        conversation = newEngine.createConversation()
                        currentModelPath = modelPath
                        activeBackend = "CPU"
                        loaded = true
                    } catch (fallbackEx: Throwable) {
                        val combinedMsg = buildString {
                            if (npuError != null) append("NPU: ${npuError.localizedMessage}. ")
                            if (gpuError != null) append("GPU: ${gpuError.localizedMessage}. ")
                            append("CPU: ${fallbackEx.localizedMessage}")
                        }
                        return@withContext Result.failure(Exception(combinedMsg, fallbackEx))
                    }
                } else {
                    return@withContext Result.failure(Exception(
                        "Model loading failed. Backend restriction ($restriction) does not permit CPU loading."
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
                android.util.Log.w("LlmInferenceEngine", "Low RAM Warning: Device has only $actualRamGb GB RAM but model recommends ${String.format("%.1f", requiredGb)} GB. Proceeding with CPU fallback anyway.")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Failed to evaluate device memory configuration: ${e.localizedMessage}", e))
        }
    }



    /**
     * Helper to identify if running on an unsupported platform (e.g. x86 virtual device)
     */
    private val isEmulatorOrX86: Boolean by lazy {
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
        isEmulator || isX86
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
            activeBackend = "None"
        }
    }
}