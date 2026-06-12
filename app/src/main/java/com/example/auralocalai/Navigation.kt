package com.example.auralocalai

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.auralocalai.ui.LlmViewModel
import com.example.auralocalai.ui.screens.ChatScreen
import com.example.auralocalai.ui.screens.ModelManagerScreen
import com.example.auralocalai.ui.screens.SettingsScreen

@Composable
fun MainNavigation(llmViewModel: LlmViewModel) {
  val backStack = rememberNavBackStack(Main)
  // Shared LlmViewModel to persist state across screens
  

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          ChatScreen(
              viewModel = llmViewModel,
              onNavigateToModelManager = { backStack.add(ModelManager) },
              onNavigateToSettings = { backStack.add(Settings) },
              modifier = Modifier.fillMaxSize()
          )
        }
        entry<ModelManager> {
          ModelManagerScreen(
              viewModel = llmViewModel,
              onBack = { backStack.removeLastOrNull() },
              modifier = Modifier.fillMaxSize()
          )
        }
        entry<Settings> {
          SettingsScreen(
              viewModel = llmViewModel,
              onBack = { backStack.removeLastOrNull() },
              modifier = Modifier.fillMaxSize()
          )
        }
      },
  )
}
