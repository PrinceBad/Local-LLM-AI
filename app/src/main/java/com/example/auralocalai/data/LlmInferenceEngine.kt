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
     * Dynamically detects SoC model and Qualcomm Hexagon NPU capability via SocDetector.
     */
    val socInfo: SocInfo by lazy {
        SocDetector.detectSoc(context)
    }

    /**
     * Loads the model asynchronously from the specified absolute file path.
     * Shuts down any previously loaded model.
     * Uses a 3-tier loading waterfall: NPU -> GPU -> CPU (configurable via preferredBackend)
     */
    suspend fun loadModel(
        modelPath: String,
        preferredBackend: String = "AUTO",
        contextTurns: Int = 6,
        onStageUpdate: ((String) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
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
                    LlmBackendRestriction.GPU_ONLY
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
            val canAttemptNpu = socInfo.isQnnRuntimeAvailable &&
                (preferredBackend == "AUTO" || preferredBackend == "NPU_ONLY") &&
                (restriction == LlmBackendRestriction.NPU_ONLY || restriction == LlmBackendRestriction.ANY)

            if (preferredBackend == "NPU_ONLY" && !socInfo.isQnnRuntimeAvailable) {
                return@withContext Result.failure(Exception(
                    "NPU backend was preferred, but Qualcomm QNN runtime is not available (${socInfo.qnnDetails}). Please select Auto or GPU backend."
                ))
            }
            
            if (canAttemptNpu) {
                val htpLabel = if (socInfo.htpVersion != HtpVersion.UNKNOWN) " (${socInfo.htpVersion.label})" else ""
                onStageUpdate?.invoke("${socInfo.marketingName} NPU detected$htpLabel \u2014 initializing NPU backend\u2026")
                try {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    try {
                        val adspLibraryPath = "$nativeLibDir:/system/lib/rfsa/adsp:/system/vendor/lib/rfsa/adsp:/vendor/dsp/cdsp:/dsp"
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
                    // NPU failed — fall through to GPU unless NPU_ONLY is enforced
                    if (restriction == LlmBackendRestriction.NPU_ONLY || preferredBackend == "NPU_ONLY") {
                        return@withContext Result.failure(Exception(
                            "Failed to load model on NPU: ${e.localizedMessage}. " +
                            "Backend restriction or preference prevents fallback.", e
                        ))
                    }
                }
            }

            // ============================================================
            // STEP 2: Attempt GPU initialization (Vulkan)
            // ============================================================
            if (!loaded) {
                val attemptGpu = (preferredBackend == "AUTO" || preferredBackend == "GPU_ONLY") &&
                    (restriction == LlmBackendRestriction.ANY || restriction == LlmBackendRestriction.GPU_ONLY)
                if (attemptGpu) {
                    // Pre-flight GPU RAM guard: accounts for weight staging & Android Vulkan buffer duplication (LiteRT-LM #3507)
                    val gpuRamCheck = ModelSafetyValidator.verifyDeviceRamForInference(
                        context = context,
                        preset = preset,
                        isGpu = true,
                        modelFile = modelFile,
                        contextTurns = contextTurns
                    )
                    if (gpuRamCheck.isFailure) {
                        val ramError = gpuRamCheck.exceptionOrNull() ?: Exception("Insufficient available RAM for GPU initialization")
                        android.util.Log.w("LlmInferenceEngine", "GPU RAM check failed: ${ramError.message}")
                        if (restriction != LlmBackendRestriction.ANY || preferredBackend == "GPU_ONLY") {
                            return@withContext Result.failure(ramError)
                        }
                        gpuError = ramError
                    } else {
                        val gpuStage = if (npuError != null) "NPU unavailable — compiling GPU shaders…" else "Compiling GPU shaders…"
                        onStageUpdate?.invoke(gpuStage)
                        try {
                            val config = EngineConfig(
                                modelPath = modelPath,
                                backend = Backend.GPU(),
                                cacheDir = context.cacheDir.absolutePath
                            )
                            val newEngine = Engine(config)
                            if (preferredBackend == "AUTO") {
                                val initOk = kotlinx.coroutines.withTimeoutOrNull(40_000L) {
                                    newEngine.initialize()
                                    true
                                }
                                if (initOk == null) {
                                    throw RuntimeException("GPU shader compilation timed out after 40s")
                                }
                            } else {
                                newEngine.initialize()
                            }
                            engine = newEngine
                            conversation = newEngine.createConversation()
                            currentModelPath = modelPath
                            activeBackend = "GPU"
                            loaded = true
                        } catch (e: Throwable) {
                            gpuError = e
                            android.util.Log.w("LlmInferenceEngine", "GPU initialization failed or timed out: ${e.message}")
                            // If CPU fallback is NOT allowed or GPU_ONLY is preferred, fail immediately
                            if (restriction != LlmBackendRestriction.ANY || preferredBackend == "GPU_ONLY") {
                                val msg = buildString {
                                    if (npuError != null) append("NPU failed: ${npuError.localizedMessage}. ")
                                    append("GPU failed: ${e.localizedMessage}. ")
                                    append("Backend restriction ($restriction) or preference ($preferredBackend) prevents fallback to CPU.")
                                }
                                return@withContext Result.failure(Exception(msg, e))
                            }
                        }
                }
            }
            }

            // ============================================================
            // STEP 3: Attempt CPU fallback
            // ============================================================
            if (!loaded) {
                val attemptCpu = (preferredBackend == "AUTO" || preferredBackend == "CPU_ONLY") &&
                    (restriction == LlmBackendRestriction.CPU_ONLY || restriction == LlmBackendRestriction.ANY)
                if (attemptCpu) {
                    onStageUpdate?.invoke("GPU unavailable \u2014 checking device RAM for CPU fallback\u2026")
                    // Check RAM size before committing to CPU execution to prevent native OOM crashes
                    val ramCheck = verifyDeviceRamForCpu(preset, modelFile, contextTurns)
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
                        "Model loading failed. Backend restriction ($restriction) or preference ($preferredBackend) does not permit CPU loading."
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
    private fun verifyDeviceRamForCpu(preset: ModelPreset?, modelFile: File? = null, contextTurns: Int = 6): Result<Unit> {
        return ModelSafetyValidator.verifyDeviceRamForInference(context, preset, isGpu = false, modelFile = modelFile, contextTurns = contextTurns)
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

        // Multimodal memory pre-flight guard: ensure headroom for vision tower & image patch embeddings
        if (image != null) {
            val multimodalRamCheck = ModelSafetyValidator.verifyAvailableRamForMultimodal(context)
            if (multimodalRamCheck.isFailure) {
                close(multimodalRamCheck.exceptionOrNull() ?: Exception("Insufficient available memory for vision processing"))
                return@callbackFlow
            }
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