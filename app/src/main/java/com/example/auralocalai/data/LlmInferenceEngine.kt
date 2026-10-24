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
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.ByteArrayOutputStream

class LlmInferenceEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String? = null
    /** System instruction bound to the conversation's native system slot. */
    private var systemPrompt: String? = null
    /** Prior turns replayed as native user/model messages when the conversation is created. */
    private var initialHistory: List<Message> = emptyList()
    private var samplerConfig: SamplerConfig? = null
    private var thinkingConfig: ThinkingConfig? = null
    private val inferenceMutex = Mutex()
    private val failedNpuModelPaths = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Real maximum context window size detected dynamically from ModelInfo. */
    var detectedMaxContextTokens: Int = 0
        private set
    /** Whether the loaded bundle natively supports reasoning/thinking according to ModelInfo. */
    var detectedSupportsThinking: Boolean = false
        private set
    /** Whether the loaded bundle supports multimodal image inputs according to ModelInfo. */
    var detectedSupportsVision: Boolean = false
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Close previous session
            close()
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

            // Dynamically inspect model metadata (context window, thinking, modalities)
            try {
                ModelInfo.from(modelPath).use { info ->
                    detectedMaxContextTokens = info.maxContextTokens()
                    detectedSupportsVision = info.inputModalities().vision
                    val presetReasoning = (preset?.isReasoningModel == true) || (preset?.tags?.contains(ModelTag.REASONING) == true)
                    if (info is ModelInfo.Llm) {
                        detectedSupportsThinking = info.supportsThinking() || presetReasoning
                    } else {
                        detectedSupportsThinking = presetReasoning
                    }
                    android.util.Log.i("LlmInferenceEngine", "ModelInfo: maxContext=$detectedMaxContextTokens, vision=$detectedSupportsVision, thinking=$detectedSupportsThinking")
                }
            } catch (e: Throwable) {
                android.util.Log.w("LlmInferenceEngine", "ModelInfo extraction skipped: ${e.message}")
                detectedSupportsThinking = preset?.isReasoningModel ?: false
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
            val npuCachePrefs = context.getSharedPreferences("npu_backend_cache", android.content.Context.MODE_PRIVATE)
            val isKnownNpuFailure = failedNpuModelPaths.contains(modelPath) || npuCachePrefs.getBoolean(modelPath, false)
            if (isKnownNpuFailure && preferredBackend == "AUTO") {
                android.util.Log.i("LlmInferenceEngine", "Skipping NPU for ${File(modelPath).name}: cached as incompatible (lacks QNN HTP subgraphs)")
            }

            val canAttemptNpu = socInfo.isQnnRuntimeAvailable &&
                (!isKnownNpuFailure || preferredBackend == "NPU_ONLY") &&
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
                    var tempEngine: Engine? = null
                    try {
                        tempEngine = Engine(config)
                        tempEngine.initialize()
                        engine = tempEngine
                        conversation = newConversation(tempEngine)
                        currentModelPath = modelPath
                        activeBackend = "NPU"
                        loaded = true
                    } catch (initEx: Throwable) {
                        // Ensure no native QNN / Hexagon context or DMA-BUF mappings leak before GPU attempt
                        try {
                            tempEngine?.close()
                        } catch (closeEx: Throwable) {
                            android.util.Log.w("LlmInferenceEngine", "Error closing failed NPU engine: ${closeEx.message}")
                        }
                        throw initEx
                    }
                } catch (e: Throwable) {
                    npuError = e
                    lastNpuError = e
                    failedNpuModelPaths.add(modelPath)
                    npuCachePrefs.edit().putBoolean(modelPath, true).apply()
                    android.util.Log.w("LlmInferenceEngine", "NPU initialization failed for ${File(modelPath).name} (Community .litertlm bundles typically lack Qualcomm QNN HTP subgraphs): ${e.message}. Cached NPU failure; falling back to GPU.")
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
                val isLowTierGpu = SocDetector.shouldDefaultToCpu(context)
                val attemptGpu = (preferredBackend == "GPU_ONLY" || (preferredBackend == "AUTO" && !isLowTierGpu)) &&
                    (restriction == LlmBackendRestriction.ANY || restriction == LlmBackendRestriction.GPU_ONLY)
                if (preferredBackend == "AUTO" && isLowTierGpu) {
                    android.util.Log.i("LlmInferenceEngine", "Entry-level GPU detected (e.g. Snapdragon 685 / Adreno 610). In AUTO mode, bypassing 75s GPU shader compilation to use high-performance CPU (XNNPACK) directly.")
                }
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
                                val initOk = kotlinx.coroutines.withTimeoutOrNull(75_000L) {
                                    newEngine.initialize()
                                    true
                                }
                                if (initOk == null) {
                                    throw RuntimeException("GPU shader compilation timed out after 75s")
                                }
                            } else {
                                newEngine.initialize()
                            }
                            engine = newEngine
                            conversation = newConversation(newEngine)
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
                        val threadCount = detectBigCoreThreadCount()
                        val config = EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.CPU(threadCount),
                            cacheDir = context.cacheDir.absolutePath
                        )
                        val newEngine = Engine(config)
                        newEngine.initialize()
                        engine = newEngine
                        conversation = newConversation(newEngine)
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
        val effectiveThinking = if (detectedSupportsThinking) (thinkingConfig ?: ThinkingConfig(enableThinking = defaultThinking)) else null
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
    fun resetConversation(
        systemPrompt: String? = this.systemPrompt,
        initialHistory: List<Message> = this.initialHistory,
        samplerConfig: SamplerConfig? = this.samplerConfig,
        thinkingConfig: ThinkingConfig? = this.thinkingConfig
    ): Boolean = runBlocking {
        inferenceMutex.withLock {
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

        val activeEngine = engine
        if (activeEngine == null) {
            close(Exception("Model not loaded yet. Please load a model first."))
            return@callbackFlow
        }

        // Auto-initialize conversation if not already created
        var currentConversation = conversation
        if (currentConversation == null) {
            try {
                currentConversation = newConversation(activeEngine)
                conversation = currentConversation
            } catch (initConvEx: Throwable) {
                close(Exception("Failed to initialize conversation: ${initConvEx.message}", initConvEx))
                return@callbackFlow
            }
        }

        try {
            // Build multimodal message if an image input is present
            val message = if (image != null) {
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
                // If thinking is enabled, clamp budget to min(1024, context / 3) for all thinking-capable models
                if (req.enableThinking) {
                    val maxBudget = if (detectedMaxContextTokens > 0) {
                        minOf(1024, detectedMaxContextTokens / 3)
                    } else {
                        512
                    }
                    val budgetedTokens = if (req.thinkingTokenBudget in 1..maxBudget) req.thinkingTokenBudget else maxBudget
                    ThinkingConfig(enableThinking = true, thinkingTokenBudget = budgetedTokens)
                } else {
                    req
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

            try {
                runTurn(currentConversation)
            } catch (convEx: Throwable) {
                val msg = convEx.message.orEmpty()
                val isDead = convEx is IllegalStateException ||
                    msg.contains("not alive", ignoreCase = true) ||
                    msg.contains("is closed", ignoreCase = true)
                if (isDead && engine != null) {
                    android.util.Log.w("LlmInferenceEngine", "Native conversation not alive; auto-recovering with fresh conversation context.")
                    try { currentConversation.close() } catch (_: Throwable) {}
                    val freshConv = newConversation(engine!!)
                    conversation = freshConv
                    runTurn(freshConv)
                } else {
                    throw convEx
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

    /**
     * Frees resources by closing the active LLM inference instance.
     */
    fun close() = runBlocking {
        inferenceMutex.withLock {
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
                bigCores.coerceIn(2, 4)
            } else {
                if (available >= 8) 4 else 2
            }
        } catch (_: Throwable) {
            if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2
        }
    }

    companion object {
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
            if (lowerName.contains("qwen") || lowerName.contains("deepseek") || lowerName.contains("chatml") || lowerName.contains("qwq")) {
                return true
            }
            val preset = ModelPreset.findByIdOrFileName(file.name)
            if (preset != null) {
                val pId = preset.id.lowercase()
                val pFile = preset.fileName.lowercase()
                if (pId.contains("qwen") || pId.contains("deepseek") || pFile.contains("qwen") || pFile.contains("deepseek")) {
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
