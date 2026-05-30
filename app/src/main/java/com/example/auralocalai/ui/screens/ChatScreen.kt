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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
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
                                is ModelState.Loaded -> "Local Active: "
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
                        .padding(bottom = 86.dp)
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = Color(0xFFE2E8F0),
                                shape = RoundedCornerShape(28.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                if (textInput.isNotBlank()) {
                                    viewModel.sendMessage(textInput)
                                    textInput = ""
                                }
                            },
                            enabled = textInput.isNotBlank() && !uiState.isGenerating,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (textInput.isNotBlank()) MaterialTheme.colorScheme.primary else Color(0xFFF1F5F9)
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send Message",
                                tint = if (textInput.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    // No model active prompt
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
            Text(
                text = message.content,
                color = textColor,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
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
