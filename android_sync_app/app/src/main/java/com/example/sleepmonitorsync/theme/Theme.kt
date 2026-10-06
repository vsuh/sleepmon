package com.example.sleepmonitorsync.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppBackground = Color(0xFF2E3440)
private val AppText = Color(0xFFFFFFFF)

private val AppColorScheme = darkColorScheme(
  primary = AppText,
  onPrimary = AppBackground,
  secondary = AppText,
  onSecondary = AppBackground,
  tertiary = AppText,
  onTertiary = AppBackground,
  background = AppBackground,
  onBackground = AppText,
  surface = AppBackground,
  onSurface = AppText,
  surfaceVariant = AppBackground,
  onSurfaceVariant = AppText,
)

@Composable
fun SleepMonitorSyncTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = AppColorScheme,
    typography = Typography,
    content = content,
  )
}
