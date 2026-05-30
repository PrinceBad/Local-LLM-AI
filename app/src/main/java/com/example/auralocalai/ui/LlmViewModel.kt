package com.example.auralocalai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.auralocalai.data.DownloadState
import com.example.auralocalai.data.LlmInferenceEngine
import com.example.auralocalai.data.ModelDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class PresetModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeLabel: String,
    val ramRequirement: String,
    val downloadUrl: String,
    val fileName: String
)

sealed interface ModelState {
    data object Unloaded : ModelState
    data object Loading : ModelState
    data class Loaded(val modelName: String) : ModelState
    data class Error(val message: String) : ModelState
}

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val modelState: ModelState = ModelState.Unloaded,
    val downloadState: DownloadState = DownloadState.Idle,
    val currentDownloadingModelId: String? = null,
    val activeModelId: String? = null,
    val localModels: List<String> = emptyList() // List of local model filenames
)

class LlmViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val downloader = ModelDownloader()
    private val inferenceEngine = LlmInferenceEngine(application.applicationContext)
    
    private val storageDir: File by lazy {
        application.getExternalFilesDir(null) ?: application.filesDir
    }

    private var downloadJob: Job? = null
    private var inferenceJob: Job? = null

    val presets = listOf(
        PresetModel(
            id = "gemma-2b",
            name = "Gemma 1.1 2B IT (Google)",
            description = "Google's ultra-optimized mobile LLM, excellent at chat, summarization, and instruction following.",
            sizeLabel = "1.4 GB",
            ramRequirement = "8 GB+ RAM",
            downloadUrl = "https://huggingface.co/lmstudio-community/gemma-2b-it-GGUF/resolve/main/gemma-2b-it-q4_K_M.task",
            fileName = "gemma-2b-it.task"
        ),
        PresetModel(
            id = "phi-2",
            name = "Phi-2 2.7B (Microsoft)",
            description = "Microsoft's research model, exceptional reasoning, logic, and coding capacities for its tiny footprint.",
            sizeLabel = "1.6 GB",
            ramRequirement = "8 GB+ RAM",
            downloadUrl = "https://huggingface.co/lmstudio-community/phi-2-GGUF/resolve/main/phi-2-q4_K_M.task",
            fileName = "phi-2.task"
        )
    )

    init {
        refreshDownloadedModels()
        // Auto-load the first available downloaded model
        viewModelScope.launch {
            val firstDownloaded = uiState.value.localModels.firstOrNull()
            if (firstDownloaded != null) {
                val matchingPreset = presets.firstOrNull { it.fileName == firstDownloaded }
                val modelName = matchingPreset?.name ?: firstDownloaded
                val modelId = matchingPreset?.id ?: firstDownloaded
                
                _uiState.update { it.copy(modelState = ModelState.Loading) }
                val result = inferenceEngine.loadModel(File(storageDir, firstDownloaded).absolutePath)
                if (result.isSuccess) {
                    _uiState.update { 
                        it.copy(
                            modelState = ModelState.Loaded(modelName),
                            activeModelId = modelId
                        )
                    }
                } else {
                    _uiState.update { it.copy(modelState = ModelState.Error(result.exceptionOrNull()?.message ?: "Failed to auto-load")) }
                }
            }
        }
    }

    fun refreshDownloadedModels() {
        val files = storageDir.listFiles() ?: emptyArray()
        val localFiles = files.filter { it.isFile && it.name.endsWith(".task") }.map { it.name }
        _uiState.update { it.copy(localModels = localFiles) }
    }

    fun downloadModel(preset: PresetModel) {
        downloadModelFromUrl(preset.downloadUrl, preset.fileName, preset.id)
    }

    fun downloadModelFromUrl(url: String, fileName: String, modelId: String = "custom") {
        downloadJob?.cancel()
        _uiState.update { 
            it.copy(
                currentDownloadingModelId = modelId,
                downloadState = DownloadState.Idle
            )
        }

        val destFile = File(storageDir, fileName)
        downloadJob = viewModelScope.launch {
            downloader.downloadModel(url, destFile).collect { state ->
                _uiState.update { it.copy(downloadState = state) }
                if (state is DownloadState.Success) {
                    refreshDownloadedModels()
                    // Auto load the newly downloaded model
                    loadModel(fileName, modelId)
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _uiState.update { 
            it.copy(
                downloadState = DownloadState.Idle,
                currentDownloadingModelId = null
            )
        }
    }

    fun loadModel(fileName: String, modelId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(modelState = ModelState.Loading) }
            val matchingPreset = presets.firstOrNull { it.id == modelId }
            val displayName = matchingPreset?.name ?: fileName

            val result = inferenceEngine.loadModel(File(storageDir, fileName).absolutePath)
            if (result.isSuccess) {
                _uiState.update { 
                    it.copy(
                        modelState = ModelState.Loaded(displayName),
                        activeModelId = modelId
                    )
                }
            } else {
                _uiState.update { 
                    it.copy(
                        modelState = ModelState.Error(result.exceptionOrNull()?.message ?: "Failed to load model")
                    )
                }
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || _uiState.value.isGenerating) return

        val userMessage = ChatMessage(content, isUser = true)
        val currentMessages = _uiState.value.messages + userMessage
        _uiState.update { 
            it.copy(
                messages = currentMessages,
                isGenerating = true
            )
        }

        inferenceJob = viewModelScope.launch {
            val systemPrompt = "You are Local LLM/AI, a helpful, intelligent offline AI running locally on this mobile device. " +
                    "Keep your responses concise and precise.\n\nUser: $content\nAI: "
            
            val aiMessagePlaceholder = ChatMessage("", isUser = false)
            _uiState.update { it.copy(messages = currentMessages + aiMessagePlaceholder) }

            var accumulatedText = ""
            inferenceEngine.generateResponse(systemPrompt).collect { partialToken ->
                accumulatedText += partialToken
                _uiState.update { state ->
                    val updatedMessages = state.messages.toMutableList()
                    if (updatedMessages.isNotEmpty()) {
                        updatedMessages[updatedMessages.lastIndex] = ChatMessage(accumulatedText, isUser = false)
                    }
                    state.copy(messages = updatedMessages)
                }
            }
            
            _uiState.update { it.copy(isGenerating = false) }
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
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        inferenceJob?.cancel()
        inferenceEngine.close()
    }
}
