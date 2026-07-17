package com.ipdial.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.ThemeMode

// Premium Modern Dialer Palette
val BrandBlue = Color(0xFF0066FF)
val BrandBlueDark = Color(0xFF4A90E2)
val AccentRed = Color(0xFFFF3B30)
val AccentGreen = Color(0xFF34C759)

// Dark Theme Colors
val DarkBg = Color(0xFF0A0A0C)
val DarkSurface = Color(0xFF141417)
val DarkSurfaceVariant = Color(0xFF1E1E22)

// Light Theme Colors
val LightBg = Color(0xFFF2F2F7)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE5E5EA)

val EndRed = AccentRed
val ForestGreen = AccentGreen

fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    alpha: Float = 0.2f,
    borderWidth: Dp = 1.dp
): Modifier = this

private val DarkColorScheme = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1B2F4D),
    onPrimaryContainer = BrandBlueDark,
    secondary = Color(0xFF8E8E93),
    secondaryContainer = Color(0xFF2C2C2E),
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurface = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFFAEAEB2),
    error = AccentRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F0FF),
    onPrimaryContainer = BrandBlue,
    secondary = Color(0xFF8E8E93),
    secondaryContainer = Color(0xFFE5E5EA),
    background = LightBg,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurface = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF3A3A3C),
    error = AccentRed,
    onError = Color.White
)

enum class GlassMode { None }
val LocalGlassMode = staticCompositionLocalOf { GlassMode.None }

@Composable
fun IPDialTheme(
    themeMode: ThemeMode = ThemeMode.System,
    fontMultiplier: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.Dark, ThemeMode.Obsidian -> true
        ThemeMode.Light, ThemeMode.Quartz -> false
        else -> isSystemInDarkTheme()
    }
    
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = IPDialTypography, 
        shapes = IPDialShapes,         
        content = {
            CompositionLocalProvider(LocalGlassMode provides GlassMode.None) {
                content()
            }
        }
    )
}
