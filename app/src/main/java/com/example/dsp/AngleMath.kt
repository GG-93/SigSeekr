package com.example.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class OrientationResult(
    val azimuthRad: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val isTiltGuardTriggered: Boolean
)

/**
 * Coordinate-frame and RF angular math invariants.
 *
 * Android SensorManager.getOrientation() yields azimuth psi in [-pi, pi]
 * where 0 = North and +pi/2 = East (clockwise).
 * Standard Cartesian grid heading theta is counter-clockwise from +X (East):
 *     theta = (pi / 2) - psi
 */
object AngleMath {
    private const val TWO_PI = 2.0 * PI
    const val TILT_GUARD_MAX_ROLL_DEG = 45.0
    const val TILT_GUARD_MIN_PITCH_DEG = 15.0
    const val UPRIGHT_PITCH_SWITCH_DEG = 45.0

    /** Wraps an arbitrary angle in radians strictly into (-pi, pi]. */
    fun wrapAngle(angleRad: Double): Double {
        var wrapped = (angleRad + PI) % TWO_PI
        if (wrapped <= 0.0) wrapped += TWO_PI
        return wrapped - PI
    }

    /**
     * Converts Android compass azimuth psi (clockwise from North) with optional
     * manual Zero Heading offset psi_offset to mathematical Cartesian heading
     * theta (counter-clockwise from +X East):
     *     psi_adj = wrap(psi - psi_offset)
     *     theta   = wrap(pi/2 - psi_adj)
     */
    fun azimuthToCartesianHeading(
        azimuthPsiRad: Double,
        headingOffsetRad: Double = 0.0
    ): Double {
        val adjustedPsi = wrapAngle(azimuthPsiRad - headingOffsetRad)
        return wrapAngle((PI / 2.0) - adjustedPsi)
    }

    /**
     * Computes dead-reckoning Cartesian displacement (dx, dy) for a single step:
     *     dx = L_step * cos(theta)
     *     dy = L_step * sin(theta)
     */
    fun stepDisplacement(
        cartesianHeadingRad: Double,
        stepLengthMeters: Double
    ): Pair<Double, Double> {
        val dx = stepLengthMeters * cos(cartesianHeadingRad)
        val dy = stepLengthMeters * sin(cartesianHeadingRad)
        return dx to dy
    }

    /**
     * Models physical body shadowing or an aluminum backplate reflector as a
     * cardioid attenuation pattern in dB:
     *     A_shield(deltaTheta) = A_max * (1 - cos(deltaTheta)) / 2
     *
     * At deltaTheta = 0 (target straight ahead): 0 dB attenuation.
     * At deltaTheta = pi (target directly behind shield): A_max dB attenuation.
     */
    fun cardioidShieldAttenuationDb(
        deltaThetaRad: Double,
        maxAttenuationDb: Double
    ): Double {
        return maxAttenuationDb * (1.0 - cos(deltaThetaRad)) * 0.5
    }

    /**
     * Evaluates the tilt/pocket guard invariant:
     *     |roll| > 45 deg OR |pitch| < 15 deg  =>  shieldLoss = 0
     */
    fun isShieldTiltGuarded(pitchDeg: Double, rollDeg: Double): Boolean {
        if (!pitchDeg.isFinite() || !rollDeg.isFinite()) return true
        return abs(rollDeg) > TILT_GUARD_MAX_ROLL_DEG || abs(pitchDeg) < TILT_GUARD_MIN_PITCH_DEG
    }

    /**
     * Extracts singularity-free azimuth, pitch, and sideways roll directly from a
     * 3x3 Android rotation matrix (Device -> World ENU):
     * - Pitch (elevation of +Y top edge): 0 deg flat, -90 deg upright vertical.
     * - Roll (sideways tilt of +X right edge from horizontal): 0 deg level, +/-90 deg landscape.
     * - Azimuth:
     *     - When |pitch| < 45 deg (flat-to-angled): horizontal projection of +Y top edge.
     *     - When |pitch| >= 45 deg (upright/pocket): horizontal projection of rear camera (-Z),
     *       eliminating gimbal lock near +/-90 deg vertical pitch while aligning forward heading
     *       with where the user is looking (screen facing user).
     *     - If [screenOutwardMode] is enabled (e.g. phone placed screen-outward in breast pocket),
     *       adds +180 deg (+pi) so forward aligns with +Z (screen normal).
     */
    fun extractOrientationFromRotationMatrix(
        rotationMatrix: FloatArray,
        screenOutwardMode: Boolean = false
    ): OrientationResult {
        require(rotationMatrix.size >= 9) { "Rotation matrix must have at least 9 elements" }
        val r1 = rotationMatrix[1].toDouble()
        val r2 = rotationMatrix[2].toDouble()
        val r4 = rotationMatrix[4].toDouble()
        val r5 = rotationMatrix[5].toDouble()
        val r6 = rotationMatrix[6].toDouble().coerceIn(-1.0, 1.0)
        val r7 = rotationMatrix[7].toDouble().coerceIn(-1.0, 1.0)

        val pitchDeg = Math.toDegrees(-asin(r7))
        val rollDeg = Math.toDegrees(asin(r6))

        val baseAzimuth = if (abs(pitchDeg) >= UPRIGHT_PITCH_SWITCH_DEG) {
            // Rear camera (-Z) projected onto horizontal (East, North) plane
            atan2(-r2, -r5)
        } else {
            // Top edge (+Y) projected onto horizontal (East, North) plane
            atan2(r1, r4)
        }

        val adjustedAzimuth = if (screenOutwardMode) {
            wrapAngle(baseAzimuth + PI)
        } else {
            wrapAngle(baseAzimuth)
        }

        return OrientationResult(
            azimuthRad = adjustedAzimuth,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            isTiltGuardTriggered = isShieldTiltGuarded(pitchDeg, rollDeg)
        )
    }
}
