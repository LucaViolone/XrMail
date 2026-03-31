package com.xremail.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object XREmailColors {
    val surface = Color(0xFF0A0A0F)
    val surfaceVariant = Color(0xFF1A1A24)
    val surfaceElevated = Color(0xFF252535)

    val onSurface = Color(0xFFE8E8EE)
    val onSurfaceVariant = Color(0xFFA0A0B0)
    val onSurfaceDim = Color(0xFF6A6A7A)

    val primary = Color(0xFF7EB8D8)
    val secondary = Color(0xFF8BD4A0)
    val tertiary = Color(0xFFD4A88B)
    val error = Color(0xFFD88B8B)
    val aiAccent = Color(0xFFB8A0D8)

    val priorityHigh = Color(0xFFD4A88B)
    val priorityMedium = Color(0xFF7EB8D8)
    val priorityLow = Color(0xFF6A6A7A)
}

@Immutable
data class ExtendedColors(
    val aiAccent: Color = XREmailColors.aiAccent,
    val priorityHigh: Color = XREmailColors.priorityHigh,
    val priorityMedium: Color = XREmailColors.priorityMedium,
    val priorityLow: Color = XREmailColors.priorityLow,
    val surfaceElevated: Color = XREmailColors.surfaceElevated,
    val onSurfaceDim: Color = XREmailColors.onSurfaceDim,
)

val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors() }

private val XRDarkColorScheme = darkColorScheme(
    primary = XREmailColors.primary,
    secondary = XREmailColors.secondary,
    tertiary = XREmailColors.tertiary,
    error = XREmailColors.error,
    background = XREmailColors.surface,
    surface = XREmailColors.surface,
    surfaceVariant = XREmailColors.surfaceVariant,
    onBackground = XREmailColors.onSurface,
    onSurface = XREmailColors.onSurface,
    onSurfaceVariant = XREmailColors.onSurfaceVariant,
    onPrimary = XREmailColors.surface,
    onSecondary = XREmailColors.surface,
    onTertiary = XREmailColors.surface,
    onError = XREmailColors.surface,
)

@Composable
fun XREmailTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides ExtendedColors()) {
        MaterialTheme(
            colorScheme = XRDarkColorScheme,
            typography = XREmailTypography,
            content = content,
        )
    }
}
