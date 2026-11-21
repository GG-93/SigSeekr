package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Dark Tactical RF — Obsidian & Thermal Plasma Palette
val ObsidianBackground = Color(0xFF0A0D12)
val CharcoalSurface = Color(0xFF111620)
val ElevatedSurface = Color(0xFF18202E)
val ElevatedSurfaceHigh = Color(0xFF202B3D)
val TacticalOutline = Color(0xFF253247)
val TacticalGridLine = Color(0xFF1C2738)

val ElectricCyan = Color(0xFF22D3EE)
val ElectricCyanDim = Color(0xFF0E7490)
val ThermalAmber = Color(0xFFF59E0B)
val SignalEmerald = Color(0xFF10B981)
val TacticalCrimson = Color(0xFFF43F5E)
val ThermalYellow = Color(0xFFF0F921)

val ReadoutInkPrimary = Color(0xFFF1F5F9)
val ReadoutInkSecondary = Color(0xFF8B9BB4)
val ReadoutInkMuted = Color(0xFF56657D)

/**
 * Precomputed 256-entry Thermal Plasma ARGB lookup table for zero-allocation
 * 100x100 Bayesian probability heatmap rendering at 10-15 FPS.
 */
object ThermalPlasmaLut {
    private val controlPoints = arrayOf(
        intArrayOf(13, 8, 135),     // 0.00 - Deep Indigo (#0D0887)
        intArrayOf(70, 3, 159),     // 0.11 - Royal Purple (#46039F)
        intArrayOf(114, 1, 168),    // 0.22 - Violet (#7201A8)
        intArrayOf(156, 23, 158),   // 0.33 - Orchid (#9C179E)
        intArrayOf(189, 55, 134),   // 0.44 - Magenta (#BD3786)
        intArrayOf(216, 87, 107),   // 0.56 - Coral (#D8576B)
        intArrayOf(237, 121, 83),   // 0.67 - Sunset Orange (#ED7953)
        intArrayOf(251, 159, 58),   // 0.78 - Amber (#FB9F3A)
        intArrayOf(253, 202, 38),   // 0.89 - Gold (#FDCA26)
        intArrayOf(240, 249, 33)    // 1.00 - Incandescent Yellow (#F0F921)
    )

    val argbLut: IntArray = IntArray(256) { index ->
        if (index < 6) {
            // Keep sub-noise cells transparent so the obsidian reticle shines through
            0x00000000
        } else {
            val t = (index / 255.0).coerceIn(0.0, 1.0)
            val scaled = t * (controlPoints.size - 1)
            val lowIdx = scaled.toInt().coerceIn(0, controlPoints.size - 2)
            val frac = scaled - lowIdx
            val c0 = controlPoints[lowIdx]
            val c1 = controlPoints[lowIdx + 1]
            val r = (c0[0] + (c1[0] - c0[0]) * frac).toInt().coerceIn(0, 255)
            val g = (c0[1] + (c1[1] - c0[1]) * frac).toInt().coerceIn(0, 255)
            val b = (c0[2] + (c1[2] - c0[2]) * frac).toInt().coerceIn(0, 255)
            // Smooth alpha ramp from subtle wash at low density to vivid 92% at peak
            val alpha = (40 + (195 * kotlin.math.sqrt(t))).toInt().coerceIn(0, 235)
            (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
