package com.example.auralocalai.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.auralocalai.data.DownloadState
import com.example.auralocalai.data.LlmBackendRestriction
import com.example.auralocalai.data.LlmInferenceEngine
import com.example.auralocalai.data.ModelDownloader
import com.example.auralocalai.data.ModelDownloadService
import com.example.auralocalai.data.ModelPreset
import com.example.auralocalai.data.ChatRepository
import com.example.auralocalai.data.ModelSafetyValidator
import com.example.auralocalai.data.SearchContextBuilder
import com.example.auralocalai.data.SearchIntentDetector
import com.example.auralocalai.data.SearchIntentResult
import com.example.auralocalai.data.SearchOutcome
import com.example.auralocalai.data.ChatSession
import com.example.auralocalai.data.SearchSource
import com.example.auralocalai.data.WebSearchClient
import com.example.auralocalai.data.WebSearchMode
import com.example.auralocalai.security.SecretStorage
import com.example.auralocalai.security.TokenStorageCoordinator
import com.example.auralocalai.security.TokenStorage
import com.example.auralocalai.data.ServiceDownloadState
import com.example.auralocalai.data.SocInfo
import com.example.auralocalai.data.isValidModelFile
import com.example.auralocalai.theme.ThemeMode
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Immutable
@Serializable
data class InferenceTelemetry(
    val ttftMs: Long,
    val promptTokens: Int,
    val decodeSpeedTokPerSec: Double,
    val decodeTokens: Int,
    val wasCancelled: Boolean = false
)

@Immutable
@Serializable
data class ModelLoadBenchmark(
    val coldLoadMs: Long? = null,
    val warmLoadMs: Long? = null,
    val sampleCount: Int = 0
)

@Immutable
@Serializable
data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null,
    val ocrText: String? = null,
    val videoUri: String? = null,
    val fileUri: String? = null,
    val fileName: String? = null,
    val fileType: String? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
    val telemetry: InferenceTelemetry? = null,
    val modelId: String? = null,
    val searchSources: List<SearchSource>? = null,
    val searchQuery: String? = null
)

const val DEFAULT_SYSTEM_PROMPT = "You are Local LLM/AI, a helpful, intelligent offline AI running locally on this mobile device. Keep your responses concise and precise."

sealed interface ModelState {
    data object Unloaded : ModelState
    data object Loading : ModelState
    data class Loaded(val modelName: String) : ModelState
    data class Error(val message: String) : ModelState
}

@Immutable
data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val modelState: ModelState = ModelState.Unloaded,
    val downloadState: DownloadState = DownloadState.Idle,
    val currentDownloadingModelId: String? = null,
    val loadingModelId: String? = null,
    val activeModelId: String? = null,
    val activeBackend: String = "None",
    val lastNpuError: String? = null,
    val localModels: List<String> = emptyList(),
    val selectedImageUri: String? = null,
    val selectedVideoUri: String? = null,
    val selectedFileUri: String? = null,
    val selectedFileName: String? = null,
    val selectedFileType: String? = null,
    val extractedOcrText: String? = null,
    val isAttachmentProcessing: Boolean = false,
    val attachmentError: String? = null,
    val hfToken: String = "",
    val isKeystoreDegraded: Boolean = false,
    val contextWindowSize: Int = 6,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val loadingStage: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val preferredBackend: String = "AUTO",
    val socInfo: SocInfo? = null,
    val importProgress: Float? = null,
    val modelLoadBenchmarks: Map<String, ModelLoadBenchmark> = emptyMap(),
    val webSearchMode: WebSearchMode = WebSearchMode.OFF,
    val webSearchProviderId: String = "free",
    val webSearchMaxResults: Int = 5,
    val searchApiKeyConfigured: Boolean = false,
    val isSearching: Boolean = false,
    val activeSearchQuery: String? = null,
    val searchDisclosureAcknowledged: Boolean = false,
    val chatSearchOverride: WebSearchMode? = null,
    val webSearchError: String? = null,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repetitionPenalty: Float = 1.1f,
    val enableThinking: Boolean = true,
    val thinkingTokenBudget: Int = -1,
    val detectedMaxContextTokens: Int = 0,
    val detectedSupportsThinking: Boolean = false,
    val detectedSupportsVision: Boolean = false,
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String? = null
)

class LlmViewModel(application: Application) : AndroidViewModel(application) {

    private val chatRepository = ChatRepository(application.filesDir)
    private val tokenStorage: TokenStorage = TokenStorageCoordinator.create(application.applicationContext)
    private val storageDir: File = File(
        application.getExternalFilesDir(null) ?: application.filesDir,
        "models"
    ).apply { mkdirs() }
    private val downloader = ModelDownloader()
    private val inferenceEngine = LlmInferenceEngine(application.applicationContext)
    private val webSearchClient = WebSearchClient()
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Isolated theme stream to prevent entire root Activity and Nav tree
     * from recomposing on every LLM token or download progress tick.
     */
    val themeMode: StateFlow<ThemeMode> = _uiState
        .map { it.themeMode }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    private var downloadJob: Job? = null
    private var loadModelJob: Job? = null
    private var inferenceJob: Job? = null

    private fun saveMessages(messages: List<ChatMessage>) {
        val activeId = _uiState.value.currentSessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.saveSessionMessages(activeId, messages)
            val updatedSessions = chatRepository.loadSessions()
            _uiState.update { it.copy(sessions = updatedSessions) }
        }
    }

    private fun loadBenchmarksFromPrefs(): Map<String, ModelLoadBenchmark> {
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val result = mutableMapOf<String, ModelLoadBenchmark>()
        for (preset in ModelPreset.presets) {
            val cold = prefs.getLong("model_load_cold_ms_${preset.id}", -1L).takeIf { it > 0 }
            val warm = prefs.getLong("model_load_warm_ms_${preset.id}", -1L).takeIf { it > 0 }
            val count = prefs.getInt("model_load_samples_${preset.id}", 0)
            if (cold != null || warm != null) {
                result[preset.id] = ModelLoadBenchmark(coldLoadMs = cold, warmLoadMs = warm, sampleCount = count)
            }
        }
        return result
    }

    private fun recordModelLoadBenchmark(modelId: String, elapsedMs: Long) {
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val existingCold = prefs.getLong("model_load_cold_ms_${modelId}", -1L)
        val existingWarm = prefs.getLong("model_load_warm_ms_${modelId}", -1L)
        val existingCount = prefs.getInt("model_load_samples_${modelId}", 0)

        val editor = prefs.edit()
        val newCold: Long?
        val newWarm: Long?
        val newCount: Int

        if (existingCold <= 0L) {
            newCold = elapsedMs
            newWarm = existingWarm.takeIf { it > 0 }
            newCount = 1
            editor.putLong("model_load_cold_ms_${modelId}", elapsedMs)
            editor.putInt("model_load_samples_${modelId}", 1)
        } else {
            newCold = existingCold
            newWarm = if (existingWarm <= 0L) {
                elapsedMs
            } else {
                (existingWarm * 2 + elapsedMs) / 3
            }
            newCount = existingCount + 1
            editor.putLong("model_load_warm_ms_${modelId}", newWarm)
            editor.putInt("model_load_samples_${modelId}", newCount)
        }
        editor.apply()

        _uiState.update { state ->
            val updated = state.modelLoadBenchmarks.toMutableMap()
            updated[modelId] = ModelLoadBenchmark(
                coldLoadMs = newCold,
                warmLoadMs = newWarm,
                sampleCount = newCount
            )
            state.copy(modelLoadBenchmarks = updated)
        }
    }

    fun estimatePromptTokens(prompt: String, hasImage: Boolean = false): Int {
        val textTokens = (prompt.length / 3.8).toInt().coerceAtLeast(1)
        val imageTokens = if (hasImage) 256 else 0
        return textTokens + imageTokens
    }


    private fun migrateExistingModels() {
        val targetDir = storageDir
        val oldDirs = listOfNotNull(
            getApplication<Application>().getExternalFilesDir(null),
            getApplication<Application>().filesDir,
            getApplication<Application>().cacheDir
        )

        for (oldDir in oldDirs) {
            if (oldDir.exists() && oldDir.canonicalPath != targetDir.canonicalPath) {
                val files = oldDir.listFiles() ?: continue
                for (file in files) {
                    if (!file.isFile) continue
                    val name = file.name.lowercase()

                    // Purge orphaned temporary downloads from old directory
                    if (name.endsWith(".tmp") || name.endsWith(".part") || name.endsWith(".migrating") || file.length() == 0L) {
                        file.delete()
                        continue
                    }

                    // Migrate recognized model artifacts
                    if (name.endsWith(".task") || name.endsWith(".bin") || name.endsWith(".litertlm")) {
                        val destFile = File(targetDir, file.name)
                        try {
                            if (!destFile.exists()) {
                                val moved = file.renameTo(destFile)
                                if (!moved) {
                                    val tempDest = File(targetDir, "${file.name}.migrating")
                                    file.copyTo(tempDest, overwrite = true)
                                    if (tempDest.renameTo(destFile)) {
                                        file.delete()
                                    } else {
                                        tempDest.delete()
                                    }
                                }
                            } else {
                                file.delete()
                            }
                        } catch (e: Exception) {
                            Log.w("LlmViewModel", "Could not migrate ${file.name}: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    init {
        // Initialize preferences on main thread (fast synchronous read)
        val prefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val initialHfToken = prefs.getString("hf_token", "") ?: ""
        val initialContextWindow = prefs.getInt("context_window_size", 6)
        val initialSystemPrompt = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        val initialPreferredBackend = prefs.getString("preferred_backend", "AUTO") ?: "AUTO"
        val initialSearchModeStr = prefs.getString("web_search_mode", "OFF") ?: "OFF"
        val initialSearchMode = try { WebSearchMode.valueOf(initialSearchModeStr) } catch (_: Throwable) { WebSearchMode.OFF }
        val initialSearchProvider = prefs.getString("web_search_provider", "free") ?: "free"
        val initialMaxResults = prefs.getInt("web_search_max_results", 5)
        val initialDisclosure = prefs.getBoolean("web_search_disclosure_acknowledged", false)
        val initialTemperature = prefs.getFloat("sampler_temperature", 0.7f)
        val initialTopP = prefs.getFloat("sampler_top_p", 0.9f)
        val initialTopK = prefs.getInt("sampler_top_k", 40)
        val initialRepetitionPenalty = prefs.getFloat("sampler_repetition_penalty", 1.1f)
        val initialEnableThinking = prefs.getBoolean("sampler_enable_thinking", true)
        val initialThinkingBudget = prefs.getInt("sampler_thinking_budget", -1)

        _uiState.update { 
            it.copy(
                hfToken = initialHfToken,
                contextWindowSize = initialContextWindow,
                systemPrompt = initialSystemPrompt,
                preferredBackend = initialPreferredBackend,
                socInfo = inferenceEngine.socInfo,
                webSearchMode = initialSearchMode,
                webSearchProviderId = initialSearchProvider,
                webSearchMaxResults = initialMaxResults,
                searchDisclosureAcknowledged = initialDisclosure,
                temperature = initialTemperature,
                topP = initialTopP,
                topK = initialTopK,
                repetitionPenalty = initialRepetitionPenalty,
                enableThinking = initialEnableThinking,
                thinkingTokenBudget = initialThinkingBudget
            ) 
        }

        // Listen for foreground service download updates
        viewModelScope.launch {
            ModelDownloadService.downloadState.collect { state ->
                when (state) {
                    is ServiceDownloadState.Idle -> {
                        _uiState.update { it.copy(downloadState = DownloadState.Idle) }
                    }
                    is ServiceDownloadState.Progress -> {
                        _uiState.update {
                            it.copy(
                                currentDownloadingModelId = state.modelId,
                                downloadState = DownloadState.Progress(
                                    bytesDownloaded = state.bytesDownloaded,
                                    totalBytes = state.totalBytes,
                                    percentage = state.percentage,
                                    speedBytesPerSec = state.speedBytesPerSec,
                                    etaSeconds = state.etaSeconds
                                )
                            )
                        }
                    }
                    is ServiceDownloadState.Success -> {
                        _uiState.update {
                            it.copy(
                                currentDownloadingModelId = null,
                                downloadState = DownloadState.Idle
                            )
                        }
                        refreshDownloadedModels()
                        loadModel(state.fileName, state.modelId)
                    }
                    is ServiceDownloadState.Error -> {
                        _uiState.update {
                            it.copy(
                                currentDownloadingModelId = state.modelId,
                                downloadState = DownloadState.Error(state.message)
                            )
                        }
                    }
                }
            }
        }

        // Asynchronously load chat sessions & history, migrate models, sweep corrupted artifacts, refresh local models, and auto-load
        viewModelScope.launch(Dispatchers.IO) {
            val sessionsList = chatRepository.loadSessions()
            val activeSession = if (sessionsList.isNotEmpty()) {
                sessionsList.first()
            } else {
                chatRepository.createSession(title = "New Chat")
            }
            val history = chatRepository.loadSessionMessages(activeSession.id)
            val allSessions = chatRepository.loadSessions()
            val token = tokenStorage.getToken()
            val degraded = !tokenStorage.isHardwareEncrypted
            val benchmarks = loadBenchmarksFromPrefs()
            val searchKey = tokenStorage.getSecret(SecretStorage.KEY_SEARCH_API_KEY)
            _uiState.update {
                it.copy(
                    sessions = allSessions,
                    currentSessionId = activeSession.id,
                    messages = history,
                    hfToken = token,
                    isKeystoreDegraded = degraded,
                    modelLoadBenchmarks = benchmarks,
                    searchApiKeyConfigured = searchKey.isNotBlank()
                )
            }

            // 1. Migrate any models from old storage directories safely
            migrateExistingModels()

            // 2. Sweep incomplete or corrupted artifacts asynchronously on Dispatchers.IO (prevents cold-start ANRs)
            ModelSafetyValidator.sweepCorruptedOrIncompleteArtifacts(storageDir)

            // 3. Refresh downloaded model list
            refreshDownloadedModels()

            // 4. Auto-load the first available downloaded model (skip on emulators/x86 to prevent error popups on startup)
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
                    || Build.SUPPORTED_ABIS.any { it.contains("x86") }

            if (!isEmulator) {
                val firstDownloaded = _uiState.value.localModels.firstOrNull()
                if (firstDownloaded != null) {
                    val matchingPreset = ModelPreset.presets.firstOrNull { it.fileName == firstDownloaded }
                    val modelId = matchingPreset?.id ?: firstDownloaded
                    loadModel(firstDownloaded, modelId)
                }
            }
        }
    }

    fun refreshDownloadedModels() {
        val files = storageDir.listFiles() ?: emptyArray()

        // Strictly recognize genuine LiteRT-LM model bundles (.litertlm and .task)
        // Reject binary cache files (_cache.bin, mldrift) and temporary files
        val validModelExtensions = listOf(".litertlm", ".task")
        val modelFiles = files.filter { file ->
            file.isFile &&
            validModelExtensions.any { file.name.endsWith(it, ignoreCase = true) } &&
            !file.name.contains("cache", ignoreCase = true) &&
            !file.name.contains("mldrift", ignoreCase = true) &&
            !file.name.endsWith(".tmp", ignoreCase = true) &&
            !file.name.startsWith(".")
        }

        // Clean up orphaned shader cache files whose parent model file is no longer present
        files.filter { file ->
            file.isFile && (file.name.contains("mldrift", ignoreCase = true) || file.name.contains("_cache.bin", ignoreCase = true))
        }.forEach { cacheFile ->
            val hasParentModel = modelFiles.any { model -> cacheFile.name.startsWith(model.name) }
            if (!hasParentModel) {
                try { cacheFile.delete() } catch (_: Throwable) {}
            }
        }

        val localFiles = modelFiles.map { it.name }
        _uiState.update { it.copy(localModels = localFiles) }
    }

    fun downloadModel(preset: ModelPreset) {
        downloadModelFromUrl(preset.downloadUrl, preset.fileName, preset.id, preset.expectedSha256)
    }

    fun downloadModelFromUrl(url: String, fileName: String, modelId: String = "custom", expectedSha256: String? = null) {
        downloadJob?.cancel()
        _uiState.update { 
            it.copy(
                currentDownloadingModelId = modelId,
                downloadState = DownloadState.Idle
            )
        }

        val context = getApplication<Application>().applicationContext
        val hfToken = tokenStorage.getToken()
        val resolvedSha256 = expectedSha256?.takeIf { it.isNotBlank() }
            ?: ModelPreset.findByIdOrFileName(modelId)?.expectedSha256

        var serviceStarted = false
        try {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                putExtra("url", url)
                putExtra("fileName", fileName)
                putExtra("modelId", modelId)
                putExtra("hfToken", hfToken)
                if (!resolvedSha256.isNullOrBlank()) putExtra("expectedSha256", resolvedSha256)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            serviceStarted = true
        } catch (e: Exception) {
            Log.w("LlmViewModel", "Could not start ModelDownloadService (e.g. background execution limits): ${e.message}. Using in-app fallback download.")
        }

        if (!serviceStarted) {
            downloadJob = viewModelScope.launch(Dispatchers.IO) {
                val tempFile = File(storageDir, "$fileName.tmp")
                val destFile = File(storageDir, fileName)

                downloader.downloadModel(url, tempFile, hfToken, resolvedSha256).collect { state ->
                    when (state) {
                        is DownloadState.Idle -> {
                            _uiState.update { it.copy(downloadState = DownloadState.Idle) }
                        }
                        is DownloadState.Progress -> {
                            _uiState.update {
                                it.copy(
                                    currentDownloadingModelId = modelId,
                                    downloadState = state
                                )
                            }
                        }
                        is DownloadState.Success -> {
                            val renameSuccess = ModelSafetyValidator.moveFileSafely(tempFile, destFile)
                            if (renameSuccess) {
                                _uiState.update {
                                    it.copy(
                                        currentDownloadingModelId = null,
                                        downloadState = DownloadState.Idle
                                    )
                                }
                                refreshDownloadedModels()
                                loadModel(fileName, modelId)
                            } else {
                                if (tempFile.exists()) tempFile.delete()
                                _uiState.update {
                                    it.copy(
                                        currentDownloadingModelId = modelId,
                                        downloadState = DownloadState.Error("Failed to finalize downloaded model file.")
                                    )
                                }
                            }
                        }
                        is DownloadState.Error -> {
                            _uiState.update {
                                it.copy(
                                    currentDownloadingModelId = modelId,
                                    downloadState = state
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun deleteModel(fileName: String) {
        viewModelScope.launch {
            try {
                val file = File(storageDir, fileName)
                if (file.exists()) {
                    file.delete()
                }
                val tempFile = File(storageDir, "$fileName.tmp")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                // Also clean up any associated GPU / NPU shader cache files
                val cachePrefix = fileName.substringBeforeLast(".")
                storageDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith(cachePrefix) && (f.name.contains("cache", ignoreCase = true) || f.name.contains("mldrift", ignoreCase = true))) {
                        try { f.delete() } catch (_: Throwable) {}
                    }
                }
                val matchingPreset = ModelPreset.presets.firstOrNull { it.fileName == fileName }
                val presetId = matchingPreset?.id ?: fileName
                if (uiState.value.activeModelId == presetId) {
                    inferenceEngine.close()
                    _uiState.update { 
                        it.copy(
                            modelState = ModelState.Unloaded,
                            activeModelId = null
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
            refreshDownloadedModels()
        }
    }

    fun saveHfToken(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            tokenStorage.saveToken(token)
            _uiState.update { it.copy(hfToken = token) }
        }
    }

    fun clearHfToken() {
        viewModelScope.launch(Dispatchers.IO) {
            tokenStorage.clearToken()
            _uiState.update { it.copy(hfToken = "") }
        }
    }

    fun validateHfToken(token: String, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val testUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm"
                val result = downloader.validateTokenAccess(testUrl, token)
                if (result == "OK") {
                    callback(true, "Token is valid!")
                } else {
                    callback(false, result)
                }
            } catch (e: Exception) {
                callback(false, "Validation failed: ${e.localizedMessage}")
            }
        }
    }

    fun saveContextWindowSize(size: Int) {
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("context_window_size", size).apply()
        _uiState.update { it.copy(contextWindowSize = size) }
    }

    fun setThemeMode(mode: ThemeMode) {
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("theme_preference", mode.name).apply()
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun saveSystemPrompt(prompt: String) {
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("system_prompt", prompt).apply()
        _uiState.update { it.copy(systemPrompt = prompt) }

        // The system instruction is bound to the conversation when it is created,
        // so the conversation has to be rebuilt for the new prompt to apply.
        if (inferenceEngine.isModelLoaded && !_uiState.value.isGenerating) {
            viewModelScope.launch(Dispatchers.IO) {
                inferenceEngine.resetConversation(
                    systemPrompt = prompt,
                    initialHistory = buildEngineHistory()
                )
            }
        }
    }

    /**
     * Updates model inference and sampling parameters.
     * Recreates conversation state with the new sampler settings if a model is active.
     */
    fun updateSamplerSettings(
        temperature: Float = _uiState.value.temperature,
        topP: Float = _uiState.value.topP,
        topK: Int = _uiState.value.topK,
        repetitionPenalty: Float = _uiState.value.repetitionPenalty,
        enableThinking: Boolean = _uiState.value.enableThinking,
        thinkingBudget: Int = _uiState.value.thinkingTokenBudget
    ) {
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("sampler_temperature", temperature)
            .putFloat("sampler_top_p", topP)
            .putInt("sampler_top_k", topK)
            .putFloat("sampler_repetition_penalty", repetitionPenalty)
            .putBoolean("sampler_enable_thinking", enableThinking)
            .putInt("sampler_thinking_budget", thinkingBudget)
            .apply()

        _uiState.update {
            it.copy(
                temperature = temperature,
                topP = topP,
                topK = topK,
                repetitionPenalty = repetitionPenalty,
                enableThinking = enableThinking,
                thinkingTokenBudget = thinkingBudget
            )
        }

        if (inferenceEngine.isModelLoaded && !_uiState.value.isGenerating) {
            viewModelScope.launch(Dispatchers.IO) {
                val sampler = SamplerConfig(
                    topK = topK,
                    topP = topP.toDouble(),
                    temperature = temperature.toDouble(),
                    seed = 0
                )
                val thinking = ThinkingConfig(
                    enableThinking = enableThinking,
                    thinkingTokenBudget = thinkingBudget
                )
                inferenceEngine.resetConversation(
                    systemPrompt = _uiState.value.systemPrompt,
                    initialHistory = buildEngineHistory(),
                    samplerConfig = sampler,
                    thinkingConfig = thinking
                )
            }
        }
    }

    fun setPreferredBackend(backend: String) {
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("preferred_backend", backend).apply()
        _uiState.update { it.copy(preferredBackend = backend) }

        // If a model is currently loaded, re-load with new backend preference
        val activeId = _uiState.value.activeModelId
        val activePreset = ModelPreset.findByIdOrFileName(activeId)
        val fileName = activePreset?.fileName ?: _uiState.value.localModels.firstOrNull()
        if (fileName != null && activeId != null) {
            loadModel(fileName, activeId)
        }
    }

    fun stopGeneration() {
        inferenceJob?.cancel()
        _uiState.update { it.copy(isGenerating = false, isSearching = false, activeSearchQuery = null) }
        saveMessages(_uiState.value.messages)
        // Reset the engine conversation so that any cancelled C++ decode state is cleaned up
        viewModelScope.launch(Dispatchers.IO) {
            inferenceEngine.resetConversation(
                systemPrompt = _uiState.value.systemPrompt,
                initialHistory = buildEngineHistory()
            )
        }
    }

    fun setWebSearchMode(mode: WebSearchMode) {
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("web_search_mode", mode.name).apply()
        _uiState.update { it.copy(webSearchMode = mode) }
    }

    fun setWebSearchProvider(providerId: String) {
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("web_search_provider", providerId).apply()
        _uiState.update { it.copy(webSearchProviderId = providerId) }
    }

    fun setWebSearchMaxResults(maxResults: Int) {
        val clamped = maxResults.coerceIn(3, 8)
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("web_search_max_results", clamped).apply()
        _uiState.update { it.copy(webSearchMaxResults = clamped) }
    }

    fun saveSearchApiKey(key: String): Boolean {
        val success = tokenStorage.saveSecret(SecretStorage.KEY_SEARCH_API_KEY, key.trim())
        if (success) {
            _uiState.update { it.copy(searchApiKeyConfigured = key.isNotBlank()) }
        }
        return success
    }

    fun clearSearchApiKey(): Boolean {
        val success = tokenStorage.clearSecret(SecretStorage.KEY_SEARCH_API_KEY)
        if (success) {
            _uiState.update { it.copy(searchApiKeyConfigured = false) }
        }
        return success
    }

    fun setChatSearchOverride(mode: WebSearchMode?) {
        _uiState.update { it.copy(chatSearchOverride = mode) }
    }

    fun acknowledgeSearchDisclosure() {
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("web_search_disclosure_acknowledged", true).apply()
        _uiState.update { it.copy(searchDisclosureAcknowledged = true) }
    }

    suspend fun testSearch(query: String = "local AI news"): SearchOutcome {
        val providerId = _uiState.value.webSearchProviderId
        val apiKey = tokenStorage.getSecret(SecretStorage.KEY_SEARCH_API_KEY)
        val locale = java.util.Locale.getDefault()
        return webSearchClient.search(
            providerId = providerId,
            query = query,
            apiKey = apiKey,
            maxResults = 3,
            country = locale.country.lowercase().ifBlank { "us" },
            language = locale.language.lowercase().ifBlank { "en" }
        )
    }

    fun cancelLoading() {
        loadModelJob?.cancel()
        loadModelJob = null
        inferenceEngine.close()
        _uiState.update { 
            it.copy(
                modelState = ModelState.Unloaded,
                loadingModelId = null,
                loadingStage = null
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        try {
            val context = getApplication<Application>().applicationContext
            context.stopService(Intent(context, ModelDownloadService::class.java))
        } catch (_: Exception) {}
        _uiState.update { 
            it.copy(
                downloadState = DownloadState.Idle,
                currentDownloadingModelId = null
            )
        }
    }

    /**
     * Maps raw engine exceptions to clear, actionable messages for end users.
     */
    fun stripThinkingBlocks(rawContent: String): String = Companion.stripThinkingBlocks(rawContent)

    companion object {
        fun stripThinkingBlocks(rawContent: String): String {
            return rawContent
                .replace(Regex("""<think>[\s\S]*?(</think>|\Z)""", RegexOption.IGNORE_CASE), "")
                .trim()
        }
    }

    private fun friendlyErrorMessage(raw: String?, isGeneration: Boolean = false): String {
        if (raw.isNullOrBlank()) {
            return if (isGeneration) "Response generation encountered an error. Please try again."
                   else "An unknown error occurred while loading the model. Please try again."
        }

        val lower = raw.lowercase()
        return when {
            // Conversation lifecycle
            lower.contains("conversation is not alive") || lower.contains("not alive") ->
                "Conversation session was reset. Please try sending your message again."

            lower.contains("cancellationexception") || lower.contains("job was cancelled") ->
                "Generation was stopped."

            // Out of memory / resource exhaustion
            lower.contains("resource_exhausted") || lower.contains("out of memory") ||
            lower.contains("oom") || lower.contains("insufficient memory") ->
                "Not enough device/GPU memory to load this model. Try closing background apps or switching to a smaller model (e.g. Qwen 1.5B or DeepSeek 1.5B)."

            // File corruption or validation failure
            lower.contains("corrupted") || lower.contains("incomplete") ||
            lower.contains("not a valid model") || lower.contains("flatbuffer") ||
            lower.contains("tfl3") || lower.contains("zip archive") ->
                "The model file appears to be incomplete or corrupted. Please delete it in Manage Models and re-download."

            // Model file missing
            lower.contains("does not exist") || lower.contains("no such file") ->
                "Model file was not found on disk. Please download a model from the Models catalog."

            // Emulator / architecture guard
            lower.contains("emulator") || lower.contains("x86") ->
                "On-device LLM inference is not supported on emulators. Please use a physical ARM64 device."

            // RAM insufficient for CPU path
            lower.contains("inference aborted") || (lower.contains("ram") && lower.contains("prevent")) ->
                "This device does not have enough RAM to run this model safely. Try a smaller model."

            // GPU init failure or backend restriction
            lower.contains("backend restriction") || lower.contains("gpu_only") ->
                "This model requires Vulkan GPU acceleration. Check your GPU backend settings."

            // Specific NPU only failure
            lower.contains("npu backend was preferred") ->
                "NPU acceleration was requested, but Qualcomm QNN runtime is not available for this model. Switch to Auto or GPU in Settings."

            // Catch-all: return the original trimmed if too long
            else -> {
                val trimmed = if (raw.length > 250) raw.take(250) + "…" else raw
                if (isGeneration) "Generation error: $trimmed" else "Model loading failed: $trimmed"
            }
        }
    }

    /**
     * Maps the persisted chat into native engine turns so the model keeps its
     * own context instead of receiving a flattened transcript. The newest turns
     * win when the bundle's KV cache cannot hold the whole conversation.
     */
    /**
     * Maps the persisted chat into native engine turns with sliding-window budgeting.
     * To maximize effective context window and preserve KV cache for user dialog,
     * chain-of-thought `<think>` tags are stripped from previous assistant responses.
     */
    fun buildEngineHistory(): List<Message> {
        val preset = ModelPreset.findByIdOrFileName(_uiState.value.activeModelId)
        val rawContextLimit = inferenceEngine.detectedMaxContextTokens.takeIf { it > 0 }
            ?: (preset?.effectiveContextTokens ?: ModelPreset.DEFAULT_CONTEXT_TOKENS)
        // Reserve 25% of context limit for system instruction, user turn & generation headroom
        val budgetTokens = (rawContextLimit * 3) / 4
        val candidates = mutableListOf<ChatMessage>()
        var used = 0
        var rawTokensEstimated = 0

        for (msg in _uiState.value.messages.asReversed()) {
            if (msg.content.isBlank()) continue
            val contentToSend = if (!msg.isUser) {
                stripThinkingBlocks(msg.content)
            } else {
                msg.content
            }
            if (contentToSend.isBlank()) continue

            rawTokensEstimated += estimatePromptTokens(msg.content)
            val cost = estimatePromptTokens(contentToSend)
            if (used + cost > budgetTokens) break
            used += cost
            candidates.add(msg.copy(content = contentToSend))
        }

        candidates.reverse()

        // Drop any leading assistant message so history strictly starts with a user turn
        while (candidates.isNotEmpty() && !candidates.first().isUser) {
            candidates.removeAt(0)
        }

        val mapped = candidates.map { msg ->
            if (msg.isUser) Message.user(msg.content) else Message.model(msg.content)
        }

        if (rawTokensEstimated > used) {
            Log.d("LlmViewModel", "Thinking-strip context savings: $rawTokensEstimated tokens reduced to $used tokens in history")
        }
        return mapped
    }

    fun loadModel(fileName: String, modelId: String) {
        loadModelJob?.cancel()
        loadModelJob = viewModelScope.launch {
            _uiState.update { it.copy(modelState = ModelState.Loading, loadingModelId = modelId, loadingStage = "Validating model file...") }
            val matchingPreset = ModelPreset.presets.firstOrNull { it.id == modelId }
            val displayName = matchingPreset?.name ?: fileName

            val startNs = System.nanoTime()
            val sampler = SamplerConfig(
                topK = _uiState.value.topK,
                topP = _uiState.value.topP.toDouble(),
                temperature = _uiState.value.temperature.toDouble(),
                seed = 0
            )
            val modelDefaultThinking = matchingPreset?.defaultThinkingEnabled ?: false
            val thinking = ThinkingConfig(
                enableThinking = if (matchingPreset != null) modelDefaultThinking else _uiState.value.enableThinking,
                thinkingTokenBudget = _uiState.value.thinkingTokenBudget
            )

            val result = inferenceEngine.loadModel(
                modelPath = File(storageDir, fileName).absolutePath,
                preferredBackend = _uiState.value.preferredBackend,
                contextTurns = _uiState.value.contextWindowSize,
                systemPrompt = _uiState.value.systemPrompt,
                initialHistory = buildEngineHistory(),
                samplerConfig = sampler,
                thinkingConfig = thinking,
                onStageUpdate = { stage ->
                    _uiState.update { it.copy(loadingStage = stage) }
                }
            )
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            if (result.isSuccess) {
                recordModelLoadBenchmark(modelId, elapsedMs)
                val supportsThinking = inferenceEngine.detectedSupportsThinking
                _uiState.update { 
                    it.copy(
                        modelState = ModelState.Loaded(displayName),
                        activeModelId = modelId,
                        loadingModelId = null,
                        activeBackend = inferenceEngine.activeBackend,
                        lastNpuError = inferenceEngine.lastNpuError?.let { err ->
                            val sw = java.io.StringWriter()
                            err.printStackTrace(java.io.PrintWriter(sw))
                            sw.toString()
                        },
                        loadingStage = null,
                        detectedMaxContextTokens = inferenceEngine.detectedMaxContextTokens,
                        detectedSupportsThinking = supportsThinking,
                        detectedSupportsVision = inferenceEngine.detectedSupportsVision,
                        enableThinking = if (supportsThinking) (matchingPreset?.defaultThinkingEnabled ?: it.enableThinking) else false
                    )
                }
            } else {
                _uiState.update { 
                    it.copy(
                        modelState = ModelState.Error(friendlyErrorMessage(result.exceptionOrNull()?.message)),
                        activeBackend = "None",
                        loadingModelId = null,
                        lastNpuError = inferenceEngine.lastNpuError?.let { err ->
                            val sw = java.io.StringWriter()
                            err.printStackTrace(java.io.PrintWriter(sw))
                            sw.toString()
                        },
                        loadingStage = null
                    )
                }
            }
        }
    }

    fun clearAttachment() {
        _uiState.update { 
            it.copy(
                selectedImageUri = null,
                selectedVideoUri = null,
                selectedFileUri = null,
                selectedFileName = null,
                selectedFileType = null,
                extractedOcrText = null,
                isAttachmentProcessing = false,
                attachmentError = null
            )
        }
    }

    fun selectImage(uri: Uri?, context: Context) {
        if (uri == null) {
            _uiState.update { 
                it.copy(
                    selectedImageUri = null,
                    extractedOcrText = null,
                    isAttachmentProcessing = false,
                    attachmentError = null
                )
            }
            return
        }

        _uiState.update { 
            it.copy(
                selectedImageUri = uri.toString(),
                selectedVideoUri = null,
                selectedFileUri = null,
                selectedFileName = null,
                selectedFileType = null,
                isAttachmentProcessing = true,
                extractedOcrText = null,
                attachmentError = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val image = InputImage.fromFilePath(context, uri)
                val task = recognizer.process(image)
                val visionText = task.awaitTask()
                
                _uiState.update { 
                    it.copy(
                        extractedOcrText = visionText.text,
                        isAttachmentProcessing = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        attachmentError = "OCR Failed: ${e.localizedMessage}",
                        isAttachmentProcessing = false
                    )
                }
            } finally {
                try {
                    recognizer.close()
                } catch (ce: Exception) {
                    Log.w("LlmViewModel", "Error closing image recognizer: ${ce.message}")
                }
            }
        }
    }

    fun selectVideo(uri: Uri?, context: Context) {
        if (uri == null) {
            _uiState.update { 
                it.copy(
                    selectedVideoUri = null,
                    selectedFileName = null,
                    selectedFileType = null,
                    attachmentError = null
                )
            }
            return
        }

        val name = getFileName(context, uri)
        val ext = name.substringAfterLast('.', "mp4")

        _uiState.update { 
            it.copy(
                selectedImageUri = null,
                selectedVideoUri = uri.toString(),
                selectedFileUri = null,
                selectedFileName = name,
                selectedFileType = ext,
                isAttachmentProcessing = false,
                extractedOcrText = null,
                attachmentError = null
            )
        }
    }

    fun selectFile(uri: Uri?, context: Context) {
        if (uri == null) {
            _uiState.update { 
                it.copy(
                    selectedFileUri = null,
                    selectedFileName = null,
                    selectedFileType = null,
                    extractedOcrText = null,
                    isAttachmentProcessing = false,
                    attachmentError = null
                )
            }
            return
        }

        val name = getFileName(context, uri)
        val ext = name.substringAfterLast('.', "")

        _uiState.update { 
            it.copy(
                selectedImageUri = null,
                selectedVideoUri = null,
                selectedFileUri = uri.toString(),
                selectedFileName = name,
                selectedFileType = ext,
                isAttachmentProcessing = true,
                extractedOcrText = null,
                attachmentError = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (ext.equals("pdf", ignoreCase = true)) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd == null) {
                        _uiState.update { 
                            it.copy(
                                attachmentError = "Could not open file",
                                isAttachmentProcessing = false
                            )
                        }
                        return@launch
                    }
                    val pdfRenderer = PdfRenderer(pfd)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    try {
                        val pageCount = pdfRenderer.pageCount
                        val combinedText = StringBuilder()
                        val maxPages = minOf(pageCount, 3)

                        for (i in 0 until maxPages) {
                            val page = pdfRenderer.openPage(i)
                            try {
                                val width = page.width / 2
                                val height = page.height / 2
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                try {
                                    val canvas = Canvas(bitmap)
                                    canvas.drawColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                    val image = InputImage.fromBitmap(bitmap, 0)
                                    val visionText = recognizer.process(image).awaitTask()
                                    combinedText.append(visionText.text).append("\n")
                                } finally {
                                    bitmap.recycle()
                                }
                            } finally {
                                page.close()
                            }
                        }

                        _uiState.update { 
                            it.copy(
                                extractedOcrText = combinedText.toString(),
                                isAttachmentProcessing = false
                            )
                        }
                    } finally {
                        try {
                            recognizer.close()
                        } catch (ce: Exception) {
                            Log.w("LlmViewModel", "Error closing PDF recognizer: ${ce.message}")
                        }
                        try {
                            pdfRenderer.close()
                        } catch (pe: Exception) {
                            Log.w("LlmViewModel", "Error closing PdfRenderer: ${pe.message}")
                        }
                        try {
                            pfd.close()
                        } catch (fe: Exception) {
                            Log.w("LlmViewModel", "Error closing pfd: ${fe.message}")
                        }
                    }
                } else {
                    val text = context.contentResolver.openInputStream(uri)?.use { 
                        it.bufferedReader().readText() 
                    }
                    _uiState.update { 
                        it.copy(
                            extractedOcrText = text,
                            isAttachmentProcessing = false
                        )
                    }
                }
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("dynamite", ignoreCase = true) == true ||
                    e.message?.contains("null object reference", ignoreCase = true) == true ||
                    e.message?.contains("Waiting for the text optional module", ignoreCase = true) == true ->
                        "Text recognition model is initializing via Google Play Services. Please retry shortly."
                    else -> "Failed to parse file: ${e.localizedMessage ?: "Unknown error"}"
                }
                _uiState.update { 
                    it.copy(
                        attachmentError = errorMsg,
                        isAttachmentProcessing = false
                    )
                }
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "file"
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(task.exception ?: Exception("Task failed"))
            }
        }
    }

    fun sendMessage(content: String) {
        val imageUri = _uiState.value.selectedImageUri
        val videoUri = _uiState.value.selectedVideoUri
        val fileUri = _uiState.value.selectedFileUri
        val fileName = _uiState.value.selectedFileName
        val fileType = _uiState.value.selectedFileType
        val ocrText = _uiState.value.extractedOcrText

        _uiState.update { 
            it.copy(
                selectedImageUri = null,
                selectedVideoUri = null,
                selectedFileUri = null,
                selectedFileName = null,
                selectedFileType = null,
                extractedOcrText = null,
                isAttachmentProcessing = false,
                attachmentError = null
            )
        }

        if (content.isBlank() && ocrText.isNullOrBlank() && imageUri == null && videoUri == null && fileUri == null) return

        val messageText = if (content.isBlank()) {
            if (!ocrText.isNullOrBlank()) {
                "Analyze attachment: $fileName"
            } else if (imageUri != null) {
                "Sent image attachment"
            } else if (videoUri != null) {
                "Sent video attachment: $fileName"
            } else {
                "Sent file attachment: $fileName"
            }
        } else {
            content
        }

        val userMessage = ChatMessage(
            content = messageText,
            isUser = true,
            imageUri = imageUri,
            ocrText = ocrText,
            videoUri = videoUri,
            fileUri = fileUri,
            fileName = fileName,
            fileType = fileType
        )

        val currentMessages = _uiState.value.messages + userMessage
        _uiState.update { 
            it.copy(
                messages = currentMessages,
                isGenerating = true
            )
        }
        saveMessages(currentMessages)

        // Dynamic auto-titling: Automatically title 'New Chat' sessions from the first meaningful prompt
        val currentActiveId = _uiState.value.currentSessionId
        val currentSession = _uiState.value.sessions.firstOrNull { it.id == currentActiveId }
        if (currentSession != null && (currentSession.title == "New Chat" || currentSession.title.isBlank())) {
            val candidate = messageText.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Chat"
            val cleanTitle = if (candidate.length > 28) candidate.take(28).trimEnd() + "..." else candidate
            renameSession(currentSession.id, cleanTitle)
        }

        inferenceJob = viewModelScope.launch {
            val activeModel = _uiState.value.activeModelId
            val activePreset = ModelPreset.findByIdOrFileName(activeModel)
            val contextCapacity = activePreset?.effectiveContextTokens ?: ModelPreset.DEFAULT_CONTEXT_TOKENS
            // Keep the injected web context proportional to the bundle's real
            // KV cache so search results cannot crowd out the conversation.
            val charBudget = minOf(
                SearchContextBuilder.resolveCharBudget(contextCapacity),
                (contextCapacity * 3 / 4).coerceAtLeast(600)
            )

            val effectiveMode = _uiState.value.chatSearchOverride ?: _uiState.value.webSearchMode
            if (_uiState.value.chatSearchOverride != null) {
                _uiState.update { it.copy(chatSearchOverride = null) }
            }
            val providerId = _uiState.value.webSearchProviderId
            val maxResults = _uiState.value.webSearchMaxResults
            val isFree = providerId == "free"

            // Look up previous user turn for follow-up query resolution
            val previousUserMsg = currentMessages.dropLast(1).lastOrNull { it.isUser }?.content

            var searchOutcome: SearchOutcome? = null
            val queryToSearch: String? = when (effectiveMode) {
                WebSearchMode.OFF -> null
                WebSearchMode.ALWAYS -> SearchIntentDetector.resolveQuery(messageText, previousUserMsg)
                WebSearchMode.AUTO -> {
                    when (val intent = SearchIntentDetector.detectIntent(messageText, isFreeProvider = isFree, previousUserMessage = previousUserMsg)) {
                        is SearchIntentResult.NeedsSearch -> intent.cleanQuery
                        is SearchIntentResult.DirectUrl -> intent.url
                        is SearchIntentResult.NoSearch -> null
                    }
                }
            }

            if (!queryToSearch.isNullOrBlank()) {
                _uiState.update { it.copy(isSearching = true, activeSearchQuery = queryToSearch, webSearchError = null) }
                try {
                    val apiKey = tokenStorage.getSecret(SecretStorage.KEY_SEARCH_API_KEY)
                    val locale = java.util.Locale.getDefault()
                    val country = locale.country.lowercase().ifBlank { "us" }
                    val language = locale.language.lowercase().ifBlank { "en" }

                    searchOutcome = webSearchClient.search(
                        providerId = providerId,
                        query = queryToSearch,
                        apiKey = apiKey,
                        maxResults = maxResults,
                        country = country,
                        language = language
                    )
                    if (searchOutcome.isError) {
                        _uiState.update { it.copy(webSearchError = searchOutcome.errorMessage) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("LlmViewModel", "Web search failed: ${e.message}")
                    searchOutcome = SearchOutcome(
                        query = queryToSearch,
                        sources = emptyList(),
                        providerId = providerId,
                        isError = true,
                        errorMessage = e.message
                    )
                    _uiState.update { it.copy(webSearchError = e.message) }
                } finally {
                    _uiState.update { it.copy(isSearching = false, activeSearchQuery = null) }
                }
            }

            // Build search injection block if results exist or if Always mode failed
            val searchBlock = when {
                searchOutcome != null && searchOutcome.sources.isNotEmpty() -> {
                    SearchContextBuilder.build(searchOutcome.query, searchOutcome.sources, maxTotalChars = charBudget)
                }
                searchOutcome != null && searchOutcome.isError && (effectiveMode == WebSearchMode.ALWAYS) -> {
                    SearchContextBuilder.buildFailureNote(searchOutcome.query)
                }
                else -> ""
            }

            val hasSearchContext = searchBlock.isNotBlank()

            // The system instruction lives in the engine's native system slot
            // (bound when the conversation is created), so it is NOT repeated
            // inside the user turn. Only the per-turn payload is sent, which
            // keeps the prompt inside the model's training distribution.
            val currentPrefix = when {
                ocrText != null && activeModel?.contains("gemma", ignoreCase = true) != true -> "[Extracted Text from Attachment: $ocrText]\n"
                else -> ""
            }

            val turnParts = mutableListOf<String>()
            if (hasSearchContext) {
                turnParts += SearchContextBuilder.SYSTEM_SEARCH_INSTRUCTION
                turnParts += searchBlock
            }
            if (currentPrefix.isNotBlank()) {
                turnParts += currentPrefix.trimEnd()
            }
            turnParts += messageText
            val currentTurn = turnParts.joinToString("\n\n")

            // Extract downsampled bitmap natively if a vision-capable model is loaded (from image or video keyframe)
            val supportsVision = activePreset?.supportsVision
                ?: (activeModel?.contains("gemma", ignoreCase = true) == true)
            val context = getApplication<Application>().applicationContext
            val imageBitmap: Bitmap? = when {
                imageUri != null && supportsVision -> {
                    decodeSampledBitmap(context, Uri.parse(imageUri), maxDimension = 1024)
                }
                videoUri != null && supportsVision -> {
                    extractVideoKeyframe(context, Uri.parse(videoUri), maxDimension = 1024)
                }
                else -> null
            }

            val promptTokens = estimatePromptTokens(currentTurn, hasImage = (imageBitmap != null))
            val t0 = System.nanoTime()
            var tFirst: Long? = null
            var tokenCount = 0
            var wasCancelled = false
            val accumulatedBuilder = StringBuilder(1024)
            var lastUpdateTime = System.currentTimeMillis()
            val sourcesToAttach = searchOutcome?.sources?.takeIf { it.isNotEmpty() }

            try {
                val aiMessagePlaceholder = ChatMessage(
                    content = "",
                    isUser = false,
                    modelId = activeModel,
                    searchSources = sourcesToAttach,
                    searchQuery = searchOutcome?.query
                )
                _uiState.update { it.copy(messages = currentMessages + aiMessagePlaceholder) }

                val repetitionConfig = RepetitionPenaltyConfig(
                    repetitionPenalty = _uiState.value.repetitionPenalty
                )
                val turnThinkingConfig = ThinkingConfig(
                    enableThinking = _uiState.value.enableThinking,
                    thinkingTokenBudget = _uiState.value.thinkingTokenBudget
                )
                inferenceEngine.generateResponse(
                    prompt = currentTurn,
                    image = imageBitmap,
                    repetitionPenaltyConfig = repetitionConfig,
                    turnThinkingConfig = turnThinkingConfig
                )
                    .onCompletion { cause ->
                        if (cause is kotlinx.coroutines.CancellationException) {
                            wasCancelled = true
                        }
                    }
                    .collect { partialToken ->
                        if (tFirst == null) {
                            tFirst = System.nanoTime()
                        }
                        tokenCount++
                        accumulatedBuilder.append(partialToken)
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime > 100) {
                            val currentSnapshot = accumulatedBuilder.toString()
                            _uiState.update { state ->
                                val updatedMessages = ArrayList(state.messages)
                                if (updatedMessages.isNotEmpty()) {
                                    val oldMessage = updatedMessages.last()
                                    updatedMessages[updatedMessages.lastIndex] = oldMessage.copy(content = currentSnapshot)
                                }
                                state.copy(messages = updatedMessages)
                            }
                            lastUpdateTime = currentTime
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                wasCancelled = true
                throw e
            } catch (e: Exception) {
                if (accumulatedBuilder.isEmpty()) {
                    accumulatedBuilder.append(friendlyErrorMessage(e.message, isGeneration = true))
                }
            } finally {
                imageBitmap?.recycle()
                val tEnd = System.nanoTime()
                val firstTime = tFirst
                val telemetry = if (firstTime != null && tokenCount > 0) {
                    val ttftMs = (firstTime - t0) / 1_000_000
                    val decodeDurationSec = (tEnd - firstTime) / 1_000_000_000.0
                    val decodeTokPerSec = if (tokenCount > 1 && decodeDurationSec > 0.001) {
                        (tokenCount - 1) / decodeDurationSec
                    } else if (decodeDurationSec > 0.001) {
                        1.0 / decodeDurationSec
                    } else {
                        0.0
                    }
                    InferenceTelemetry(
                        ttftMs = ttftMs,
                        promptTokens = promptTokens,
                        decodeSpeedTokPerSec = decodeTokPerSec,
                        decodeTokens = tokenCount,
                        wasCancelled = wasCancelled
                    )
                } else null

                val finalContent = accumulatedBuilder.toString()
                _uiState.update { state ->
                    val updatedMessages = ArrayList(state.messages)
                    if (updatedMessages.isNotEmpty()) {
                        val oldMessage = updatedMessages.last()
                        updatedMessages[updatedMessages.lastIndex] = oldMessage.copy(
                            content = finalContent,
                            telemetry = telemetry,
                            searchSources = sourcesToAttach,
                            searchQuery = searchOutcome?.query
                        )
                    }
                    state.copy(messages = updatedMessages, isGenerating = false, isSearching = false, activeSearchQuery = null)
                }
                saveMessages(_uiState.value.messages)
            }
        }
    }

    /**
     * Creates a new chat session, switches to it, and clears conversation state.
     */
    fun createNewSession(title: String = "New Chat") {
        inferenceJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val newSession = chatRepository.createSession(
                title = title,
                modelId = _uiState.value.activeModelId
            )
            val updatedSessions = chatRepository.loadSessions()
            _uiState.update {
                it.copy(
                    sessions = updatedSessions,
                    currentSessionId = newSession.id,
                    messages = emptyList(),
                    isGenerating = false,
                    selectedImageUri = null,
                    selectedVideoUri = null,
                    selectedFileUri = null,
                    selectedFileName = null,
                    selectedFileType = null,
                    extractedOcrText = null
                )
            }
            if (inferenceEngine.isModelLoaded) {
                inferenceEngine.resetConversation(
                    systemPrompt = _uiState.value.systemPrompt,
                    initialHistory = emptyList()
                )
            }
        }
    }

    /**
     * Switches to an existing chat session and rebuilds the model conversation context.
     */
    fun switchSession(sessionId: String) {
        if (_uiState.value.currentSessionId == sessionId) return
        inferenceJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val sessionMessages = chatRepository.loadSessionMessages(sessionId)
            _uiState.update {
                it.copy(
                    currentSessionId = sessionId,
                    messages = sessionMessages,
                    isGenerating = false,
                    selectedImageUri = null,
                    selectedVideoUri = null,
                    selectedFileUri = null,
                    selectedFileName = null,
                    selectedFileType = null,
                    extractedOcrText = null
                )
            }
            if (inferenceEngine.isModelLoaded) {
                inferenceEngine.resetConversation(
                    systemPrompt = _uiState.value.systemPrompt,
                    initialHistory = buildEngineHistory()
                )
            }
        }
    }

    /**
     * Renames an existing chat session.
     */
    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.renameSession(sessionId, newTitle)
            val updated = chatRepository.loadSessions()
            _uiState.update { it.copy(sessions = updated) }
        }
    }

    /**
     * Deletes a chat session. If the deleted session was active, switches to another or creates a new one.
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.deleteSession(sessionId)
            val updated = chatRepository.loadSessions()
            if (_uiState.value.currentSessionId == sessionId) {
                if (updated.isNotEmpty()) {
                    val next = updated.first()
                    val nextMessages = chatRepository.loadSessionMessages(next.id)
                    _uiState.update {
                        it.copy(
                            sessions = updated,
                            currentSessionId = next.id,
                            messages = nextMessages,
                            isGenerating = false
                        )
                    }
                    if (inferenceEngine.isModelLoaded) {
                        inferenceEngine.resetConversation(
                            systemPrompt = _uiState.value.systemPrompt,
                            initialHistory = buildEngineHistory()
                        )
                    }
                } else {
                    createNewSession()
                }
            } else {
                _uiState.update { it.copy(sessions = updated) }
            }
        }
    }

    /**
     * Regenerates the last assistant response in the active chat.
     */
    fun regenerateLastResponse() {
        if (_uiState.value.isGenerating) return
        val current = _uiState.value.messages
        if (current.isEmpty()) return

        val last = current.lastOrNull()
        val (historyBeforeTurn, userTurn) = when {
            last != null && !last.isUser -> {
                val userIndex = current.indexOfLast { it.isUser }
                if (userIndex != -1) {
                    val turn = current[userIndex]
                    val history = current.subList(0, userIndex)
                    history to turn
                } else {
                    emptyList<ChatMessage>() to null
                }
            }
            last != null && last.isUser -> {
                val history = current.dropLast(1)
                history to last
            }
            else -> emptyList<ChatMessage>() to null
        }

        if (userTurn != null) {
            _uiState.update {
                it.copy(
                    messages = historyBeforeTurn,
                    selectedImageUri = userTurn.imageUri,
                    selectedVideoUri = userTurn.videoUri,
                    selectedFileUri = userTurn.fileUri,
                    selectedFileName = userTurn.fileName,
                    selectedFileType = userTurn.fileType,
                    extractedOcrText = userTurn.ocrText
                )
            }
            saveMessages(historyBeforeTurn)
            sendMessage(userTurn.content)
        }
    }

    fun clearChat() {
        inferenceJob?.cancel()
        _uiState.update { 
            it.copy(
                messages = emptyList(),
                isGenerating = false
            )
        }
        val activeId = _uiState.value.currentSessionId
        if (activeId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                chatRepository.saveSessionMessages(activeId, emptyList())
                val updatedSessions = chatRepository.loadSessions()
                _uiState.update { it.copy(sessions = updatedSessions) }
            }
        }
        // The engine keeps the conversation natively, so the model's own turn
        // history has to be dropped as well.
        viewModelScope.launch(Dispatchers.IO) {
            inferenceEngine.resetConversation(
                systemPrompt = _uiState.value.systemPrompt,
                initialHistory = emptyList()
            )
        }
    }

    fun importModelFile(context: Context, uri: Uri, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileName(context, uri)
                if (!fileName.endsWith(".litertlm", ignoreCase = true) &&
                    !fileName.endsWith(".task", ignoreCase = true)) {
                    viewModelScope.launch(Dispatchers.Main) {
                        callback(false, "Invalid file format. File must end with .litertlm or .task")
                    }
                    return@launch
                }

                val destFile = File(storageDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                if (!isValidModelFile(destFile)) {
                    destFile.delete()
                    viewModelScope.launch(Dispatchers.Main) {
                        callback(false, "Validation failed. The imported file is corrupted or not a valid LiteRT/MediaPipe model.")
                    }
                    return@launch
                }

                viewModelScope.launch(Dispatchers.Main) {
                    refreshDownloadedModels()
                    callback(true, "Successfully imported $fileName!")
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    callback(false, "Failed to import model: ${e.localizedMessage}")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val context = getApplication<Application>().applicationContext
        context.stopService(Intent(context, ModelDownloadService::class.java))
        inferenceJob?.cancel()
        inferenceEngine.close()
    }

    private fun extractVideoKeyframe(context: Context, uri: Uri, maxDimension: Int = 1024): Bitmap? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationUs = (durationStr?.toLongOrNull() ?: 1000L) * 1000L
            val targetUs = (durationUs / 2).coerceAtLeast(0L)
            val frame = retriever.getFrameAtTime(targetUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
            retriever.release()
            frame?.let { raw ->
                val maxSide = maxOf(raw.width, raw.height)
                if (maxSide > maxDimension) {
                    val scale = maxDimension.toFloat() / maxSide.toFloat()
                    val matrix = android.graphics.Matrix().apply { postScale(scale, scale) }
                    val scaled = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    if (scaled != raw) {
                        raw.recycle()
                    }
                    scaled
                } else {
                    raw
                }
            }
        } catch (e: Exception) {
            Log.w("LlmViewModel", "Could not extract video keyframe: ${e.message}")
            null
        }
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int = 1024): Bitmap? {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var inSampleSize = 1
            val maxSide = maxOf(options.outWidth, options.outHeight)
            while ((maxSide / inSampleSize) > maxDimension * 2) {
                inSampleSize *= 2
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val sampled = context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            var orientation = android.media.ExifInterface.ORIENTATION_NORMAL
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = android.media.ExifInterface(stream)
                    orientation = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (e: Exception) {
                // Ignore EXIF read errors
            }

            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }

            val currentMax = maxOf(sampled.width, sampled.height)
            if (currentMax > maxDimension) {
                val scale = maxDimension.toFloat() / currentMax.toFloat()
                matrix.postScale(scale, scale)
            }

            val transformed = if (!matrix.isIdentity) {
                val result = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
                if (result != sampled) {
                    sampled.recycle()
                }
                result
            } else {
                sampled
            }
            transformed
        } catch (e: Exception) {
            null
        }
    }

}
