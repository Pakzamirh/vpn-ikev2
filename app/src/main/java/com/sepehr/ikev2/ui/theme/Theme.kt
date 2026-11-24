package com.sepehr.ikev2.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ObsidianBg = Color(0xFF070B13)
val SurfaceCard = Color(0xFF0F1623)
val SurfaceElevated = Color(0xFF161F30)
val SurfaceInput = Color(0xFF0A0F1A)
val BorderHairline = Color(0xFF1E293B)
val BorderBright = Color(0xFF2D3C55)
val EmeraldShield = Color(0xFF10B981)
val CobaltAccent = Color(0xFF3B82F6)
val AmberWarn = Color(0xFFF59E0B)
val CrimsonAlert = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)

private val SepehrDarkColors = darkColorScheme(
    primary = CobaltAccent,
    secondary = EmeraldShield,
    background = ObsidianBg,
    surface = SurfaceCard,
    surfaceVariant = SurfaceElevated,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = CrimsonAlert
)

@Composable
fun SepehrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SepehrDarkColors,
        content = content
    )
}
