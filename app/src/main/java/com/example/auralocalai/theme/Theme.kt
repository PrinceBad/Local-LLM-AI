package com.example.auralocalai.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    secondary = LightSecondary,
    tertiary = LightTertiary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = Color(0xFFBA1A1A)
)

@Composable
fun AuraLocalAITheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme, 
        typography = Typography, 
        content = content
    )
}
