package com.example.dsp

import kotlin.math.PI
import kotlin.math.hypot

enum class SweepStage(val label: String) {
    IDLE("STANDBY"),
    SPIN_1("STAGE 1 · SPIN AT ANCHOR A"),
    WALK_PERPENDICULAR("STAGE 2 · WALK 5m PERPENDICULAR"),
    SPIN_2("STAGE 3 · SPIN AT ANCHOR B"),
    LOCKED("STAGE 4 · TARGET LOCKED")
}

data class SweepCoachState(
    val stage: SweepStage = SweepStage.IDLE,
    val anchorA: Point2D? = null,
    val anchorB: Point2D? = null,
    /** Peak signal bearing recorded during Spin 1 (Cartesian radians). */
    val anchorAPeakBearingRad: Double? = null,
    /** Recommended perpendicular walk bearings (+90° and -90° from Anchor A peak bearing). */
    val perpendicularWalkBearingARad: Double? = null,
    val perpendicularWalkBearingBRad: Double? = null,
    val spinCoverageDegrees: Int = 0,
    val walkDistanceMeters: Float = 0.0f,
    val targetWalkMeters: Float = SweepCoachEngine.REQUIRED_PERPENDICULAR_WALK_M,
    val currentCep95Meters: Float = 25.0f,
    val progressFraction: Float = 0.0f,
    val instruction: String = "Lock a target to begin guided two-spin triangulation"
)

/**
 * Guided Two-Spin Baseline Triangulation Coach for the single active locked target.
 *
 * Stages:
 * 1. SPIN_1: Sweep >= 270° (27 of 36 ten-degree heading bins) at Anchor A while recording peak RSSI bearing.
 * 2. WALK_PERPENDICULAR: Walk 5.0m perpendicular (±90°) from Anchor A peak bearing to establish baseline Anchor B.
 * 3. SPIN_2: Sweep >= 270° at Anchor B to resolve range/bearing ambiguity via cross-baseline triangulation.
 * 4. LOCKED: Converged when Bayesian grid CEP95 <= 1.5m.
 */
class SweepCoachEngine {

    companion object {
        const val REQUIRED_SPIN_DEGREES = 270
        const val REQUIRED_PERPENDICULAR_WALK_M = 5.0f
        const val LOCKED_CEP95_THRESHOLD_M = 1.5f
        private const val HEADING_BIN_COUNT = 36 // 10° per bin
        private const val REQUIRED_BINS = 27     // 27 * 10° = 270°
        private const val MIN_UPDATES_FOR_LOCK = 6L
    }

    private val lock = Any()
    private var stage: SweepStage = SweepStage.IDLE
    private var anchorA: Point2D? = null
    private var anchorB: Point2D? = null
    private val coveredBins = BooleanArray(HEADING_BIN_COUNT)
    private var bestSpin1RssiDbm: Int = Int.MIN_VALUE
    private var anchorAPeakBearingRad: Double? = null
    private var lastCep95Meters: Float = 25.0f

    fun reset() {
        synchronized(lock) {
            stage = SweepStage.IDLE
            anchorA = null
            anchorB = null
            coveredBins.fill(false)
            bestSpin1RssiDbm = Int.MIN_VALUE
            anchorAPeakBearingRad = null
            lastCep95Meters = 25.0f
        }
    }

    fun startForLockedTarget(userPose: UserPose): SweepCoachState {
        synchronized(lock) {
            stage = SweepStage.SPIN_1
            anchorA = Point2D(userPose.xMeters, userPose.yMeters)
            anchorB = null
            coveredBins.fill(false)
            bestSpin1RssiDbm = Int.MIN_VALUE
            anchorAPeakBearingRad = userPose.headingRad
            lastCep95Meters = 25.0f
            markHeadingBinLocked(userPose.headingRad)
            return buildStateLocked(userPose)
        }
    }

    fun update(
        userPose: UserPose,
        lockedTargetRssiDbm: Int?,
        gridSnapshot: GridSnapshot
    ): SweepCoachState {
        synchronized(lock) {
            if (stage == SweepStage.IDLE) {
                return buildStateLocked(userPose)
            }

            lastCep95Meters = gridSnapshot.cep95Meters

            // Immediate convergence promotion when Bayesian posterior meets CEP95 <= 1.5m
            if (gridSnapshot.updateCount >= MIN_UPDATES_FOR_LOCK &&
                gridSnapshot.cep95Meters <= LOCKED_CEP95_THRESHOLD_M
            ) {
                stage = SweepStage.LOCKED
                return buildStateLocked(userPose)
            }

            when (stage) {
                SweepStage.SPIN_1 -> {
                    if (anchorA == null) {
                        anchorA = Point2D(userPose.xMeters, userPose.yMeters)
                    }
                    markHeadingBinLocked(userPose.headingRad)
                    if (lockedTargetRssiDbm != null && lockedTargetRssiDbm >= bestSpin1RssiDbm) {
                        bestSpin1RssiDbm = lockedTargetRssiDbm
                        anchorAPeakBearingRad = userPose.headingRad
                    }
                    if (countCoveredBinsLocked() >= REQUIRED_BINS) {
                        stage = SweepStage.WALK_PERPENDICULAR
                    }
                }

                SweepStage.WALK_PERPENDICULAR -> {
                    val a = anchorA ?: Point2D(userPose.xMeters, userPose.yMeters).also { anchorA = it }
                    val walked = hypot(userPose.xMeters - a.x, userPose.yMeters - a.y).toFloat()
                    if (walked >= REQUIRED_PERPENDICULAR_WALK_M) {
                        anchorB = Point2D(userPose.xMeters, userPose.yMeters)
                        coveredBins.fill(false)
                        markHeadingBinLocked(userPose.headingRad)
                        stage = SweepStage.SPIN_2
                    }
                }

                SweepStage.SPIN_2 -> {
                    if (anchorB == null) {
                        anchorB = Point2D(userPose.xMeters, userPose.yMeters)
                    }
                    markHeadingBinLocked(userPose.headingRad)
                    if (countCoveredBinsLocked() >= REQUIRED_BINS &&
                        gridSnapshot.cep95Meters <= LOCKED_CEP95_THRESHOLD_M
                    ) {
                        stage = SweepStage.LOCKED
                    }
                }

                SweepStage.LOCKED -> {
                    // Stay locked until user resets grid or unlocks target
                }

                SweepStage.IDLE -> Unit
            }

            return buildStateLocked(userPose)
        }
    }

    fun currentState(userPose: UserPose): SweepCoachState = synchronized(lock) {
        buildStateLocked(userPose)
    }

    private fun markHeadingBinLocked(headingRad: Double) {
        val deg = ((Math.toDegrees(AngleMath.wrapAngle(headingRad)) % 360.0) + 360.0) % 360.0
        val bin = ((deg / 10.0).toInt()).coerceIn(0, HEADING_BIN_COUNT - 1)
        coveredBins[bin] = true
    }

    private fun countCoveredBinsLocked(): Int {
        var count = 0
        for (covered in coveredBins) {
            if (covered) count++
        }
        return count
    }

    private fun buildStateLocked(userPose: UserPose): SweepCoachState {
        val spinDeg = (countCoveredBinsLocked() * 10).coerceAtMost(360)
        val a = anchorA
        val walked = if (a != null) {
            hypot(userPose.xMeters - a.x, userPose.yMeters - a.y).toFloat()
        } else {
            0.0f
        }
        val peakBearing = anchorAPeakBearingRad
        val perpA = peakBearing?.let { AngleMath.wrapAngle(it + PI / 2.0) }
        val perpB = peakBearing?.let { AngleMath.wrapAngle(it - PI / 2.0) }

        val (progress, instruction) = when (stage) {
            SweepStage.IDLE -> 0.0f to "Select a target from Scanner to start 2-spin triangulation"
            SweepStage.SPIN_1 -> {
                val p = (spinDeg.toFloat() / REQUIRED_SPIN_DEGREES).coerceIn(0f, 1f)
                p to "Spin slowly at Anchor A ($spinDeg° / 270°) to map shield null"
            }
            SweepStage.WALK_PERPENDICULAR -> {
                val p = (walked / REQUIRED_PERPENDICULAR_WALK_M).coerceIn(0f, 1f)
                val remaining = (REQUIRED_PERPENDICULAR_WALK_M - walked).coerceAtLeast(0f)
                p to String.format(
                    java.util.Locale.US,
                    "Walk %.1fm perpendicular (±90°) to peak bearing to Anchor B",
                    remaining
                )
            }
            SweepStage.SPIN_2 -> {
                val p = (spinDeg.toFloat() / REQUIRED_SPIN_DEGREES).coerceIn(0f, 1f)
                if (spinDeg >= REQUIRED_SPIN_DEGREES) {
                    1.0f to String.format(
                        java.util.Locale.US,
                        "Refining lock · CEP95 %.1fm (target ≤ 1.5m)",
                        lastCep95Meters
                    )
                } else {
                    p to "Spin slowly at Anchor B ($spinDeg° / 270°) to triangulate"
                }
            }
            SweepStage.LOCKED -> {
                1.0f to String.format(
                    java.util.Locale.US,
                    "Triangulation locked · CEP95 %.2fm",
                    lastCep95Meters
                )
            }
        }

        return SweepCoachState(
            stage = stage,
            anchorA = anchorA,
            anchorB = anchorB,
            anchorAPeakBearingRad = peakBearing,
            perpendicularWalkBearingARad = perpA,
            perpendicularWalkBearingBRad = perpB,
            spinCoverageDegrees = spinDeg,
            walkDistanceMeters = walked,
            targetWalkMeters = REQUIRED_PERPENDICULAR_WALK_M,
            currentCep95Meters = lastCep95Meters,
            progressFraction = progress,
            instruction = instruction
        )
    }
}
