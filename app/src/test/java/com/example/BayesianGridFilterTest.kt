package com.example

import com.example.dsp.AngleMath
import com.example.dsp.BayesianGridFilter
import com.example.dsp.CalibrationParams
import com.example.dsp.LocalizationTier
import com.example.sensors.GeigerFeedbackEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

class BayesianGridFilterTest {

    @Test
    fun azimuthToCartesianHeading_matchesCoordinateFrameLock() {
        // psi = 0 (North) -> theta = +pi/2 (North = +Y)
        val thetaNorth = AngleMath.azimuthToCartesianHeading(0.0)
        assertEquals(PI / 2.0, thetaNorth, 1e-6)
        val (dxN, dyN) = AngleMath.stepDisplacement(thetaNorth, 0.72)
        assertEquals(0.0, dxN, 1e-6)
        assertEquals(0.72, dyN, 1e-6)

        // psi = +pi/2 (East) -> theta = 0 (East = +X)
        val thetaEast = AngleMath.azimuthToCartesianHeading(PI / 2.0)
        assertEquals(0.0, thetaEast, 1e-6)
        val (dxE, dyE) = AngleMath.stepDisplacement(thetaEast, 0.72)
        assertEquals(0.72, dxE, 1e-6)
        assertEquals(0.0, dyE, 1e-6)

        // psi = -pi/2 (West) -> theta = pi (West = -X)
        val thetaWest = AngleMath.azimuthToCartesianHeading(-PI / 2.0)
        val (dxW, dyW) = AngleMath.stepDisplacement(thetaWest, 0.72)
        assertEquals(-0.72, dxW, 1e-6)
        assertEquals(0.0, dyW, 1e-6)
    }

    @Test
    fun cardioidShieldAttenuation_zeroAheadAndMaxBehind() {
        assertEquals(0.0, AngleMath.cardioidShieldAttenuationDb(0.0, 18.0), 1e-6)
        assertEquals(9.0, AngleMath.cardioidShieldAttenuationDb(PI / 2.0, 18.0), 1e-6)
        assertEquals(18.0, AngleMath.cardioidShieldAttenuationDb(PI, 18.0), 1e-6)
        assertEquals(18.0, AngleMath.cardioidShieldAttenuationDb(-PI, 18.0), 1e-6)
    }

    @Test
    fun tier1RttTrilateration_convergesNearTrueTargetWithinOneMeter() {
        val filter = BayesianGridFilter()
        val trueX = 31.0
        val trueY = 29.0

        val observerPoints = listOf(
            25.0 to 25.0,
            31.0 to 22.0,
            37.0 to 29.0,
            29.0 to 34.0
        )

        for ((ux, uy) in observerPoints) {
            val trueDist = hypot(trueX - ux, trueY - uy)
            filter.updateWithRtt(userX = ux, userY = uy, rttDistanceMeters = trueDist, sigmaRttMeters = 0.8)
            assertEquals(1.0, filter.totalProbabilityMass(), 1e-6)
        }

        val snap = filter.createSnapshot(29.0, 34.0)
        val err = hypot(snap.estimatedTargetX - trueX, snap.estimatedTargetY - trueY)
        assertEquals(LocalizationTier.RTT_TIER1, snap.activeTier)
        assertTrue("Expected RTT localization error < 1.0m, was $err", err < 1.0)
    }

    @Test
    fun tier2ShieldedRssiSweep_disambiguatesFrontVsRearShieldNull() {
        val filter = BayesianGridFilter()
        val params = CalibrationParams(
            shieldDropDb = 18.0f,
            txPowerP0Dbm = -45.0f,
            pathLossExponentN = 2.5f,
            sigmaRssiDb = 4.5
        )
        // True target is 6m directly North of (25, 25) -> (25, 31)
        // Facing North (theta = pi/2): unshielded RSSI = -45 - 25*log10(6) ≈ -64.45 dBm
        filter.updateWithShieldedRssi(
            userX = 25.0,
            userY = 25.0,
            userHeadingRad = PI / 2.0,
            measuredRssiDbm = -64.45,
            params = params
        )
        // Turn South (theta = -pi/2): target is directly behind shield -> -18 dB drop -> -82.45 dBm
        filter.updateWithShieldedRssi(
            userX = 25.0,
            userY = 25.0,
            userHeadingRad = -PI / 2.0,
            measuredRssiDbm = -82.45,
            params = params
        )

        assertEquals(1.0, filter.totalProbabilityMass(), 1e-6)
        // Probability at North cell (25m, 31m -> idx 50, 62) must greatly exceed South mirror (25m, 19m -> idx 50, 38)
        val probNorth = filter.probabilityAt(50, 62)
        val probSouth = filter.probabilityAt(50, 38)
        assertTrue("Expected North cell ($probNorth) >> South cell ($probSouth)", probNorth > probSouth * 10.0)
    }

    @Test
    fun underflowProtection_resetsToUniformDistribution() {
        val filter = BayesianGridFilter()
        // Feed an impossible RSSI (-500 dBm) so every cell's Gaussian likelihood underflows to 0.0
        filter.updateWithShieldedRssi(
            userX = 25.0,
            userY = 25.0,
            userHeadingRad = 0.0,
            measuredRssiDbm = -500.0,
            params = CalibrationParams()
        )
        assertEquals(1.0, filter.totalProbabilityMass(), 1e-6)
        assertTrue(abs(filter.probabilityAt(50, 50) - BayesianGridFilter.UNIFORM_PROB) < 1e-9)
    }

    @Test
    fun geigerFrequencyCurve_clampsToOneHzFarAndTwelveHzNear() {
        assertEquals(1.0f, GeigerFeedbackEngine.distanceToFrequencyHz(20.0f), 1e-4f)
        assertEquals(1.0f, GeigerFeedbackEngine.distanceToFrequencyHz(15.0f), 1e-4f)
        assertEquals(12.0f, GeigerFeedbackEngine.distanceToFrequencyHz(1.0f), 1e-4f)
        assertEquals(12.0f, GeigerFeedbackEngine.distanceToFrequencyHz(0.2f), 1e-4f)
        val mid = GeigerFeedbackEngine.distanceToFrequencyHz(5.0f)
        assertTrue("Expected smooth intermediate frequency in (1, 12), was $mid", mid in 1.1f..11.9f)
    }
}
