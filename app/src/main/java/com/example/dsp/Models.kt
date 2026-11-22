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

enum class PowerMode(val label: String, val frameIntervalMs: Long, val rttPollMs: Long) {
    TURBO("TURBO", frameIntervalMs = 80L, rttPollMs = 450L),
    ECO("ECO", frameIntervalMs = 200L, rttPollMs = 1_200L)
}

/**
 * 6-Stage Background Emitter Visual Geometry Stages driven by angular spread
 * of strong sightings (>= maxRssi - 6 dB) or convergence / SQLite pin status.
 */
enum class EmitterShapeStage(val label: String, val spreadBandLabel: String) {
    SEMICIRCLE("Semicircle", "180°"),
    TRIANGLE("Triangle", "120°"),
    SQUARE("Square", "90°"),
    PENTAGON("Pentagon", "45°"),
    HEXAGON("Hexagon", "20°"),
    PINNED("Pinned", "PINNED")
}

data class CalibrationParams(
    val shieldDropDb: Float = 16.0f,
    val txPowerP0Dbm: Float = -45.0f,
    val pathLossExponentN: Float = 2.5f,
    val stepLengthMeters: Float = 0.72f,
    val sigmaRttMeters: Double = 0.8,
    val sigmaRssiDb: Double = 4.5
) {
    companion object {
        const val MIN_SHIELD_DB = 0.0f
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
    /** Device pitch in degrees (0 deg flat, -90 deg upright portrait). */
    val pitchDeg: Double = -45.0,
    /** Device sideways roll in degrees (0 deg level, +/-90 deg landscape). */
    val rollDeg: Double = 0.0,
    /** True when |roll| > 45 deg or |pitch| < 15 deg, forcing shieldLoss = 0 dB. */
    val isShieldTiltGuarded: Boolean = false,
    /** True when user enables Screen Outward (+180 deg) breast-pocket orientation flip. */
    val screenOutwardMode: Boolean = false,
    /** True once at least one live IMU orientation event has been received. */
    val hasOrientationReading: Boolean = false,
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

    val cardinalLabel: String
        get() {
            val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val idx = (((compassDegrees + 22.5) / 45.0).toInt()) % 8
            return dirs[idx]
        }

    val isHeadingZeroed: Boolean
        get() = kotlin.math.abs(headingOffsetRad) > 1e-4
}

/**
 * Total ambient magnetic flux B = sqrt(Bx^2 + By^2 + Bz^2) in microTesla.
 * Readings < 25 uT or > 70 uT indicate rebar/vehicle/ferromagnetic compass distortion.
 */
data class MagneticFieldStatus(
    val fluxMicroTesla: Float = 45.0f,
    val baselineFluxMicroTesla: Float? = null,
    val driftDeltaMicroTesla: Float = 0f,
    val isMagneticWarped: Boolean = false,
    val needsFigure8Calibration: Boolean = false,
    val hasSensorReading: Boolean = false
)

data class RssiSamplePoint(
    val rawDbm: Int,
    val kalmanDbm: Float,
    val timestampElapsedMs: Long
)

data class MacSightingRecord(
    val macAddress: String,
    val lastSeenElapsedMs: Long,
    val lastRssiDbm: Int
)

data class RfMeasurement(
    val macAddress: String,
    val displayName: String,
    val transport: EmitterTransport,
    val rssiDbm: Int,
    val isRttResponder: Boolean = false,
    val rttDistanceMeters: Double? = null,
    val rttStdDevMeters: Double? = null,
    val timestampElapsedMs: Long,
    val scanResult: ScanResult? = null,
    val fingerprintHash: String = macAddress,
    val bleAddressType: Int? = null,
    val bleDecoded: BleDecodedPacket? = null,
    val wifiCapabilities: String? = null,
    val wifiVendorOuiHexes: List<String> = emptyList(),
    val frequencyMhz: Int? = null,
    val channelNumber: Int? = null,
    val channelWidthMhz: Int? = null,
    val wifiStandardLabel: String? = null,
    val wifiIeTags: List<Int> = emptyList(),
    val wifiVendorOuis: List<Pair<String, String>> = emptyList(),
    val wpsDeviceInfo: String? = null
)

data class DiscoveredEmitter(
    val macAddress: String,
    val displayName: String,
    val transport: EmitterTransport,
    val isRttResponder: Boolean,
    val rssiDbm: Int,
    val packetRateHz: Float,
    val lastSeenElapsedMs: Long,
    val scanResult: ScanResult? = null,
    val fingerprintHash: String = macAddress,
    val kalmanRssiDbm: Float = rssiDbm.toFloat().coerceIn(-127.0f, -1.0f),
    val bleDecoded: BleDecodedPacket? = null,
    val observedMacCount: Int = 1,
    val linkedMacs: List<String> = listOf(macAddress),
    val wifiCapabilities: String? = null,
    val droneProtocol: String? = null,
    val isDroneSuspect: Boolean = false,
    val isEvilTwinSuspect: Boolean = false,
    val evilTwinReason: String? = null,
    val isWatchlistMatch: Boolean = false,
    val isMuted: Boolean = false
) {
    val isMacRotated: Boolean
        get() = observedMacCount > 1 || linkedMacs.size > 1
}

data class BackgroundEmitter(
    val fingerprintHash: String,
    val latestMacAddress: String,
    val observedMacCount: Int,
    val displayName: String,
    val transport: EmitterTransport,
    val isRttResponder: Boolean,
    val kalmanRssiDbm: Float,
    val maxRssiDbm: Int,
    val estimatedDistanceMeters: Float,
    val estimatedX: Float,
    val estimatedY: Float,
    val peakBearingRad: Double,
    val angularSpreadDeg: Float,
    val shapeStage: EmitterShapeStage,
    val isSavedInLog: Boolean,
    val trackerTag: BleTrackerTag? = null,
    val lastSeenElapsedMs: Long,
    val baselineMeters: Float = 0.0f
)

data class EmitterDossierData(
    val fingerprintHash: String,
    val macAddress: String,
    val displayName: String,
    val transport: EmitterTransport,
    val isRttResponder: Boolean,
    val latestRawRssiDbm: Int,
    val latestKalmanRssiDbm: Float,
    val packetRateHz: Float,
    val meanDeltaMs: Float = 0.0f,
    val interArrivalJitterMs: Float,
    val clockSkewPpm: Float,
    val rssiHistory: List<RssiSamplePoint>,
    val macHistory: List<MacSightingRecord>,
    val bleDecoded: BleDecodedPacket?,
    val shapeStage: EmitterShapeStage?,
    val angularSpreadDeg: Float?,
    val estimatedDistanceMeters: Float?,
    val isPinned: Boolean,
    val isLocked: Boolean,
    val scanResult: ScanResult?,
    val displayFingerprintId: String = DeviceFingerprintEngine.displayFingerprintId(fingerprintHash),
    val primaryVendorLabel: String? = null,
    val bearingDegTrue: Int? = null,
    val bearingConfidencePct: Int = 0,
    val localizationModeLabel: String = "Omni SAR",
    val baselineMeters: Float = 0.0f,
    val enuEastMeters: Float = 0.0f,
    val enuNorthMeters: Float = 0.0f,
    val wgs84Lat: Double? = null,
    val wgs84Lon: Double? = null,
    val isInsidePerimeter: Boolean? = null,
    val activeGeofenceCount: Int = 0,
    val protocolProfile: String = "",
    val frequencyChannelLabel: String = "",
    val capabilitiesSummary: String = "",
    val wifiIeTags: List<Int> = emptyList(),
    val wifiVendorOuis: List<Pair<String, String>> = emptyList(),
    val wpsDeviceInfo: String? = null,
    val tailChaseScore: Int = 0,
    val firstSeenEpochMs: Long = 0L,
    val lastSeenEpochMs: Long = 0L,
    val isWatched: Boolean = false,
    val isMuted: Boolean = false,
    val totalPacketCount: Int = 0
)

data class GridSnapshot(
    val sequenceId: Long,
    val probabilities: FloatArray,
    val maxProbability: Float,
    val estimatedTargetX: Float,
    val estimatedTargetY: Float,
    val estimatedDistanceMeters: Float,
    /** Circular Error Probable (95% posterior probability mass radius in meters). */
    val cep95Meters: Float,
    val peakConfidencePct: Int,
    val updateCount: Long,
    val activeTier: LocalizationTier,
    val gridOriginX: Float = 0.0f,
    val gridOriginY: Float = 0.0f
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
            cep95Meters == other.cep95Meters &&
            peakConfidencePct == other.peakConfidencePct &&
            gridOriginX == other.gridOriginX &&
            gridOriginY == other.gridOriginY
    }

    override fun hashCode(): Int {
        var result = sequenceId.hashCode()
        result = 31 * result + updateCount.hashCode()
        result = 31 * result + activeTier.hashCode()
        result = 31 * result + estimatedTargetX.hashCode()
        result = 31 * result + estimatedTargetY.hashCode()
        result = 31 * result + estimatedDistanceMeters.hashCode()
        result = 31 * result + cep95Meters.hashCode()
        result = 31 * result + peakConfidencePct
        result = 31 * result + gridOriginX.hashCode()
        result = 31 * result + gridOriginY.hashCode()
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
                cep95Meters = 25.0f,
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
    val hasMagnetometer: Boolean = false,
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

enum class WatchlistMatchType(val label: String) {
    OUI("OUI Prefix"),
    SUBSTRING("SSID / Name"),
    FINGERPRINT("Fingerprint")
}

data class WatchlistEntry(
    val id: Long = 0L,
    val matchType: WatchlistMatchType,
    val pattern: String,
    val label: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class PerimeterTriggerMode(val label: String) {
    ENTER("Enter"),
    LEAVE("Leave"),
    BOTH("Both")
}

enum class PerimeterScope(val label: String) {
    ALL_UNKNOWN("All Unknown"),
    WATCHLIST_ONLY("Watchlist Only"),
    LOCKED_TARGET("Locked Target")
}

data class PerimeterEventRecord(
    val id: Long = 0L,
    val timestampEpochMs: Long,
    val emitterKey: String,
    val emitterName: String,
    val eventType: String, // "ENTER" or "LEAVE"
    val distanceMeters: Double,
    val rssiDbm: Int
)
