package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SigSeekerDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianBackground,
    primaryContainer = ElectricCyanDim,
    onPrimaryContainer = ReadoutInkPrimary,
    secondary = ThermalAmber,
    onSecondary = ObsidianBackground,
    secondaryContainer = ElevatedSurfaceHigh,
    onSecondaryContainer = ThermalAmber,
    tertiary = SignalEmerald,
    onTertiary = ObsidianBackground,
    tertiaryContainer = ElevatedSurfaceHigh,
    onTertiaryContainer = SignalEmerald,
    error = TacticalCrimson,
    onError = ReadoutInkPrimary,
    background = ObsidianBackground,
    onBackground = ReadoutInkPrimary,
    surface = CharcoalSurface,
    onSurface = ReadoutInkPrimary,
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = ReadoutInkSecondary,
    outline = TacticalOutline,
    outlineVariant = TacticalGridLine
)

private val SigSeekerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp)
)

@Composable
fun SigSeekerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SigSeekerDarkColorScheme,
        typography = SigSeekerTypography,
        shapes = SigSeekerShapes,
        content = content
    )
}
