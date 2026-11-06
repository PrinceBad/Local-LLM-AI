package com.example.auralocalai.data

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BackendType
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.NpuBrand
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.ByteArrayOutputStream

class LlmInferenceEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var pendingCloseJob: kotlinx.coroutines.Job? = null
    private var currentModelPath: String? = null
    /** System instruction bound to the conversation's native system slot. */
    private var systemPrompt: String? = null
    /** Prior turns replayed as native user/model messages when the conversation is created. */
    private var initialHistory: List<Message> = emptyList()
    private var samplerConfig: SamplerConfig? = null
    private var thinkingConfig: ThinkingConfig? = null
    private val inferenceMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val engineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val failedNpuModelPaths = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        // Cache migration: sweep old unversioned or outdated cache entries
        try {
            val prefs = context.getSharedPreferences("npu_backend_cache", Context.MODE_PRIVATE)
            val allKeys = prefs.all.keys
            val outdatedKeys = allKeys.filter { !it.endsWith(":$NPU_CACHE_VERSION") }
            if (outdatedKeys.isNotEmpty()) {
                val editor = prefs.edit()
                for (key in outdatedKeys) {
                    editor.remove(key)
                }
                editor.apply()
            }
            prefs.all.forEach { (key, value) ->
                if (value == true && key.endsWith(":$NPU_CACHE_VERSION")) {
                    failedNpuModelPaths.add(key)
                }
            }
        } catch (_: Throwable) {
            // Ignore cache migration failures
        }
    }

    private fun getCompiledCacheDir(): File {
        return File(context.filesDir, "litert_cache").apply { mkdirs() }
    }

        /**
     * Signals the underlying native conversation to abort active generation.
     */
    fun cancelInference() {
        try {
            conversation?.cancelProcess()
        } catch (_: Throwable) {
            // Ignore
        }
    }

    fun clearNpuFailureCache() {
        failedNpuModelPaths.clear()
        val prefs = context.getSharedPreferences("npu_backend_cache", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    /** Real maximum context window size detected dynamically from ModelInfo. */
    var detectedMaxContextTokens: Int = 0
        private set
    /** Whether the loaded bundle natively supports reasoning/thinking according to ModelInfo. */
    var detectedSupportsThinking: Boolean = false
        private set
    /** Whether the loaded bundle supports multimodal image inputs according to ModelInfo. */
    var detectedSupportsVision: Boolean = false
        private set
    /** The backends explicitly supported by the model bundle (e.g. [GPU], [NPU], [CPU]). */
    var detectedSupportedBackends: Set<BackendType> = emptySet()
        private set
    /** The target NPU brand declared by the model bundle if NPU-constrained. */
    var detectedNpuBrand: NpuBrand = NpuBrand.UNKNOWN
        private set
    /** The target SoC name declared by the model bundle if SoC-specific. */
    var detectedSocName: String = ""
        private set
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
        systemPrompt: String? = null,
        initialHistory: List<Message> = emptyList(),
        samplerConfig: SamplerConfig? = null,
        thinkingConfig: ThinkingConfig? = null,
        onStageUpdate: ((String) -> Unit)? = null
    ): Result<Unit> = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
        try {
            // Close previous session with inference lock (lifecycleMutex already acquired)
            inferenceMutex.withLock {
                closeInternal()
            }
            lastNpuError = null
            this@LlmInferenceEngine.currentModelPath = modelPath
            this@LlmInferenceEngine.systemPrompt = systemPrompt
            this@LlmInferenceEngine.initialHistory = initialHistory
            this@LlmInferenceEngine.samplerConfig = samplerConfig
            this@LlmInferenceEngine.thinkingConfig = thinkingConfig

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                return@withContext Result.failure(Exception("Model file does not exist: $modelPath"))
            }

            val preset = ModelPreset.findByIdOrFileName(modelFile.name)

            // Dynamically inspect model metadata (context window, thinking, modalities, backends)
            try {
                ModelInfo.from(modelPath).use { info ->
                    detectedMaxContextTokens = info.maxContextTokens()
                    detectedSupportsVision = info.inputModalities().vision
                    if (info is ModelInfo.Llm) {
                        detectedSupportsThinking = info.supportsThinking()
                    } else {
                        detectedSupportsThinking = false
                    }
                    detectedSupportedBackends = try { info.supportedBackends(Modality.TEXT).toSet() } catch (_: Throwable) { emptySet() }
                    detectedNpuBrand = try { info.npuBrand(Modality.TEXT) } catch (_: Throwable) { NpuBrand.UNKNOWN }
                    detectedSocName = try { info.socName(Modality.TEXT).orEmpty() } catch (_: Throwable) { "" }
                    android.util.Log.i(
                        "LlmInferenceEngine",
                        "ModelInfo: maxContext=$detectedMaxContextTokens, vision=$detectedSupportsVision, " +
                        "thinking=$detectedSupportsThinking, backends=$detectedSupportedBackends, " +
                        "npuBrand=$detectedNpuBrand, socName=$detectedSocName"
                    )
                }
            } catch (e: Throwable) {
                android.util.Log.w("LlmInferenceEngine", "ModelInfo extraction skipped: ${e.message}")
                detectedSupportsThinking = false
                detectedSupportedBackends = emptySet()
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
            // If preset defines a restriction, use it. For imported models, check declared backends.
            val restriction = preset?.backendRestriction
                ?: if (detectedSupportedBackends.isNotEmpty()) {
                    if (!detectedSupportedBackends.contains(BackendType.CPU)) LlmBackendRestriction.GPU_ONLY else LlmBackendRestriction.ANY
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

            // KV Cache token budgeting: clamp maxNumTokens so LiteRT-LM does not over-allocate contiguous VRAM on mobile GPUs
            val maxTokensByTurns = (contextTurns * 384).coerceIn(1536, 4096)
            val effectiveMaxTokens = minOf(detectedMaxContextTokens.takeIf { it > 0 } ?: 4096, maxTokensByTurns)
            val modelSupportsVision = detectedSupportsVision || (preset?.supportsVision == true)
            val threadCount = detectBigCoreThreadCount()
            // Always route vision encoding graph to CPU (XNNPACK) with big-core parallelism.
            // On mobile devices (e.g. Adreno 610/619/680/685) and models with CPU backend constraints on vision adapters
            // (e.g. Gemma 4 E2B/E4B), GPU vision allocation fails or leaves vision executor null.
            // CPU XNNPACK guarantees 100% stable vision loading while keeping text decoder accelerated on GPU.
            val visionBackendToUse = if (modelSupportsVision) Backend.CPU(threadCount) else null

            // ============================================================
            // STEP 1: Attempt GPU initialization (Vulkan) - Primary acceleration in AUTO mode
            // ============================================================
            // In AUTO mode, always attempt GPU first as requested; fallback to CPU only if GPU fails or times out.
            val attemptGpu = (preferredBackend == "GPU_ONLY" || preferredBackend == "AUTO") &&
                (restriction == LlmBackendRestriction.ANY || restriction == LlmBackendRestriction.GPU_ONLY)
            if (attemptGpu) {
                // Pre-flight GPU RAM guard: accounts for weight staging & Android Vulkan buffer duplication (LiteRT-LM #3507)
                val gpuRamCheck = ModelSafetyValidator.verifyDeviceRamForInference(
                    context = context,
                    preset = preset,
                    isGpu = true,
                    isNpu = false,
                    modelFile = modelFile,
                    contextTurns = contextTurns
                )
                if (gpuRamCheck.isFailure) {
                    val ramError = gpuRamCheck.exceptionOrNull() ?: Exception("Insufficient available RAM for GPU initialization")
                    android.util.Log.w("LlmInferenceEngine", "GPU RAM check failed: ${ramError.message}")
                    if (restriction == LlmBackendRestriction.GPU_ONLY || preferredBackend == "GPU_ONLY") {
                        return@withContext Result.failure(ramError)
                    }
                    gpuError = ramError
                } else {
                    onStageUpdate?.invoke("Compiling GPU shaders\u2026")
                    var tempGpuEngine: Engine? = null
                    try {
                        val config = EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.GPU(),
                            visionBackend = visionBackendToUse,
                            cacheDir = getCompiledCacheDir().absolutePath,
                            maxNumTokens = effectiveMaxTokens,
                            maxNumImages = if (modelSupportsVision) 1 else null,
                            speculativeDecodingConfig = SpeculativeDecodingConfig(SpeculativeDecodingMode.ADAPTIVE)
                        )
                        if (preferredBackend == "AUTO") {
                            val initOk = kotlinx.coroutines.withTimeoutOrNull(75_000L) {
                                val eng = Engine(config)
                                tempGpuEngine = eng
                                eng.initialize()
                                eng
                            }
                            if (initOk == null) {
                                throw RuntimeException("GPU shader compilation timed out after 75s")
                            }
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            engine = initOk
                            conversation = newConversation(initOk)
                        } else {
                            val eng = Engine(config)
                            tempGpuEngine = eng
                            eng.initialize()
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            engine = eng
                            conversation = newConversation(eng)
                        }
                        currentModelPath = modelPath
                        activeBackend = "GPU"
                        loaded = true
                    } catch (e: Throwable) {
                        runCatching { tempGpuEngine?.close() }
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        gpuError = e
                        android.util.Log.w("LlmInferenceEngine", "GPU initialization failed or timed out: ${e.message}")
                        // If CPU fallback is NOT allowed or GPU_ONLY is preferred, fail immediately
                        if (restriction == LlmBackendRestriction.GPU_ONLY || preferredBackend == "GPU_ONLY") {
                            val msg = "GPU failed: ${e.localizedMessage}. Backend restriction ($restriction) or preference ($preferredBackend) prevents fallback."
                            return@withContext Result.failure(Exception(msg, e))
                        }
                    }
                }
            }

            // ============================================================
            // STEP 2: Attempt NPU initialization (Qualcomm Hexagon HTP / Google Tensor TPU)
            // ============================================================
            if (!loaded) {
                val npuCachePrefs = context.getSharedPreferences("npu_backend_cache", android.content.Context.MODE_PRIVATE)
                val cacheKey = "$modelPath:${modelFile.lastModified()}:$NPU_CACHE_VERSION"
                val isKnownNpuFailure = failedNpuModelPaths.contains(cacheKey) || npuCachePrefs.getBoolean(cacheKey, false)

                // Fail-safe: if ModelInfo parsing failed or returned emptySet(), do not assume NPU is supported.
                // Only treat as supporting NPU if BackendType.NPU is declared or preset is NPU_ONLY.
                val modelSupportsNpu = detectedSupportedBackends.contains(BackendType.NPU) ||
                    (detectedSupportedBackends.isEmpty() && preset?.backendRestriction == LlmBackendRestriction.NPU_ONLY)

                val isGoogleTensor = socInfo.vendor == SocVendor.GOOGLE_TENSOR
                val isQualcommQnn = socInfo.isQnnRuntimeAvailable
                // In AUTO mode, only attempt NPU if runtime is verified available (avoid entry-level Qualcomm wasting time)
                val hasDeviceNpuCapability = if (preferredBackend == "AUTO") {
                    isGoogleTensor || isQualcommQnn
                } else {
                    isGoogleTensor || isQualcommQnn || socInfo.isNpuHardwarePresent
                }

                if (preferredBackend == "NPU_ONLY") {
                    if (restriction != LlmBackendRestriction.ANY && restriction != LlmBackendRestriction.NPU_ONLY) {
                        return@withContext Result.failure(Exception(
                            "Backend restriction ($restriction) does not allow NPU execution for ${modelFile.name}."
                        ))
                    }
                    if (!modelSupportsNpu && detectedSupportedBackends.isNotEmpty()) {
                        val supportedStr = detectedSupportedBackends.joinToString { it.name }
                        return@withContext Result.failure(Exception(
                            "This model bundle (${modelFile.name}) does not support NPU execution. " +
                            "Supported backends declared by model: [$supportedStr]. " +
                            "Most standard .litertlm models require GPU acceleration (Vulkan/OpenCL). " +
                            "Please select Auto or GPU backend in Settings."
                        ))
                    }
                    if (!hasDeviceNpuCapability) {
                        return@withContext Result.failure(Exception(
                            "NPU backend was preferred, but NPU runtime is not available on this device (${socInfo.qnnDetails}). " +
                            "Please select Auto or GPU backend in Settings."
                        ))
                    }
                }

                val canAttemptNpu = modelSupportsNpu &&
                    hasDeviceNpuCapability &&
                    (!isKnownNpuFailure || preferredBackend == "NPU_ONLY") &&
                    (preferredBackend == "AUTO" || preferredBackend == "NPU_ONLY") &&
                    (restriction == LlmBackendRestriction.NPU_ONLY || restriction == LlmBackendRestriction.ANY)

                if (canAttemptNpu) {
                    // Pre-flight NPU RAM guard (both Google Tensor TPU and Qualcomm QNN)
                    val npuRamCheck = ModelSafetyValidator.verifyDeviceRamForNpu(
                        context = context,
                        preset = preset,
                        modelFile = modelFile,
                        contextTurns = contextTurns
                    )
                    if (npuRamCheck.isFailure) {
                        val ramError = npuRamCheck.exceptionOrNull() ?: Exception("Insufficient available RAM for NPU initialization")
                        android.util.Log.w("LlmInferenceEngine", "NPU RAM check failed: ${ramError.message}")
                        if (restriction == LlmBackendRestriction.NPU_ONLY || preferredBackend == "NPU_ONLY") {
                            return@withContext Result.failure(ramError)
                        }
                        npuError = ramError
                    } else {
                        if (isGoogleTensor) {
                            onStageUpdate?.invoke("${socInfo.marketingName} TPU detected \u2014 initializing Tensor backend\u2026")
                            var tempTensorEngine: Engine? = null
                            try {
                                val config = EngineConfig(
                                    modelPath = modelPath,
                                    backend = Backend.GOOGLE_TENSOR(),
                                    visionBackend = visionBackendToUse,
                                    cacheDir = getCompiledCacheDir().absolutePath,
                                    maxNumTokens = effectiveMaxTokens,
                                    maxNumImages = if (modelSupportsVision) 1 else null,
                                    speculativeDecodingConfig = SpeculativeDecodingConfig(SpeculativeDecodingMode.ADAPTIVE)
                                )
                                val eng = Engine(config)
                                tempTensorEngine = eng
                                eng.initialize()
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                engine = eng
                                conversation = newConversation(eng)
                                currentModelPath = modelPath
                                activeBackend = "NPU"
                                loaded = true
                            } catch (e: Throwable) {
                                runCatching { tempTensorEngine?.close() }
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                npuError = e
                                lastNpuError = e
                                failedNpuModelPaths.add(cacheKey)
                                npuCachePrefs.edit().putBoolean(cacheKey, true).apply()
                                android.util.Log.w("LlmInferenceEngine", "Google Tensor TPU initialization failed for ${modelFile.name}: ${e.message}")
                                if (restriction == LlmBackendRestriction.NPU_ONLY || preferredBackend == "NPU_ONLY") {
                                    return@withContext Result.failure(Exception(
                                        "Failed to load model on Google Tensor NPU: ${e.localizedMessage}. Backend preference prevents fallback.", e
                                    ))
                                }
                            }
                        } else if (isQualcommQnn) {
                            val htpLabel = if (socInfo.htpVersion != HtpVersion.UNKNOWN) " (${socInfo.htpVersion.label})" else ""
                            onStageUpdate?.invoke("${socInfo.marketingName} NPU detected$htpLabel \u2014 initializing NPU backend\u2026")
                            var tempQnnEngine: Engine? = null
                            try {
                                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                                try {
                                    val adspLibraryPath = "$nativeLibDir:/vendor/lib64/rfsa/adsp:/system/vendor/lib64/rfsa/adsp:/vendor/dsp/cdsp:/system/lib/rfsa/adsp:/system/vendor/lib/rfsa/adsp:/dsp"
                                    android.system.Os.setenv("ADSP_LIBRARY_PATH", adspLibraryPath, true)
                                    android.util.Log.d("LlmInferenceEngine", "Set ADSP_LIBRARY_PATH to: $adspLibraryPath")
                                } catch (envEx: Throwable) {
                                    android.util.Log.e("LlmInferenceEngine", "Failed to set ADSP_LIBRARY_PATH", envEx)
                                }
                                val config = EngineConfig(
                                    modelPath = modelPath,
                                    backend = Backend.NPU(nativeLibraryDir = nativeLibDir),
                                    visionBackend = visionBackendToUse,
                                    cacheDir = getCompiledCacheDir().absolutePath,
                                    maxNumTokens = effectiveMaxTokens,
                                    maxNumImages = if (modelSupportsVision) 1 else null,
                                    speculativeDecodingConfig = SpeculativeDecodingConfig(SpeculativeDecodingMode.ADAPTIVE)
                                )
                                val eng = Engine(config)
                                tempQnnEngine = eng
                                eng.initialize()
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                engine = eng
                                conversation = newConversation(eng)
                                currentModelPath = modelPath
                                activeBackend = "NPU"
                                loaded = true
                            } catch (e: Throwable) {
                                runCatching { tempQnnEngine?.close() }
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                npuError = e
                                lastNpuError = e
                                failedNpuModelPaths.add(cacheKey)
                                npuCachePrefs.edit().putBoolean(cacheKey, true).apply()
                                android.util.Log.w("LlmInferenceEngine", "Qualcomm NPU initialization failed for ${modelFile.name}: ${e.message}. Cached NPU failure; falling back.")
                                if (restriction == LlmBackendRestriction.NPU_ONLY || preferredBackend == "NPU_ONLY") {
                                    return@withContext Result.failure(Exception(
                                        "Failed to load model on NPU: ${e.localizedMessage}. " +
                                        "Backend restriction or preference prevents fallback.", e
                                    ))
                                }
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
                    val stageMsg = if (gpuError != null) "GPU unavailable \u2014 checking device RAM for CPU fallback\u2026" else "Checking device RAM for CPU execution\u2026"
                    onStageUpdate?.invoke(stageMsg)
                    // Check RAM size before committing to CPU execution to prevent native OOM crashes
                    val ramCheck = verifyDeviceRamForCpu(preset, modelFile, contextTurns)
                    if (ramCheck.isFailure) {
                        val ramError = ramCheck.exceptionOrNull() ?: Exception("RAM check failed for CPU execution")
                        return@withContext Result.failure(ramError)
                    }

                    onStageUpdate?.invoke("Initializing CPU backend\u2026")
                    var tempCpuEngine: Engine? = null
                    try {
                        val config = EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.CPU(threadCount),
                            visionBackend = visionBackendToUse,
                            cacheDir = getCompiledCacheDir().absolutePath,
                            maxNumTokens = effectiveMaxTokens,
                            maxNumImages = if (modelSupportsVision) 1 else null,
                            speculativeDecodingConfig = SpeculativeDecodingConfig(SpeculativeDecodingMode.ADAPTIVE)
                        )
                        val eng = Engine(config)
                        tempCpuEngine = eng
                        eng.initialize()
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        engine = eng
                        conversation = newConversation(eng)
                        currentModelPath = modelPath
                        activeBackend = "CPU"
                        loaded = true
                    } catch (fallbackEx: Throwable) {
                        runCatching { tempCpuEngine?.close() }
                        if (fallbackEx is kotlinx.coroutines.CancellationException) throw fallbackEx
                        val combinedMsg = buildString {
                            if (gpuError != null) append("GPU: ${gpuError.localizedMessage}. ")
                            if (npuError != null) append("NPU: ${npuError.localizedMessage}. ")
                            append("CPU: ${fallbackEx.localizedMessage}")
                        }
                        return@withContext Result.failure(Exception(combinedMsg, fallbackEx))
                    }
                } else {
                    val failureReason = buildString {
                        if (gpuError != null) append("GPU initialization failed: ${gpuError.localizedMessage}. ")
                        if (npuError != null) append("NPU initialization failed: ${npuError.localizedMessage}. ")
                        append("Backend restriction ($restriction) or preference ($preferredBackend) does not permit CPU loading.")
                    }
                    return@withContext Result.failure(Exception(failureReason))
                }
            }

            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
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
     * Creates a conversation that uses the model's own chat semantics:
     * the system instruction occupies the real system slot and prior turns are
     * replayed as native user/model messages.
     *
     * Flattening the transcript into a single user turn (the previous
     * behaviour) pushes the prompt out of the model's training distribution,
     * which makes aligned and abliterated small models fall back to their
     * safety priors and refuse.
     */
    private fun newConversation(engineInstance: Engine): Conversation {
        val instruction = systemPrompt?.takeIf { it.isNotBlank() }
        val template = if (isChatMlOrQwenModel(currentModelPath)) QWEN_CHATML_TEMPLATE else null
        val defaultThinking = currentModelPath?.let { ModelPreset.findByIdOrFileName(File(it).name)?.defaultThinkingEnabled } ?: false
        val effectiveThinking = if (detectedSupportsThinking) {
            thinkingConfig ?: ThinkingConfig(enableThinking = defaultThinking, thinkingTokenBudget = -1)
        } else null
        val config = ConversationConfig(
            systemInstruction = instruction?.let { Contents.of(it) },
            initialMessages = initialHistory,
            samplerConfig = samplerConfig,
            thinkingConfig = effectiveThinking,
            chatTemplate = template
        )
        return engineInstance.createConversation(config)
    }

    /**
     * Recreates the conversation so a new system instruction, updated sampler,
     * or a cleared history takes effect. The loaded model and its backend stay intact.
     */
    suspend fun resetConversation(
        systemPrompt: String? = this.systemPrompt,
        initialHistory: List<Message> = this.initialHistory,
        samplerConfig: SamplerConfig? = this.samplerConfig,
        thinkingConfig: ThinkingConfig? = this.thinkingConfig
    ): Boolean = inferenceMutex.withLock {
        pendingCloseJob?.join()
        pendingCloseJob = null
        val activeEngine = engine ?: return@withLock false
        this@LlmInferenceEngine.systemPrompt = systemPrompt
        this@LlmInferenceEngine.initialHistory = initialHistory
        this@LlmInferenceEngine.samplerConfig = samplerConfig
        this@LlmInferenceEngine.thinkingConfig = thinkingConfig
        try {
            conversation?.close()
        } catch (e: Exception) {
            android.util.Log.w("LlmInferenceEngine", "Failed to close previous conversation", e)
        }
        conversation = null
        return@withLock try {
            conversation = newConversation(activeEngine)
            true
        } catch (e: Exception) {
            android.util.Log.e("LlmInferenceEngine", "Failed to reset conversation", e)
            false
        }
    }

    /**
     * Generates a streaming response flow for the given prompt.
     * Optionally accepts a native image bitmap for multimodal vision models.
     */
    fun generateResponse(
        prompt: String,
        image: Bitmap? = null,
        repetitionPenaltyConfig: RepetitionPenaltyConfig? = null,
        turnThinkingConfig: ThinkingConfig? = null
    ): Flow<String> = callbackFlow {
        // Multimodal memory pre-flight guard: ensure headroom for vision tower & image patch embeddings
        if (image != null) {
            val multimodalRamCheck = ModelSafetyValidator.verifyAvailableRamForMultimodal(context)
            if (multimodalRamCheck.isFailure) {
                close(multimodalRamCheck.exceptionOrNull() ?: Exception("Insufficient available memory for vision processing"))
                return@callbackFlow
            }
        }

        try {
            // Build multimodal message if an image input is present and model natively supports vision
            val message = if (image != null && detectedSupportsVision) {
                val stream = ByteArrayOutputStream()
                image.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val byteArray = stream.toByteArray()
                
                val imageContent = Content.ImageBytes(byteArray)
                val textContent = Content.Text(prompt)
                val contents = Contents.of(imageContent, textContent)
                Message.user(contents)
            } else {
                Message.user(prompt)
            }

            val effectiveThinkingConfig = if (detectedSupportsThinking) {
                val defaultThinking = currentModelPath?.let { ModelPreset.findByIdOrFileName(File(it).name)?.defaultThinkingEnabled } ?: false
                val req = turnThinkingConfig ?: this@LlmInferenceEngine.thinkingConfig ?: ThinkingConfig(enableThinking = defaultThinking)
                if (req.enableThinking) {
                    val budgetedTokens = if (req.thinkingTokenBudget > 0) {
                        val maxBudget = if (detectedMaxContextTokens > 0) minOf(2048, detectedMaxContextTokens / 3) else 1024
                        req.thinkingTokenBudget.coerceAtMost(maxBudget)
                    } else {
                        -1 // Unconstrained native reasoning
                    }
                    ThinkingConfig(enableThinking = true, thinkingTokenBudget = budgetedTokens)
                } else {
                    ThinkingConfig(enableThinking = false)
                }
            } else {
                null
            }

            suspend fun runTurn(conv: Conversation) {
                conv.sendMessageAsync(
                    message = message,
                    repetitionPenaltyConfig = repetitionPenaltyConfig,
                    thinkingConfig = effectiveThinkingConfig
                )
                    .flowOn(Dispatchers.Default)
                    .collect { chunk ->
                        val text = chunk.toString()
                        if (text.isNotEmpty()) {
                            trySend(text)
                        }
                    }
            }

            inferenceMutex.withLock {
                val activeEngine = engine
                if (activeEngine == null) {
                    close(Exception("Model not loaded yet. Please load a model first."))
                    return@withLock
                }

                // Auto-initialize conversation if not already created
                var currentConversation = conversation
                if (currentConversation == null) {
                    try {
                        currentConversation = newConversation(activeEngine)
                        conversation = currentConversation
                    } catch (initConvEx: Throwable) {
                        close(Exception("Failed to initialize conversation: ${initConvEx.message}", initConvEx))
                        return@withLock
                    }
                }

                var turnCompletedNormally = false
                try {
                    runTurn(currentConversation)
                    turnCompletedNormally = true
                } catch (convEx: Throwable) {
                    val msg = convEx.message.orEmpty()
                    val isDead = convEx is IllegalStateException ||
                        msg.contains("not alive", ignoreCase = true) ||
                        msg.contains("is closed", ignoreCase = true)
                    val isVisionFail = image != null && (msg.contains("Vision executor", ignoreCase = true) || msg.contains("TryLoadingVisionExecutor", ignoreCase = true))
                    if (isVisionFail) {
                        android.util.Log.w("LlmInferenceEngine", "Vision execution failed on native runtime ($msg); auto-falling back to text-only turn.")
                        val textMessage = Message.user(prompt)
                        currentConversation.sendMessageAsync(
                            message = textMessage,
                            repetitionPenaltyConfig = repetitionPenaltyConfig,
                            thinkingConfig = effectiveThinkingConfig
                        )
                            .flowOn(Dispatchers.Default)
                            .collect { chunk ->
                                val text = chunk.toString()
                                if (text.isNotEmpty()) {
                                    trySend(text)
                                }
                            }
                        turnCompletedNormally = true
                    } else if (isDead && engine != null) {
                        android.util.Log.w("LlmInferenceEngine", "Native conversation not alive; auto-recovering with fresh conversation context.")
                        try { currentConversation.close() } catch (_: Throwable) {}
                        val freshConv = newConversation(engine!!)
                        conversation = freshConv
                        runTurn(freshConv)
                        turnCompletedNormally = true
                    } else {
                        throw convEx
                    }
                } finally {
                    // If turn was aborted or cancelled mid-stream, safely reset native conversation.
                    // On mid-stream cancellation, calling conv.close() synchronously while the native worker
                    // is still running causes status 0x20b ("Failed to update task state") fatal abort.
                    // First tell the native worker to abort via cancelProcess(), detach conversation,
                    // and defer conv.close() by a grace delay so worker can cleanly finalize.
                    if (!turnCompletedNormally) {
                        try { currentConversation.cancelProcess() } catch (_: Throwable) {}
                        if (conversation === currentConversation) {
                            conversation = null
                        }
                        val convToClose = currentConversation
                        pendingCloseJob = engineScope.launch {
                            try {
                                kotlinx.coroutines.delay(100)
                                convToClose.close()
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
            channel.close()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            // Flow cancelled or closed
        }
    }.flowOn(Dispatchers.IO)

    private fun closeInternal() {
        pendingCloseJob?.cancel()
        pendingCloseJob = null

        val convToClose = conversation
        conversation = null
        try {
            convToClose?.close()
        } catch (_: Throwable) {
            // Ignore
        }

        val engineToClose = engine
        engine = null
        try {
            engineToClose?.close()
        } catch (_: Throwable) {
            // Ignore
        } finally {
            currentModelPath = null
            activeBackend = "None"
            detectedMaxContextTokens = 0
            detectedSupportsThinking = false
            detectedSupportsVision = false
            detectedSupportedBackends = emptySet()
            detectedNpuBrand = NpuBrand.UNKNOWN
            detectedSocName = ""
        }
    }

    /**
     * Asynchronously frees native engine and conversation resources with serialized mutex locking.
     */
    suspend fun closeAsync() {
        lifecycleMutex.withLock {
            inferenceMutex.withLock {
                closeInternal()
            }
        }
    }

    /**
     * Synchronously frees resources by closing the active LLM inference instance.
     * Uses non-blocking tryLock or background IO dispatch to prevent Main-thread ANRs.
     */
    fun close() {
        if (engine == null && conversation == null) return
        if (lifecycleMutex.tryLock()) {
            try {
                if (inferenceMutex.tryLock()) {
                    try {
                        closeInternal()
                    } finally {
                        inferenceMutex.unlock()
                    }
                } else {
                    engineScope.launch {
                        inferenceMutex.withLock {
                            closeInternal()
                        }
                    }
                }
            } finally {
                lifecycleMutex.unlock()
            }
        } else {
            engineScope.launch {
                closeAsync()
            }
        }
    }

    /**
     * Determines the optimal thread count targeting performance / big CPU cores.
     * Google LiteRT benchmarks use 4 threads. Spawning threads on efficiency cores
     * introduces synchronization bottlenecks that slow down XNNPACK token decode.
     */
    private fun detectBigCoreThreadCount(): Int {
        return try {
            val available = Runtime.getRuntime().availableProcessors()
            val cpuDir = File("/sys/devices/system/cpu")
            val maxFreqs = mutableListOf<Long>()
            for (i in 0 until available) {
                val freqFile = File(cpuDir, "cpu$i/cpufreq/cpuinfo_max_freq")
                if (freqFile.exists() && freqFile.canRead()) {
                    freqFile.readText().trim().toLongOrNull()?.let { maxFreqs.add(it) }
                }
            }
            if (maxFreqs.isNotEmpty()) {
                val medianFreq = maxFreqs.sorted()[maxFreqs.size / 2]
                val bigCores = maxFreqs.count { it >= medianFreq }
                bigCores.coerceIn(2, 6)
            } else {
                if (available >= 8) minOf(6, available - 2) else maxOf(2, available / 2)
            }
        } catch (_: Throwable) {
            val available = Runtime.getRuntime().availableProcessors()
            if (available >= 8) minOf(6, available - 2) else 2
        }
    }

    companion object {
        const val NPU_CACHE_VERSION = "v3.0_litertlm"

        /**
         * Qwen / ChatML Jinja template that handles sequence-based message content
         * without triggering the LiteRT-LM Minja parser error:
         * "tried to use + operator on unsupported types string and sequence"
         */
        const val QWEN_CHATML_TEMPLATE =
            "{%- for message in messages -%}" +
            "{{- '<|im_start|>' + message.role + '\n' -}}" +
            "{%- if message.content is string -%}" +
            "{{- message.content -}}" +
            "{%- else -%}" +
            "{%- for item in message.content -%}" +
            "{%- if item.text is defined -%}" +
            "{{- item.text -}}" +
            "{%- elif item is string -%}" +
            "{{- item -}}" +
            "{%- endif -%}" +
            "{%- endfor -%}" +
            "{%- endif -%}" +
            "{{- '<|im_end|>\n' -}}" +
            "{%- endfor -%}" +
            "{%- if add_generation_prompt -%}" +
            "{{- '<|im_start|>assistant\n' -}}" +
            "{%- endif -%}"

        fun isChatMlOrQwenModel(path: String?): Boolean {
            if (path == null) return false
            val file = File(path)
            val lowerName = file.name.lowercase()
            if (lowerName.contains("qwen") || lowerName.contains("smollm") || lowerName.contains("deepseek") || lowerName.contains("chatml") || lowerName.contains("qwq")) {
                return true
            }
            val preset = ModelPreset.findByIdOrFileName(file.name)
            if (preset != null) {
                val pId = preset.id.lowercase()
                val pFile = preset.fileName.lowercase()
                if (pId.contains("qwen") || pId.contains("smollm") || pId.contains("deepseek") || pFile.contains("qwen") || pFile.contains("smollm") || pFile.contains("deepseek")) {
                    return true
                }
            }
            if (file.exists() && (path.endsWith(".litertlm", ignoreCase = true) || path.endsWith(".zip", ignoreCase = true))) {
                try {
                    java.util.zip.ZipFile(file).use { zip ->
                        val entry = zip.getEntry("tokenizer_config.json")
                        if (entry != null) {
                            val text = zip.getInputStream(entry).bufferedReader().readText()
                            if (text.contains("im_start") || text.contains("qwen", ignoreCase = true)) {
                                return true
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                    // Best-effort check
                }
            }
            return false
        }
    }
}
