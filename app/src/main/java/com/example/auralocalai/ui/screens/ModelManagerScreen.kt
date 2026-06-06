package com.example.auralocalai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auralocalai.data.DownloadState
import com.example.auralocalai.ui.LlmViewModel
import com.example.auralocalai.ui.ModelState
import com.example.auralocalai.data.ModelPreset
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    viewModel: LlmViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var customUrl by remember { mutableStateOf("") }
    var customFileName by remember { mutableStateOf("model.task") }
    var isCustomExpanded by remember { mutableStateOf(false) }
    var modelToDelete by remember { mutableStateOf<ModelPreset?>(null) }
 
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("LOCAL LLM MANAGER", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.primary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Go Back", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header info banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Model Storage Directory",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "All large language models are downloaded to secure, external app file storage. Models remain 100% offline.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val isHfConfigured = uiState.hfToken.isNotBlank()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isHfConfigured) MaterialTheme.colorScheme.tertiaryContainer 
                                        else MaterialTheme.colorScheme.secondaryContainer
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isHfConfigured) "🔑 HF Token: Configured ✓" else "⚠ HF Token: Not Configured",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isHfConfigured) MaterialTheme.colorScheme.onTertiaryContainer
                                            else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Presets Header
            item {
                Text(
                    text = "Recommended Mobile Presets",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Recommended Presets List
            items(ModelPreset.presets) { preset ->
                val isDownloaded = uiState.localModels.contains(preset.fileName)
                val isActive = uiState.activeModelId == preset.id
                val isDownloading = uiState.currentDownloadingModelId == preset.id

                PresetModelCard(
                    preset = preset,
                    isDownloaded = isDownloaded,
                    isActive = isActive,
                    isDownloading = isDownloading,
                    downloadState = uiState.downloadState,
                    modelState = uiState.modelState,
                    onDownload = { viewModel.downloadModel(preset) },
                    onLoad = { viewModel.loadModel(preset.fileName, preset.id) },
                    onCancel = { viewModel.cancelDownload() },
                    onDelete = { modelToDelete = preset }
                )
            }

            // Custom Downloader Expandable Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isCustomExpanded = !isCustomExpanded }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Download Custom model (.task)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = if (isCustomExpanded) "Collapse ?" else "Expand ?",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(visible = isCustomExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Paste a direct download link for a compatible quantized LLM (.task file) e.g. from community servers.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )

                                OutlinedTextField(
                                    value = customUrl,
                                    onValueChange = { customUrl = it },
                                    label = { Text("Direct Model URL") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                                        unfocusedBorderColor = Color(0xFFE2E8F0)
                                    )
                                )

                                OutlinedTextField(
                                    value = customFileName,
                                    onValueChange = { customFileName = it },
                                    label = { Text("Local Filename (must end in .task or .bin)") },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                                        unfocusedBorderColor = Color(0xFFE2E8F0)
                                    )
                                )

                                Button(
                                    onClick = {
                                        if (customUrl.isNotBlank() && (customFileName.endsWith(".task") || customFileName.endsWith(".bin"))) {
                                            viewModel.downloadModelFromUrl(customUrl, customFileName)
                                            customUrl = ""
                                        }
                                    },
                                    enabled = customUrl.isNotBlank() && (customFileName.endsWith(".task") || customFileName.endsWith(".bin")) && uiState.currentDownloadingModelId == null,
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    shape = RoundedCornerShape(23.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text("Start Custom Download", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (modelToDelete != null) {
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text("Delete Local Model?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete '${modelToDelete?.name}'? This will free up approximately ${modelToDelete?.sizeLabel} of storage space.") },
            confirmButton = {
                Button(
                    onClick = {
                        modelToDelete?.let { viewModel.deleteModel(it.fileName) }
                        modelToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun PresetModelCard(
    preset: ModelPreset,
    isDownloaded: Boolean,
    isActive: Boolean,
    isDownloading: Boolean,
    downloadState: DownloadState,
    modelState: ModelState,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title & Badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Size Pill tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Size: ${preset.sizeLabel}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        // RAM requirement Pill tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "RAM: ${preset.ramRequirement}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                if (isActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Active",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "ACTIVE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = preset.description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Action / Download panel
            if (isDownloading) {
                DownloadProgressPanel(state = downloadState, onCancel = onCancel)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isDownloaded) {
                        if (isActive) {
                            Box(
                                modifier = Modifier
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Loaded & Ready Offline",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            val isLoading = modelState is ModelState.Loading
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onDelete,
                                    enabled = !isLoading,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete model",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = onLoad,
                                    enabled = !isLoading,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text(
                                        text = if (isLoading) "Loading..." else "Load Model",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = onDownload,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text(
                                text = "Download (${preset.sizeLabel})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadProgressPanel(
    state: DownloadState,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        when (state) {
            DownloadState.Idle -> {
                Text("Initializing connection...", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is DownloadState.Progress -> {
                val speedMb = state.speedBytesPerSec / (1024.0 * 1024.0)
                val totalMb = state.totalBytes / (1024.0 * 1024.0)
                val downloadedMb = state.bytesDownloaded / (1024.0 * 1024.0)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Downloading: ${state.percentage}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f MB/s", speedMb),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LinearProgressIndicator(
                    progress = { state.percentage.toFloat() / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.US, "%.0f/%.0f MB (ETA: %d s)", downloadedMb, totalMb, state.etaSeconds),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Cancel",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { onCancel() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            is DownloadState.Success -> {
                Text(
                    text = "Download complete! Initializing local model...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            is DownloadState.Error -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Error: ${state.message}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Retry",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onCancel() }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
