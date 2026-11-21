package com.example.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
}
