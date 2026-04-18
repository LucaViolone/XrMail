package com.xremail.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object XREmailColors {
    val surface = Color(0xFF07080C)
    val surfaceVariant = Color(0xFF1A1F2A)
    val surfaceElevated = Color(0xFF2E3544)

    val onSurface = Color(0xFFECF0F7)
    val onSurfaceVariant = Color(0xFFC6CDDA)
    val onSurfaceDim = Color(0xFF8892A4)
    val onSurfaceStrong = Color(0xFFFFFFFF)

    val primary = Color(0xFF7C9CFF)
    val secondary = Color(0xFF8BD4A0)
    val tertiary = Color(0xFFD4A88B)
    val error = Color(0xFFE98D8D)
    val aiAccent = Color(0xFF7C9CFF)

    val priorityHigh = Color(0xFFD4A88B)
    val priorityMedium = Color(0xFF8892A4)
    val priorityLow = Color(0xFF6A6A7A)

    val glassFill = Color(0x12FFFFFF)
    val glassFillStrong = Color(0xB8161A24)
    val glassStroke = Color(0x24FFFFFF)
    val glassEdge = Color(0x47FFFFFF)
}

@Immutable
data class ExtendedColors(
    val aiAccent: Color = XREmailColors.aiAccent,
    val priorityHigh: Color = XREmailColors.priorityHigh,
    val priorityMedium: Color = XREmailColors.priorityMedium,
    val priorityLow: Color = XREmailColors.priorityLow,
    val surfaceElevated: Color = XREmailColors.surfaceElevated,
    val onSurfaceDim: Color = XREmailColors.onSurfaceDim,
    val glassFill: Color = XREmailColors.glassFill,
    val glassFillStrong: Color = XREmailColors.glassFillStrong,
    val glassStroke: Color = XREmailColors.glassStroke,
    val glassEdge: Color = XREmailColors.glassEdge,
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
