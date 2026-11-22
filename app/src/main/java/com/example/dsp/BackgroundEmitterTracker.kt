package com.example.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Lightweight background multi-emitter tracker and 6-stage geometry classifier.
 *
 * - Groups all incoming Wi-Fi and BLE packets by `fingerprintHash` (from
 *   [DeviceFingerprintEngine]) so MAC-randomized BLE devices collapse into a
 *   single marker while Wi-Fi BSSIDs remain distinct.
 * - Supports immediate fingerprint & MAC pinning when saving a snapshot to SQLite.
 * - Assigns 6 visual shapes based on circular angular spread Δφ across strong sightings:
 *       > 120°         -> SEMICIRCLE (180°)
 *       90°..120°      -> TRIANGLE   (120°)
 *       45°..90°       -> SQUARE     (90°)
 *       20°..45°       -> PENTAGON   (45°)
 *       <= 20°         -> HEXAGON    (20°)
 *       <= 1.0m or Log -> PINNED     (PINNED)
 */
class BackgroundEmitterTracker {

    companion object {
        private const val STRONG_SIGHTING_MARGIN_DB = 6
        private const val MAX_SIGHTINGS_PER_EMITTER = 64
        private const val MIN_EST_DISTANCE_M = 0.5
        private const val MAX_EST_DISTANCE_M = 35.0
        private const val EXPIRY_MS = 45_000L

        fun classifyShapeStage(
            angularSpreadDeg: Float,
            estimatedDistanceMeters: Float,
            isSavedInLog: Boolean
        ): EmitterShapeStage {
            if (isSavedInLog || estimatedDistanceMeters <= 1.0f) {
                return EmitterShapeStage.PINNED
            }
            return when {
                angularSpreadDeg > 120.0f -> EmitterShapeStage.SEMICIRCLE
                angularSpreadDeg > 90.0f -> EmitterShapeStage.TRIANGLE
                angularSpreadDeg > 45.0f -> EmitterShapeStage.SQUARE
                angularSpreadDeg > 20.0f -> EmitterShapeStage.PENTAGON
                else -> EmitterShapeStage.HEXAGON
            }
        }

        fun computeCircularSpreadDegrees(anglesRad: List<Double>): Float {
            if (anglesRad.isEmpty()) return 360.0f
            if (anglesRad.size == 1) return 180.0f

            val sortedDeg = anglesRad
                .map { rad ->
                    val deg = Math.toDegrees(AngleMath.wrapAngle(rad))
                    ((deg % 360.0) + 360.0) % 360.0
                }
                .sorted()

            var maxGap = 0.0
            for (i in 0 until sortedDeg.size - 1) {
                val gap = sortedDeg[i + 1] - sortedDeg[i]
                if (gap > maxGap) maxGap = gap
            }
            val wrapGap = (sortedDeg.first() + 360.0) - sortedDeg.last()
            if (wrapGap > maxGap) maxGap = wrapGap

            return (360.0 - maxGap).toFloat().coerceIn(0.0f, 360.0f)
        }

        fun computeCircularMeanRad(anglesRad: List<Double>): Double {
            if (anglesRad.isEmpty()) return PI / 2.0
            var sumCos = 0.0
            var sumSin = 0.0
            for (a in anglesRad) {
                sumCos += cos(a)
                sumSin += sin(a)
            }
            return atan2(sumSin, sumCos)
        }

        fun estimateDistanceMeters(
            kalmanRssiDbm: Double,
            txPowerP0Dbm: Double,
            pathLossExponentN: Double
        ): Double {
            val safeRssi = RssiKalmanFilter.sanitizeNegativeDbm(kalmanRssiDbm)
            val safeN = pathLossExponentN.coerceAtLeast(1.2)
            val exponent = (txPowerP0Dbm - safeRssi) / (10.0 * safeN)
            val rawDist = 10.0.pow(exponent)
            return if (rawDist.isFinite()) {
                rawDist.coerceIn(MIN_EST_DISTANCE_M, MAX_EST_DISTANCE_M)
            } else {
                MAX_EST_DISTANCE_M
            }
        }
    }

    private data class SightingSample(
        val rssiDbm: Int,
        val headingRad: Double,
        val userX: Double,
        val userY: Double
    )

    private class TrackedEntry(
        val fingerprintHash: String,
        var latestMacAddress: String,
        var displayName: String,
        val transport: EmitterTransport,
        var isRttResponder: Boolean,
        val kalman: RssiKalmanFilter = RssiKalmanFilter(),
        var maxRssiDbm: Int,
        var lastSeenElapsedMs: Long,
        var advertisedTxPowerP0: Double? = null,
        var trackerTag: BleTrackerTag? = null,
        val observedMacs: LinkedHashMap<String, MacSightingRecord> = LinkedHashMap(),
        val sightings: ArrayDeque<SightingSample> = ArrayDeque()
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, TrackedEntry>()
    private val savedMacs = LinkedHashSet<String>()
    private val savedFingerprints = LinkedHashSet<String>()

    fun updateSavedLogMacs(macs: Set<String>) {
        synchronized(lock) {
            savedMacs.clear()
            macs.forEach { savedMacs.add(it.uppercase()) }
        }
    }

    /**
     * Immediately pins a target's fingerprintHash and MAC address upon Save Snapshot
     * so its marker transitions to the permanent emerald/gold Pinned Target Circle right away.
     */
    fun pinTarget(fingerprintHash: String?, macAddress: String) {
        synchronized(lock) {
            savedMacs.add(macAddress.uppercase())
            if (!fingerprintHash.isNullOrBlank()) {
                savedFingerprints.add(fingerprintHash)
            }
        }
    }

    fun observedMacHistoryFor(fingerprintHash: String): List<MacSightingRecord> {
        synchronized(lock) {
            return entries[fingerprintHash]
                ?.observedMacs
                ?.values
                ?.sortedByDescending { it.lastSeenElapsedMs }
                ?: emptyList()
        }
    }

    fun observedMacCountFor(fingerprintHash: String): Int {
        synchronized(lock) {
            return entries[fingerprintHash]?.observedMacs?.size ?: 1
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    fun ingest(
        measurement: RfMeasurement,
        userPose: UserPose,
        calibration: CalibrationParams
    ) {
        val fp = measurement.fingerprintHash.ifBlank { measurement.macAddress }
        val sanitizedRssi = RssiKalmanFilter.sanitizeNegativeDbm(measurement.rssiDbm.toDouble())
        val rssiInt = sanitizedRssi.toInt()
        val txPower0x0A = measurement.bleDecoded?.advertisedTxPowerDbm
        val calibratedP0FromAd = txPower0x0A?.let { tx ->
            CalibrationPresetApplier.applyBleTxPowerOverride(calibration, tx).txPowerP0Dbm.toDouble()
        }
        val macUpper = measurement.macAddress.uppercase()

        synchronized(lock) {
            val existing = entries[fp]
            if (existing == null) {
                val created = TrackedEntry(
                    fingerprintHash = fp,
                    latestMacAddress = macUpper,
                    displayName = measurement.displayName,
                    transport = measurement.transport,
                    isRttResponder = measurement.isRttResponder,
                    maxRssiDbm = rssiInt,
                    lastSeenElapsedMs = measurement.timestampElapsedMs,
                    advertisedTxPowerP0 = calibratedP0FromAd,
                    trackerTag = measurement.bleDecoded?.trackerTag
                )
                created.observedMacs[macUpper] = MacSightingRecord(
                    macAddress = macUpper,
                    lastSeenElapsedMs = measurement.timestampElapsedMs,
                    lastRssiDbm = rssiInt
                )
                created.kalman.update(sanitizedRssi)
                created.sightings.addLast(
                    SightingSample(
                        rssiDbm = rssiInt,
                        headingRad = userPose.headingRad,
                        userX = userPose.xMeters,
                        userY = userPose.yMeters
                    )
                )
                entries[fp] = created
            } else {
                existing.latestMacAddress = macUpper
                existing.observedMacs[macUpper] = MacSightingRecord(
                    macAddress = macUpper,
                    lastSeenElapsedMs = measurement.timestampElapsedMs,
                    lastRssiDbm = rssiInt
                )
                if (measurement.displayName.isNotBlank() &&
                    measurement.displayName != "BLE Emitter" &&
                    measurement.displayName != "Hidden AP"
                ) {
                    existing.displayName = measurement.displayName
                }
                if (measurement.isRttResponder) existing.isRttResponder = true
                if (calibratedP0FromAd != null) existing.advertisedTxPowerP0 = calibratedP0FromAd
                if (measurement.bleDecoded?.trackerTag != null) {
                    existing.trackerTag = measurement.bleDecoded.trackerTag
                }
                existing.lastSeenElapsedMs = measurement.timestampElapsedMs
                existing.kalman.update(sanitizedRssi)
                if (rssiInt > existing.maxRssiDbm) {
                    existing.maxRssiDbm = rssiInt
                }
                existing.sightings.addLast(
                    SightingSample(
                        rssiDbm = rssiInt,
                        headingRad = userPose.headingRad,
                        userX = userPose.xMeters,
                        userY = userPose.yMeters
                    )
                )
                while (existing.sightings.size > MAX_SIGHTINGS_PER_EMITTER) {
                    existing.sightings.removeFirst()
                }
            }
        }
    }

    fun snapshot(
        nowElapsedMs: Long,
        userPose: UserPose,
        calibration: CalibrationParams,
        lockedMac: String? = null
    ): List<BackgroundEmitter> {
        synchronized(lock) {
            val result = ArrayList<BackgroundEmitter>(entries.size)
            val iter = entries.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next().value
                val isPinnedByLog = entry.fingerprintHash in savedFingerprints ||
                    entry.observedMacs.keys.any { it in savedMacs }
                val isLocked = lockedMac != null &&
                    (entry.latestMacAddress == lockedMac || lockedMac.uppercase() in entry.observedMacs)
                if (!isLocked && !isPinnedByLog && (nowElapsedMs - entry.lastSeenElapsedMs) > EXPIRY_MS) {
                    iter.remove()
                    continue
                }

                val smoothedRssi = entry.kalman.currentEstimateDbm()
                val effectiveP0 = entry.advertisedTxPowerP0 ?: calibration.txPowerP0Dbm.toDouble()
                val estDist = estimateDistanceMeters(
                    kalmanRssiDbm = smoothedRssi,
                    txPowerP0Dbm = effectiveP0,
                    pathLossExponentN = calibration.pathLossExponentN.toDouble()
                )

                val threshold = entry.maxRssiDbm - STRONG_SIGHTING_MARGIN_DB
                val strongSightings = entry.sightings.filter { it.rssiDbm >= threshold }
                val strongAngles = strongSightings.map { it.headingRad }

                val spreadDeg = computeCircularSpreadDegrees(strongAngles)
                val meanBearing = computeCircularMeanRad(strongAngles)

                val anchorX = if (strongSightings.isNotEmpty()) {
                    strongSightings.map { it.userX }.average()
                } else {
                    userPose.xMeters
                }
                val anchorY = if (strongSightings.isNotEmpty()) {
                    strongSightings.map { it.userY }.average()
                } else {
                    userPose.yMeters
                }

                val estX = (anchorX + estDist * cos(meanBearing))
                    .coerceIn(0.5, BayesianGridFilter.ARENA_SIZE_METERS - 0.5)
                val estY = (anchorY + estDist * sin(meanBearing))
                    .coerceIn(0.5, BayesianGridFilter.ARENA_SIZE_METERS - 0.5)

                val stage = classifyShapeStage(
                    angularSpreadDeg = spreadDeg,
                    estimatedDistanceMeters = estDist.toFloat(),
                    isSavedInLog = isPinnedByLog
                )

                result.add(
                    BackgroundEmitter(
                        fingerprintHash = entry.fingerprintHash,
                        latestMacAddress = entry.latestMacAddress,
                        observedMacCount = entry.observedMacs.size,
                        displayName = entry.displayName,
                        transport = entry.transport,
                        isRttResponder = entry.isRttResponder,
                        kalmanRssiDbm = smoothedRssi.toFloat(),
                        maxRssiDbm = entry.maxRssiDbm,
                        estimatedDistanceMeters = estDist.toFloat(),
                        estimatedX = estX.toFloat(),
                        estimatedY = estY.toFloat(),
                        peakBearingRad = meanBearing,
                        angularSpreadDeg = spreadDeg,
                        shapeStage = stage,
                        isSavedInLog = isPinnedByLog,
                        trackerTag = entry.trackerTag,
                        lastSeenElapsedMs = entry.lastSeenElapsedMs
                    )
                )
            }
            return result.sortedByDescending { it.kalmanRssiDbm }
        }
    }
}
