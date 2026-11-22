package com.example.dsp

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TimingStats(
    val meanDeltaMs: Float,
    val jitterSigmaMs: Float,
    val clockSkewPpm: Float
)

/**
 * Pure Kotlin SIGINT forensic math for inter-packet timing statistics
 * (mean Δt, sample standard deviation σ, and oscillator clock skew ppm)
 * and offline Tail-Chase persistence-across-displacement scoring.
 */
object SigintForensics {

    private const val WIFI_BEACON_TU_MS = 102.4

    fun computeTimingStats(
        intervalsMs: List<Double>,
        isWifi: Boolean
    ): TimingStats {
        if (intervalsMs.isEmpty()) {
            return TimingStats(0f, 0f, 0f)
        }
        val mean = intervalsMs.average()
        val variance = if (intervalsMs.size >= 2) {
            var sumSq = 0.0
            for (v in intervalsMs) {
                val d = v - mean
                sumSq += d * d
            }
            sumSq / (intervalsMs.size - 1)
        } else {
            0.0
        }
        val sigma = sqrt(variance)

        val skewPpm = if (intervalsMs.size >= 3) {
            val sorted = intervalsMs.sorted()
            val median = sorted[sorted.size / 2].coerceAtLeast(1.0)
            val reference = if (isWifi) {
                val mult = (median / WIFI_BEACON_TU_MS).roundToInt().coerceAtLeast(1)
                mult * WIFI_BEACON_TU_MS
            } else {
                // Quantize BLE advertising interval to nearest 10 ms step
                ((median / 10.0).roundToInt().coerceAtLeast(1)) * 10.0
            }
            val firstHalf = intervalsMs.take(intervalsMs.size / 2).average()
            val secondHalf = intervalsMs.drop(intervalsMs.size / 2).average()
            val drift = (secondHalf - firstHalf) / reference
            (drift * 1_000_000.0).toFloat().coerceIn(-99_999f, 99_999f)
        } else {
            0f
        }

        return TimingStats(
            meanDeltaMs = mean.toFloat(),
            jitterSigmaMs = sigma.toFloat(),
            clockSkewPpm = skewPpm
        )
    }

    /**
     * Evaluates whether a device is persistently following the operator across
     * significant spatial displacement (> 30m walked) while remaining strong (>= -75 dBm).
     * Returns an offline score in 0..100.
     */
    fun computeTailChaseScore(
        strongSightingPositions: List<Point2D>,
        operatorWalkedMeters: Double
    ): Int {
        if (operatorWalkedMeters < 30.0 || strongSightingPositions.size < 2) return 0
        var maxSpan = 0.0
        for (i in strongSightingPositions.indices) {
            val a = strongSightingPositions[i]
            for (j in i + 1 until strongSightingPositions.size) {
                val b = strongSightingPositions[j]
                val d = hypot(b.x - a.x, b.y - a.y)
                if (d > maxSpan) maxSpan = d
            }
        }
        if (maxSpan < 15.0) return 0
        val distanceFactor = ((maxSpan - 15.0) / 45.0).coerceIn(0.0, 1.0)
        val countFactor = ((strongSightingPositions.size - 1) / 5.0).coerceIn(0.2, 1.0)
        return (100.0 * (0.65 * distanceFactor + 0.35 * countFactor))
            .roundToInt()
            .coerceIn(0, 100)
    }
}
