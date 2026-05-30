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

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)
  // Shared LlmViewModel to persist state across screens
  val llmViewModel: LlmViewModel = viewModel()

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          ChatScreen(
              viewModel = llmViewModel,
              onNavigateToModelManager = { backStack.add(ModelManager) },
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
      },
  )
}