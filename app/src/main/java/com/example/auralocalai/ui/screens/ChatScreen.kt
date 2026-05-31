package com.example.auralocalai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.example.auralocalai.ui.ChatMessage
import com.example.auralocalai.ui.LlmViewModel
import com.example.auralocalai.ui.ModelState
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import android.content.Intent
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: LlmViewModel,
    onNavigateToModelManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var textInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            viewModel.selectImage(uri, context)
        }
    )

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            viewModel.selectVideo(uri, context)
        }
    )

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            viewModel.selectFile(uri, context)
        }
    )

    // Scroll to bottom on new messages
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "LOCAL LLM/AI",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val subtitleText = when (val state = uiState.modelState) {
                                is ModelState.Loaded -> "Local Active: " + state.modelName
                                ModelState.Loading -> "Loading Model..."
                                is ModelState.Error -> "Engine Error"
                                ModelState.Unloaded -> "No model loaded (Offline)"
                            }
                            Text(
                                text = subtitleText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    },
                    actions = {
                        if (uiState.messages.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearChat() }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear Chat",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(onClick = onNavigateToModelManager) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Model Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                HorizontalDivider(color = Color(0xFFE2E8F0), thickness = 1.dp)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            if (uiState.messages.isEmpty()) {
                // Onboarding screen if no messages
                EmptyStateOnboarding(
                    modelState = uiState.modelState,
                    localModels = uiState.localModels,
                    onNavigateToModelManager = onNavigateToModelManager,
                    onQuickPrompt = { prompt ->
                        viewModel.sendMessage(prompt)
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 96.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(uiState.messages) { message ->
                            ChatBubble(message = message)
                        }

                        if (uiState.isGenerating) {
                            item {
                                TypingIndicatorBubble()
                            }
                        }
                    }
                }
            }

            // Floating bottom text inputs
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                if (uiState.modelState is ModelState.Loaded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = Color(0xFFE2E8F0),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(8.dp)
                    ) {
                        // Attachment preview row (if any attachment is selected)
                        val hasAttachment = uiState.selectedImageUri != null || uiState.selectedVideoUri != null || uiState.selectedFileUri != null
                        if (hasAttachment) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFF1F5F9)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (uiState.selectedImageUri != null) {
                                        AsyncImage(
                                            model = uiState.selectedImageUri,
                                            contentDescription = "Image preview",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    } else if (uiState.selectedVideoUri != null) {
                                        Icon(
                                            imageVector = Icons.Default.PlayCircle,
                                            contentDescription = "Video preview",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    } else if (uiState.selectedFileUri != null) {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = "File preview",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    val label = when {
                                        uiState.selectedImageUri != null -> "Image Attachment"
                                        uiState.selectedVideoUri != null -> uiState.selectedFileName ?: "Video"
                                        else -> uiState.selectedFileName ?: "Document"
                                    }
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    if (uiState.isAttachmentProcessing) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Processing attachment...",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else if (uiState.attachmentError != null) {
                                        Text(
                                            text = uiState.attachmentError ?: "Error",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Medium
                                        )
                                    } else if (uiState.extractedOcrText != null) {
                                        Text(
                                            text = "Extracted ${uiState.extractedOcrText?.length ?: 0} characters",
                                            fontSize = 11.sp,
                                            color = Color(0xFF16A34A),
                                            fontWeight = FontWeight.Medium
                                        )
                                    } else {
                                        Text(
                                            text = "Ready to send",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                IconButton(
                                    onClick = {
                                        viewModel.selectImage(null, context)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove attachment",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            
                            HorizontalDivider(
                                color = Color(0xFFF1F5F9),
                                thickness = 1.dp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    enabled = !hasAttachment && !uiState.isGenerating,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = "Attach Image",
                                        tint = if (!hasAttachment) MaterialTheme.colorScheme.primary else Color(0xFFCBD5E1),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                
                                IconButton(
                                    onClick = { videoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                                    enabled = !hasAttachment && !uiState.isGenerating,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = "Attach Video",
                                        tint = if (!hasAttachment) MaterialTheme.colorScheme.primary else Color(0xFFCBD5E1),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                
                                IconButton(
                                    onClick = { fileLauncher.launch("*/*") },
                                    enabled = !hasAttachment && !uiState.isGenerating,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = "Attach File",
                                        tint = if (!hasAttachment) MaterialTheme.colorScheme.primary else Color(0xFFCBD5E1),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(4.dp))
                            
                            TextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                placeholder = { Text("Ask Local LLM/AI...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 15.sp) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.weight(1f),
                                maxLines = 4
                            )
                            
                            Spacer(modifier = Modifier.width(4.dp))
                            
                            val canSend = (textInput.isNotBlank() || hasAttachment) && !uiState.isGenerating && !uiState.isAttachmentProcessing
                            IconButton(
                                onClick = {
                                    viewModel.sendMessage(textInput)
                                    textInput = ""
                                },
                                enabled = canSend,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (canSend) MaterialTheme.colorScheme.primary else Color(0xFFF1F5F9)
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send Message",
                                    tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = onNavigateToModelManager,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 2.dp,
                            pressedElevation = 6.dp
                        )
                    ) {
                        Text(
                            text = "Download or Load local LLM to start Chatting",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val bubbleColor = if (message.isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }

    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val shape = if (message.isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    val borderStroke = if (message.isUser) {
        BorderStroke(0.dp, Color.Transparent)
    } else {
        BorderStroke(1.dp, Color(0xFFE2E8F0))
    }

    val textColor = if (message.isUser) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            border = borderStroke,
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (message.isUser) 1.dp else 2.dp
            ),
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.imageUri != null) {
                    AsyncImage(
                        model = message.imageUri,
                        contentDescription = "Image attachment",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                if (message.videoUri != null) {
                    val context = LocalContext.current
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.parse(message.videoUri), "video/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // ignore
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircle,
                                contentDescription = "Play Video",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = message.fileName ?: "Attached Video",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    text = "Tap to play",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (message.fileUri != null) {
                    val context = LocalContext.current
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.parse(message.fileUri), "*/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // ignore
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = "Open File",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = message.fileName ?: "Document",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${message.fileType?.uppercase() ?: "Document"} • Tap to open",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (message.content.isNotEmpty()) {
                    Text(
                        text = message.content,
                        color = textColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
                
                if (message.ocrText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    var isExpanded by remember { mutableStateOf(false) }
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isExpanded = !isExpanded },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Extracted Text (OCR)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Toggle extracted text",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            if (isExpanded) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = message.ocrText,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TypingIndicatorBubble() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dotScales = List(3) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 800
                    0.4f at 0
                    1f at 200 + (index * 150)
                    0.4f at 500 + (index * 150)
                },
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot-"
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dotScales.forEach { scale ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = scale.value))
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStateOnboarding(
    modelState: ModelState,
    localModels: List<String>,
    onNavigateToModelManager: () -> Unit,
    onQuickPrompt: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Glowing tech emblem
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Local LLM Engine",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Welcome to Local LLM/AI",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.5).sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your 100% private, offline-first artificial intelligence running on native hardware.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (modelState is ModelState.Loaded) {
            Text(
                text = "Try a sample question:",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Start).padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            val samplePrompts = listOf(
                "Write a quick python code to sort a list.",
                "Explain dark matter in simple English sentences.",
                "Give me 3 healthy breakfast ideas under 10 minutes."
            )
            samplePrompts.forEach { prompt ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clickable { onQuickPrompt(prompt) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Text(
                        text = prompt,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else if (modelState is ModelState.Error) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Local LLM Engine Error",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = modelState.message,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onNavigateToModelManager,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text(
                            text = "Manage Models",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Color(0xFFEFF6FF)),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Local LLM Required",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val bodyMsg = if (localModels.isEmpty()) {
                        "To start using local generation, you must download a highly-quantized 4-bit model task from the internet."
                    } else {
                        "You have model files available. Please select and load a model to begin."
                    }
                    Text(
                        text = bodyMsg,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onNavigateToModelManager,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text(
                            text = if (localModels.isEmpty()) "Go to Downloader" else "Select & Load Model",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
