package com.example.auralocalai.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanNeonPrimary,
    secondary = VioletNeonSecondary,
    tertiary = BlueNeonElectric,
    background = DeepSlateBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = DeepSlateBackground,
    onSecondary = TextWhite,
    onBackground = TextWhite,
    onSurface = TextWhite,
    onSurfaceVariant = TextGray,
    error = DarkError
)

@Composable
fun AuraLocalAITheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
      colorScheme = DarkColorScheme, 
      typography = Typography, 
      content = content
  )
}