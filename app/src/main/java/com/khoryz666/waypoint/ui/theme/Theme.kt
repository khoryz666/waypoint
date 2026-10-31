package com.khoryz666.waypoint.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Focus = primary (indigo), accents = secondary (coral), minimum met = tertiary (green).
private val Light = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF0E1F66),
    secondary = Color(0xFFD9480F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE2D4),
    onSecondaryContainer = Color(0xFF4A1700),
    tertiary = Color(0xFF2B8A3E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F2DC),
    onTertiaryContainer = Color(0xFF0B3B17),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFE6E9EE),
    onSurfaceVariant = Color(0xFF5B616D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEDEFF3),
    surfaceContainerHigh = Color(0xFFE6E9EE),
    outline = Color(0xFF8A909B),
    outlineVariant = Color(0xFFDADDE3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFBEFF),
    onPrimary = Color(0xFF10267A),
    primaryContainer = Color(0xFF2B3F9C),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFFFB38F),
    onSecondary = Color(0xFF4A1700),
    secondaryContainer = Color(0xFF7A2C06),
    onSecondaryContainer = Color(0xFFFFE2D4),
    tertiary = Color(0xFF86DB9F),
    onTertiary = Color(0xFF0B3B17),
    tertiaryContainer = Color(0xFF155A28),
    onTertiaryContainer = Color(0xFFD3F2DC),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF262A32),
    onSurfaceVariant = Color(0xFFA4AAB5),
    surfaceContainerLowest = Color(0xFF14171C),
    surfaceContainerLow = Color(0xFF181B21),
    surfaceContainer = Color(0xFF1E2229),
    surfaceContainerHigh = Color(0xFF262A32),
    outline = Color(0xFF6E7480),
    outlineVariant = Color(0xFF2E333C),
)

private val WaypointTypography = Typography().run {
    copy(
        displayMedium = displayMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Tabular figures, so ticking numbers don't jitter. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")

val SmallCaps = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)

@Composable
fun WaypointTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = WaypointTypography,
        content = content,
    )
}
