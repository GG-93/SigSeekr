package com.example.dsp

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Thread-safe, double-buffered 100x100 Bayesian spatial probability grid filter.
 *
 * Arena: 50.0m x 50.0m at 0.5m resolution (10,000 cells).
 * Index layout: index = yIdx * GRID_SIZE + xIdx where xIdx in 0..99, yIdx in 0..99.
 *
 * Designed to run exclusively on Dispatchers.Default and publish immutable
 * FloatArray snapshots to Compose Canvas at 10-15 FPS.
 */
class BayesianGridFilter {
    companion object {
        const val GRID_SIZE = 100
        const val CELL_SIZE_METERS = 0.5
        const val ARENA_SIZE_METERS = GRID_SIZE * CELL_SIZE_METERS // 50.0m
        const val TOTAL_CELLS = GRID_SIZE * GRID_SIZE              // 10,000
        const val UNIFORM_PROB = 1.0 / TOTAL_CELLS                 // 1e-4
        const val UNDERFLOW_EPSILON = 1e-12
        const val MIN_DISTANCE_METERS = 0.25
        private val MAX_ENTROPY = ln(TOTAL_CELLS.toDouble())
    }

    private val lock = Any()
    private val sequenceCounter = AtomicLong(0L)

    // Precomputed cell center world coordinates (meters)
    private val cellCenterX = DoubleArray(GRID_SIZE) { i -> (i + 0.5) * CELL_SIZE_METERS }
    private val cellCenterY = DoubleArray(GRID_SIZE) { j -> (j + 0.5) * CELL_SIZE_METERS }

    // Double-buffered normalized probability matrices
    private var frontBuffer = DoubleArray(TOTAL_CELLS) { UNIFORM_PROB }
    private var backBuffer = DoubleArray(TOTAL_CELLS) { UNIFORM_PROB }

    private var updateCount: Long = 0L
    private var activeTier: LocalizationTier = LocalizationTier.NONE

    /** Resets all 10,000 cells back to uniform probability 1/10000. */
    fun resetToUniform() {
        synchronized(lock) {
            frontBuffer.fill(UNIFORM_PROB)
            backBuffer.fill(UNIFORM_PROB)
            updateCount = 0L
            activeTier = LocalizationTier.NONE
            sequenceCounter.incrementAndGet()
        }
    }

    /**
     * Tier 1 (Hardware Ranging - WifiRttManager):
     *     L(x, y) = exp( - (d_rtt - d_cell)^2 / (2 * sigma_rtt^2) )
     */
    fun updateWithRtt(
        userX: Double,
        userY: Double,
        rttDistanceMeters: Double,
        sigmaRttMeters: Double = 0.8
    ) {
        if (!rttDistanceMeters.isFinite() || rttDistanceMeters < 0.0) return
        val twoSigmaSq = 2.0 * sigmaRttMeters * sigmaRttMeters

        synchronized(lock) {
            val prior = frontBuffer
            val posterior = backBuffer
            var totalWeight = 0.0

            for (yIdx in 0 until GRID_SIZE) {
                val dy = cellCenterY[yIdx] - userY
                val dySq = dy * dy
                val rowOffset = yIdx * GRID_SIZE
                for (xIdx in 0 until GRID_SIZE) {
                    val dx = cellCenterX[xIdx] - userX
                    val dCell = sqrt(dx * dx + dySq)
                    val diff = rttDistanceMeters - dCell
                    val likelihood = exp(-(diff * diff) / twoSigmaSq)
                    val idx = rowOffset + xIdx
                    val updated = prior[idx] * likelihood
                    posterior[idx] = updated
                    totalWeight += updated
                }
            }

            normalizeAndSwapLocked(totalWeight, LocalizationTier.RTT_TIER1)
        }
    }

    /**
     * Tier 2 (Standalone Shielded RSSI Sweep):
     *     deltaTheta = wrap(atan2(y - y_u, x - x_u) - theta)
     *     A_shield   = A_max * (1 - cos(deltaTheta)) / 2
     *     R_pred     = P_0 - 10 * n * log10(max(d_cell, 0.25)) - A_shield
     *     L(x, y)    = exp( - (R_meas - R_pred)^2 / (2 * sigma_rssi^2) )
     */
    fun updateWithShieldedRssi(
        userX: Double,
        userY: Double,
        userHeadingRad: Double,
        measuredRssiDbm: Double,
        params: CalibrationParams
    ) {
        if (!measuredRssiDbm.isFinite()) return

        val aMax = params.shieldDropDb.toDouble()
        val p0 = params.txPowerP0Dbm.toDouble()
        val tenN = 10.0 * params.pathLossExponentN.toDouble()
        val twoSigmaSq = 2.0 * params.sigmaRssiDb * params.sigmaRssiDb

        synchronized(lock) {
            val prior = frontBuffer
            val posterior = backBuffer
            var totalWeight = 0.0

            for (yIdx in 0 until GRID_SIZE) {
                val dy = cellCenterY[yIdx] - userY
                val dySq = dy * dy
                val rowOffset = yIdx * GRID_SIZE
                for (xIdx in 0 until GRID_SIZE) {
                    val dx = cellCenterX[xIdx] - userX
                    val dCell = sqrt(dx * dx + dySq)
                    val clampedDist = max(dCell, MIN_DISTANCE_METERS)

                    val bearingToCell = atan2(dy, dx)
                    val deltaTheta = AngleMath.wrapAngle(bearingToCell - userHeadingRad)
                    val shieldAtten = aMax * (1.0 - cos(deltaTheta)) * 0.5
                    val predictedRssi = p0 - tenN * log10(clampedDist) - shieldAtten

                    val err = measuredRssiDbm - predictedRssi
                    val likelihood = exp(-(err * err) / twoSigmaSq)
                    val idx = rowOffset + xIdx
                    val updated = prior[idx] * likelihood
                    posterior[idx] = updated
                    totalWeight += updated
                }
            }

            normalizeAndSwapLocked(totalWeight, LocalizationTier.SHIELD_TIER2)
        }
    }

    private fun normalizeAndSwapLocked(totalWeight: Double, tier: LocalizationTier) {
        if (!totalWeight.isFinite() || totalWeight <= UNDERFLOW_EPSILON) {
            // Underflow protection: reset to uniform distribution
            backBuffer.fill(UNIFORM_PROB)
        } else {
            val invTotal = 1.0 / totalWeight
            for (i in 0 until TOTAL_CELLS) {
                backBuffer[i] *= invTotal
            }
        }

        // Swap double buffers so frontBuffer always holds the latest normalized state
        val temp = frontBuffer
        frontBuffer = backBuffer
        backBuffer = temp

        updateCount++
        activeTier = tier
        sequenceCounter.incrementAndGet()
    }

    /**
     * Produces an immutable FloatArray snapshot plus MAP peak coordinates and
     * Shannon-entropy convergence score for thread-safe Compose Canvas rendering.
     */
    fun createSnapshot(userX: Double, userY: Double): GridSnapshot {
        synchronized(lock) {
            val probs = FloatArray(TOTAL_CELLS)
            var maxProb = 0.0
            var peakXIdx = GRID_SIZE / 2
            var peakYIdx = GRID_SIZE / 2
            var entropy = 0.0

            for (yIdx in 0 until GRID_SIZE) {
                val rowOffset = yIdx * GRID_SIZE
                for (xIdx in 0 until GRID_SIZE) {
                    val idx = rowOffset + xIdx
                    val p = frontBuffer[idx]
                    probs[idx] = p.toFloat()
                    if (p > maxProb) {
                        maxProb = p
                        peakXIdx = xIdx
                        peakYIdx = yIdx
                    }
                    if (p > 1e-15) {
                        entropy -= p * ln(p)
                    }
                }
            }

            // Compute weighted local centroid in a 5x5 neighborhood around the MAP peak
            // for sub-cell (~0.1m) smooth estimation without multi-modal smearing.
            var localWeightSum = 0.0
            var weightedX = 0.0
            var weightedY = 0.0
            val yMin = (peakYIdx - 2).coerceAtLeast(0)
            val yMax = (peakYIdx + 2).coerceAtMost(GRID_SIZE - 1)
            val xMin = (peakXIdx - 2).coerceAtLeast(0)
            val xMax = (peakXIdx + 2).coerceAtMost(GRID_SIZE - 1)

            for (yIdx in yMin..yMax) {
                val rowOffset = yIdx * GRID_SIZE
                val cy = cellCenterY[yIdx]
                for (xIdx in xMin..xMax) {
                    val w = frontBuffer[rowOffset + xIdx]
                    localWeightSum += w
                    weightedX += w * cellCenterX[xIdx]
                    weightedY += w * cy
                }
            }

            val estX = if (updateCount == 0L || localWeightSum <= 1e-15) {
                userX
            } else {
                weightedX / localWeightSum
            }
            val estY = if (updateCount == 0L || localWeightSum <= 1e-15) {
                userY
            } else {
                weightedY / localWeightSum
            }

            val estDist = if (updateCount == 0L) 0.0 else hypot(estX - userX, estY - userY)
            val normalizedConfidence = if (updateCount == 0L) {
                0
            } else {
                ((1.0 - (entropy / MAX_ENTROPY)) * 100.0).toInt().coerceIn(0, 100)
            }

            return GridSnapshot(
                sequenceId = sequenceCounter.get(),
                probabilities = probs,
                maxProbability = maxProb.toFloat(),
                estimatedTargetX = estX.toFloat(),
                estimatedTargetY = estY.toFloat(),
                estimatedDistanceMeters = estDist.toFloat(),
                peakConfidencePct = normalizedConfidence,
                updateCount = updateCount,
                activeTier = activeTier
            )
        }
    }

    /** Direct read-only sum check helper for diagnostics and unit tests. */
    fun totalProbabilityMass(): Double = synchronized(lock) {
        var sum = 0.0
        for (i in 0 until TOTAL_CELLS) sum += frontBuffer[i]
        sum
    }

    /** Direct cell lookup helper for diagnostics and unit tests. */
    fun probabilityAt(xIdx: Int, yIdx: Int): Double = synchronized(lock) {
        frontBuffer[yIdx * GRID_SIZE + xIdx]
    }
}
