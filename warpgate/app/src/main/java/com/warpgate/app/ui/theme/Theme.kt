package com.warpgate.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// WarpGate dark palette (Happ-inspired)
val Bg0 = Color(0xFF070B14)
val Bg1 = Color(0xFF0C1220)
val Surface0 = Color(0xFF121A2B)
val Surface1 = Color(0xFF182238)
val Stroke0 = Color(0xFF1F2C46)
val Text0 = Color(0xFFEDF1FB)
val Text1 = Color(0xFF8B95AD)
val Accent = Color(0xFF4F7CFF)
val Accent2 = Color(0xFF9B5CFF)
val Green = Color(0xFF34D399)
val Amber = Color(0xFFF5A623)
val Red = Color(0xFFFF5C7A)

val Gradient = Brush.horizontalGradient(listOf(Accent, Accent2))

/**
 * Layout tokens.
 *
 * The app draws edge to edge, so every screen has to reserve room for the
 * floating bottom navigation. [BottomNavContentPadding] keeps the last card
 * reachable instead of hiding it under the navigation bar.
 */
val BottomNavContentPadding = 96.dp
val BottomNavHeight = 64.dp
val ScreenPadding = 18.dp

private val DarkColors = darkColorScheme(
    primary = Accent,
    secondary = Accent2,
    background = Bg0,
    onBackground = Text0,
    surface = Surface0,
    onSurface = Text0,
    surfaceVariant = Surface1,
    onSurfaceVariant = Text1,
    outline = Stroke0,
    error = Red,
)

@Composable
fun WarpGateTheme(content: @Composable () -> Unit) {
    // The app is dark-only by design (Happ-style).
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
