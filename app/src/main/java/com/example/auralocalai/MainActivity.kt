package com.example.auralocalai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.viewModels
import com.example.auralocalai.theme.AuraLocalAITheme
import com.example.auralocalai.ui.LlmViewModel

class MainActivity : ComponentActivity() {
  private val llmViewModel: LlmViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
    }

    enableEdgeToEdge()
    setContent {
      val uiState by llmViewModel.uiState.collectAsState()
      AuraLocalAITheme(themeMode = uiState.themeMode) { 
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { 
          MainNavigation(llmViewModel) 
        } 
      }
    }
  }
}
