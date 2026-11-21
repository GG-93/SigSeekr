package com.example.dsp

import android.net.wifi.ScanResult

enum class EmitterTransport(val shortLabel: String) {
    WIFI("Wi-Fi"),
    BLE("BLE"),
    EXTERNAL_USB("USB")
}

enum class LocalizationTier(val displayLabel: String) {
    NONE("IDLE"),
    RTT_TIER1("TIER 1 · RTT"),
    SHIELD_TIER2("TIER 2 · SHIELD")
}

data class CalibrationParams(
    val shieldDropDb: Float = 18.0f,
    val txPowerP0Dbm: Float = -45.0f,
    val pathLossExponentN: Float = 2.5f,
    val stepLengthMeters: Float = 0.72f,
    val sigmaRttMeters: Double = 0.8,
    val sigmaRssiDb: Double = 4.5
) {
    companion object {
        const val MIN_SHIELD_DB = 5.0f
        const val MAX_SHIELD_DB = 30.0f
        const val MIN_P0_DBM = -70.0f
        const val MAX_P0_DBM = -20.0f
        const val MIN_PATH_LOSS_N = 1.8f
        const val MAX_PATH_LOSS_N = 4.0f
        const val MIN_STEP_M = 0.50f
        const val MAX_STEP_M = 1.00f
    }
}

data class Point2D(
    val x: Double,
    val y: Double
)

data class UserPose(
    val xMeters: Double = 25.0,
    val yMeters: Double = 25.0,
    /** Mathematical Cartesian heading theta (CCW from +X East), radians. */
    val headingRad: Double = Math.PI / 2.0,
    /** Raw Android SensorManager azimuth psi in [-pi, pi] (0 = North, +pi/2 = East). */
    val rawAzimuthRad: Double = 0.0,
    /** User-calibrated compass offset psi_offset (radians) set by Zero Heading. */
    val headingOffsetRad: Double = 0.0,
    val stepCount: Int = 0,
    val trail: List<Point2D> = listOf(Point2D(25.0, 25.0))
) {
    /** Compass bearing in degrees [0, 360) after applying manual Zero Heading offset. */
    val compassDegrees: Int
        get() {
            val adjPsi = AngleMath.wrapAngle(rawAzimuthRad - headingOffsetRad)
            val deg = Math.toDegrees(adjPsi)
            return (((deg % 360.0) + 360.0) % 360.0).toInt()
        }

    val isHeadingZeroed: Boolean
        get() = kotlin.math.abs(headingOffsetRad) > 1e-4
}

data class RfMeasurement(
    val macAddress: String,
    val displayName: String,
    val transport: EmitterTransport,
    val rssiDbm: Int,
    val isRttResponder: Boolean = false,
    val rttDistanceMeters: Double? = null,
    val rttStdDevMeters: Double? = null,
    val timestampElapsedMs: Long,
    val scanResult: ScanResult? = null
)

data class DiscoveredEmitter(
    val macAddress: String,
    val displayName: String,
    val transport: EmitterTransport,
    val isRttResponder: Boolean,
    val rssiDbm: Int,
    val packetRateHz: Float,
    val lastSeenElapsedMs: Long,
    val scanResult: ScanResult? = null
)

data class GridSnapshot(
    val sequenceId: Long,
    val probabilities: FloatArray,
    val maxProbability: Float,
    val estimatedTargetX: Float,
    val estimatedTargetY: Float,
    val estimatedDistanceMeters: Float,
    val peakConfidencePct: Int,
    val updateCount: Long,
    val activeTier: LocalizationTier
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GridSnapshot) return false
        return sequenceId == other.sequenceId &&
            updateCount == other.updateCount &&
            activeTier == other.activeTier &&
            estimatedTargetX == other.estimatedTargetX &&
            estimatedTargetY == other.estimatedTargetY &&
            estimatedDistanceMeters == other.estimatedDistanceMeters &&
            peakConfidencePct == other.peakConfidencePct
    }

    override fun hashCode(): Int {
        var result = sequenceId.hashCode()
        result = 31 * result + updateCount.hashCode()
        result = 31 * result + activeTier.hashCode()
        result = 31 * result + estimatedTargetX.hashCode()
        result = 31 * result + estimatedTargetY.hashCode()
        return result
    }

    companion object {
        fun uniform(sequenceId: Long = 0L): GridSnapshot {
            val uniformVal = 1.0f / BayesianGridFilter.TOTAL_CELLS
            return GridSnapshot(
                sequenceId = sequenceId,
                probabilities = FloatArray(BayesianGridFilter.TOTAL_CELLS) { uniformVal },
                maxProbability = uniformVal,
                estimatedTargetX = 25.0f,
                estimatedTargetY = 25.0f,
                estimatedDistanceMeters = 0.0f,
                peakConfidencePct = 0,
                updateCount = 0L,
                activeTier = LocalizationTier.NONE
            )
        }
    }
}

data class HardwareStatus(
    val hasWifiRtt: Boolean = false,
    val hasBleScanner: Boolean = false,
    val hasRotationVector: Boolean = false,
    val hasStepDetector: Boolean = false,
    val usbBridgeAttached: Boolean = false
)

data class SessionSnapshotRecord(
    val id: Long = 0L,
    val timestampEpochMs: Long,
    val targetMac: String,
    val targetName: String,
    val estimatedX: Double,
    val estimatedY: Double,
    val minDistanceMeters: Double,
    val maxRssiDbm: Int,
    val localizationTier: String
)
