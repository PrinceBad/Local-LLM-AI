package com.example.auralocalai.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.auralocalai.data.DownloadState
import com.example.auralocalai.data.LlmInferenceEngine
import com.example.auralocalai.data.ModelDownloader
import com.example.auralocalai.data.ModelPreset
import com.example.auralocalai.data.LlmBackendRestriction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import android.content.Context
import android.content.Intent
import com.example.auralocalai.data.ModelDownloadService
import com.example.auralocalai.data.ServiceDownloadState
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import android.util.Log

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
    val fileType: String? = null
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
    val localModels: List<String> = emptyList(),
    val selectedImageUri: String? = null,
    val selectedVideoUri: String? = null,
    val selectedFileUri: String? = null,
    val selectedFileName: String? = null,
    val selectedFileType: String? = null,
    val extractedOcrText: String? = null,
    val isAttachmentProcessing: Boolean = false,
    val attachmentError: String? = null,
    val hfToken: String = ""
)

class LlmViewModel(application: Application) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val historyFile = File(application.filesDir, "chat_history.json")

    private fun saveMessages(messages: List<ChatMessage>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = json.encodeToString(ListSerializer(ChatMessage.serializer()), messages)
                historyFile.writeText(jsonString)
            } catch (e: Exception) {
                Log.e("LlmViewModel", "Failed to save chat history", e)
            }
        }
    }

    private fun loadMessages(): List<ChatMessage> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val jsonString = historyFile.readText()
            json.decodeFromString(ListSerializer(ChatMessage.serializer()), jsonString)
        } catch (e: Exception) {
            Log.e("LlmViewModel", "Failed to load chat history", e)
            emptyList()
        }
    }

    private val _uiState = MutableStateFlow(UiState(messages = loadMessages()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val downloader = ModelDownloader()
    private val inferenceEngine = LlmInferenceEngine(application.applicationContext)
    
    private val storageDir: File by lazy {
        val dir = application.getExternalFilesDir(null) ?: application.filesDir
        File(dir, "models").apply { mkdirs() }
    }

    private var downloadJob: Job? = null
    private var inferenceJob: Job? = null

    private fun migrateExistingModels() {
        val targetDir = storageDir
        
        // 1. Migrate from old direct external files dir
        val oldExternalDir = getApplication<Application>().getExternalFilesDir(null)
        if (oldExternalDir != null && oldExternalDir.exists() && oldExternalDir != targetDir) {
            val files = oldExternalDir.listFiles() ?: emptyArray()
            for (file in files) {
                if (file.isFile && (file.name.endsWith(".task") || file.name.endsWith(".bin"))) {
                    val destFile = File(targetDir, file.name)
                    try {
                        if (!destFile.exists()) {
                            file.copyTo(destFile, overwrite = true)
                        }
                        file.delete()
                    } catch (e: Exception) {
                        // Silently ignore
                    }
                }
            }
        }

        // 2. Migrate from old internal files dir
        val oldInternalDir = getApplication<Application>().filesDir
        if (oldInternalDir.exists() && oldInternalDir != targetDir) {
            val files = oldInternalDir.listFiles() ?: emptyArray()
            for (file in files) {
                if (file.isFile && (file.name.endsWith(".task") || file.name.endsWith(".bin"))) {
                    val destFile = File(targetDir, file.name)
                    try {
                        if (!destFile.exists()) {
                            file.copyTo(destFile, overwrite = true)
                        }
                        file.delete()
                    } catch (e: Exception) {
                        // Silently ignore
                    }
                }
            }
        }
    }

    init {
        migrateExistingModels()
        refreshDownloadedModels()

        // Initialize hfToken from SharedPreferences
        val prefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val initialHfToken = prefs.getString("hf_token", "") ?: ""
        _uiState.update { it.copy(hfToken = initialHfToken) }

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
                                currentDownloadingModelId = null,
                                downloadState = DownloadState.Error(state.message)
                            )
                        }
                    }
                }
            }
        }
        
        // Auto-load the first available downloaded model (skip on emulators/x86 to prevent error popups on startup)
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
            viewModelScope.launch {
                val firstDownloaded = uiState.value.localModels.firstOrNull()
                if (firstDownloaded != null) {
                    val matchingPreset = ModelPreset.presets.firstOrNull { it.fileName == firstDownloaded }
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
    }

    fun refreshDownloadedModels() {
        val files = storageDir.listFiles() ?: emptyArray()
        val localFiles = files.filter { it.isFile && (it.name.endsWith(".task") || it.name.endsWith(".bin")) }.map { it.name }
        _uiState.update { it.copy(localModels = localFiles) }
    }

    fun downloadModel(preset: ModelPreset) {
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

          val tempFile = File(storageDir, "$fileName.tmp")
        val destFile = File(storageDir, fileName)
        // Note: do NOT pre-delete tempFile - ModelDownloader supports resuming partial downloads

        val context = getApplication<Application>().applicationContext
        // Read HF token from SharedPreferences (set by user in Settings)
        val prefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val hfToken = prefs.getString("hf_token", "") ?: ""

        val intent = Intent(context, ModelDownloadService::class.java).apply {
            putExtra("url", url)
            putExtra("fileName", fileName)
            putExtra("modelId", modelId)
            putExtra("hfToken", hfToken)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun deleteModel(fileName: String) {
        viewModelScope.launch {
            try {
                val file = File(storageDir, fileName)
                if (file.exists()) {
                    file.delete()
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
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("hf_token", token).apply()
        _uiState.update { it.copy(hfToken = token) }
    }

    fun clearHfToken() {
        val context = getApplication<Application>().applicationContext
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().remove("hf_token").apply()
        _uiState.update { it.copy(hfToken = "") }
    }

    fun validateHfToken(token: String, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val testUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/deepseek_q8_ekv1280.task"
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

    fun cancelDownload() {
        val context = getApplication<Application>().applicationContext
        context.stopService(Intent(context, ModelDownloadService::class.java))
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
            val matchingPreset = ModelPreset.presets.firstOrNull { it.id == modelId }
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
            try {
                val image = InputImage.fromFilePath(context, uri)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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
                    val pageCount = pdfRenderer.pageCount
                    val combinedText = StringBuilder()
                    val maxPages = minOf(pageCount, 3)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                    for (i in 0 until maxPages) {
                        val page = pdfRenderer.openPage(i)
                        val width = page.width / 2
                        val height = page.height / 2
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val image = InputImage.fromBitmap(bitmap, 0)
                        val visionText = recognizer.process(image).awaitTask()
                        combinedText.append(visionText.text).append("\n")
                        page.close()
                    }
                    pdfRenderer.close()
                    pfd.close()

                    _uiState.update { 
                        it.copy(
                            extractedOcrText = combinedText.toString(),
                            isAttachmentProcessing = false
                        )
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
                _uiState.update { 
                    it.copy(
                        attachmentError = "Failed to parse file: ${e.localizedMessage}",
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

        inferenceJob = viewModelScope.launch {
            val systemHeader = "System: You are Local LLM/AI, a helpful, intelligent offline AI running locally on this mobile device. Keep your responses concise and precise.\n\n"
            
            val activeModel = _uiState.value.activeModelId
            
            val history = currentMessages.takeLast(6).joinToString("\n") { msg ->
                if (msg.isUser) {
                    val prefix = when {
                        // Skip OCR context injection for Gemma 4 native multimodal vision model to let it analyze natively
                        msg.ocrText != null && activeModel != "gemma4-e2b" && activeModel != "gemma4-e4b" -> "[Extracted Text from Attachment: ${msg.ocrText}]\n"
                        else -> ""
                    }
                    "User: $prefix${msg.content}"
                } else {
                    "AI: ${msg.content}"
                }
            }
            val fullPrompt = "$systemHeader$history\nAI: "

            // Extract bitmap natively if a vision-capable model is loaded and image is attached
            val imageBitmap: Bitmap? = if (imageUri != null && (activeModel == "gemma4-e2b" || activeModel == "gemma4-e4b")) {
                try {
                    val context = getApplication<Application>().applicationContext
                    context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            val aiMessagePlaceholder = ChatMessage("", isUser = false)
            _uiState.update { it.copy(messages = currentMessages + aiMessagePlaceholder) }

            var accumulatedText = ""
            inferenceEngine.generateResponse(fullPrompt, imageBitmap).collect { partialToken ->
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
            saveMessages(_uiState.value.messages)
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
        saveMessages(emptyList())
    }

    override fun onCleared() {
        super.onCleared()
        val context = getApplication<Application>().applicationContext
        context.stopService(Intent(context, ModelDownloadService::class.java))
        inferenceJob?.cancel()
        inferenceEngine.close()
    }
}

