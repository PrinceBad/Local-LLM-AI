package com.example.auralocalai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auralocalai.ui.LlmViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: LlmViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var tokenInput by remember(uiState.hfToken) { mutableStateOf(uiState.hfToken) }
    var isTokenVisible by remember { mutableStateOf(false) }
    
    var isValidating by remember { mutableStateOf(false) }
    var validationResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "APP SETTINGS",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Go Back",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title Card with Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Token Settings",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Hugging Face Authentication",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Some gated, highly-capable models require a Hugging Face User Access Token (read permission) to download. Your token is stored securely in local app settings and only sent directly to Hugging Face during downloads.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            // Main Token Settings Input Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Hugging Face Access Token",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { 
                            tokenInput = it.trim()
                            validationResult = null
                        },
                        label = { Text("HF Token (hf_...)") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                                Icon(
                                    imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isTokenVisible) "Hide Token" else "Show Token"
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Token Status Hint Block
                    val isConfigured = uiState.hfToken.isNotBlank()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isConfigured) MaterialTheme.colorScheme.tertiaryContainer 
                                            else MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isConfigured) "Configured ✓" else "Not Configured",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isConfigured) MaterialTheme.colorScheme.onTertiaryContainer
                                        else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Validate button
                        Button(
                            onClick = {
                                if (tokenInput.isNotBlank()) {
                                    isValidating = true
                                    validationResult = null
                                    viewModel.validateHfToken(tokenInput) { isSuccess, msg ->
                                        isValidating = false
                                        validationResult = Pair(isSuccess, msg)
                                    }
                                }
                            },
                            enabled = tokenInput.isNotBlank() && !isValidating,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text(
                                text = if (isValidating) "Checking..." else "Validate Access",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Save button
                        Button(
                            onClick = {
                                viewModel.saveHfToken(tokenInput)
                                validationResult = null
                            },
                            enabled = tokenInput != uiState.hfToken && !isValidating,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = "Save Token",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Clear button
                    if (isConfigured) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                viewModel.clearHfToken()
                                tokenInput = ""
                                validationResult = null
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Remove Saved Token", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    // Validation status output
                    validationResult?.let { (isSuccess, message) ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSuccess) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                                                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSuccess) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = if (isSuccess) "Success" else "Error",
                                    tint = if (isSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = message,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSuccess) MaterialTheme.colorScheme.onTertiaryContainer
                                            else MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Footer / How to link
            Text(
                text = "Get your token by visiting Hugging Face settings page:",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = "huggingface.co/settings/tokens",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
