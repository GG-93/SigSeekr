package com.example

import com.example.dsp.BackgroundEmitterTracker
import com.example.dsp.BleAdDecoder
import com.example.dsp.BleTrackerTag
import com.example.dsp.CalibrationParams
import com.example.dsp.CalibrationPresetApplier
import com.example.dsp.DeviceFingerprintEngine
import com.example.dsp.EmitterClassPreset
import com.example.dsp.EmitterShapeStage
import com.example.dsp.EmitterTransport
import com.example.dsp.EnvironmentPreset
import com.example.dsp.GridSnapshot
import com.example.dsp.LocalizationTier
import com.example.dsp.RfMeasurement
import com.example.dsp.RssiKalmanFilter
import com.example.dsp.ShieldPreset
import com.example.dsp.SweepCoachEngine
import com.example.dsp.SweepStage
import com.example.dsp.UserPose
import com.example.maps.OfflineMapTileManager
import com.example.sensors.CellRadioGeneration
import com.example.sensors.CellularMonitor
import com.example.sensors.GnssInterferenceAnalyzer
import com.example.sensors.GnssInterferenceLevel
import com.example.sensors.ImuDeadReckoningEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class Part1EnginesTest {

    @Test
    fun calibrationPresets_andBle0x0ATxPowerAutoOverride() {
        var params = CalibrationParams()
        assertEquals(0.0f, CalibrationParams.MIN_SHIELD_DB, 1e-4f)
        assertEquals(30.0f, CalibrationParams.MAX_SHIELD_DB, 1e-4f)

        params = CalibrationPresetApplier.applyShield(params, ShieldPreset.OMNI_HANDHELD)
        assertEquals(0.0f, params.shieldDropDb, 1e-4f)
        assertEquals(ShieldPreset.POCKET_BODY, ShieldPreset.OMNI_HANDHELD.nextQuickCycle())

        params = CalibrationPresetApplier.applyShield(params, ShieldPreset.POCKET_BODY)
        assertEquals(16.0f, params.shieldDropDb, 1e-4f)
        assertEquals(ShieldPreset.CORNER_REFLECTOR_90, ShieldPreset.POCKET_BODY.nextQuickCycle())

        params = CalibrationPresetApplier.applyShield(params, ShieldPreset.CORNER_REFLECTOR_90)
        assertEquals(27.0f, params.shieldDropDb, 1e-4f)
        assertEquals(ShieldPreset.OMNI_HANDHELD, ShieldPreset.CORNER_REFLECTOR_90.nextQuickCycle())

        params = CalibrationPresetApplier.applyEmitterClass(params, EmitterClassPreset.BLE_TRACKER)
        assertEquals(-58.0f, params.txPowerP0Dbm, 1e-4f)

        params = CalibrationPresetApplier.applyEnvironment(params, EnvironmentPreset.DENSE_CONCRETE)
        assertEquals(3.4f, params.pathLossExponentN, 1e-4f)
        assertEquals(0.62f, params.stepLengthMeters, 1e-4f)

        // BLE 0x0A explicit TX power (+4 dBm at 0m -> -37 dBm at 1m)
        val overridden0m = CalibrationPresetApplier.applyBleTxPowerOverride(params, 4)
        assertEquals(-37.0f, overridden0m.txPowerP0Dbm, 1e-4f)

        // BLE 0x0A calibrated 1m value (-59 dBm directly)
        val overridden1m = CalibrationPresetApplier.applyBleTxPowerOverride(params, -59)
        assertEquals(-59.0f, overridden1m.txPowerP0Dbm, 1e-4f)
    }

    @Test
    fun bleAdDecoder_parsesAppleSeparatedFindMy_txPower_andStrictlyNegativeKalman() {
        // Construct raw BLE AD stream:
        // [0x02, 0x01, 0x06] -> Flags
        // [0x02, 0x0A, 0xC5] -> TX Power 0x0A = -59 dBm (signed byte 0xC5)
        // [0x1E, 0xFF, 0x4C, 0x00, 0x12, 0x19, 0x10, <24 rolling bytes>] -> Apple Find My Separated
        val payload = ByteArray(3 + 3 + 31)
        payload[0] = 0x02; payload[1] = 0x01; payload[2] = 0x06
        payload[3] = 0x02; payload[4] = 0x0A; payload[5] = (-59).toByte()
        payload[6] = 30; payload[7] = 0xFF.toByte()
        payload[8] = 0x4C; payload[9] = 0x00 // Apple 0x004C little-endian
        payload[10] = 0x12 // Find My
        payload[11] = 0x19 // 25 bytes (Separated tracker)
        payload[12] = 0x10 // Status byte
        for (i in 13 until payload.size) payload[i] = (i * 7).toByte()

        val decoded = BleAdDecoder.parse(payload)
        assertEquals(-59, decoded.advertisedTxPowerDbm)
        assertEquals(0x004C, decoded.companyId)
        assertEquals(BleTrackerTag.APPLE_FIND_MY_SEPARATED, decoded.trackerTag)
        assertTrue(decoded.rawHexDump.startsWith("02 01 06 02 0A C5"))

        // Fingerprint collapses rotating RPA MACs with different rolling key bytes
        val payloadRotated = payload.copyOf()
        for (i in 13 until payloadRotated.size) payloadRotated[i] = (0xFF - i).toByte()
        val decodedRotated = BleAdDecoder.parse(payloadRotated)

        val fp1 = DeviceFingerprintEngine.fingerprintBle(
            "4E:11:22:33:44:55",
            DeviceFingerprintEngine.ADDRESS_TYPE_RANDOM,
            decoded
        )
        val fp2 = DeviceFingerprintEngine.fingerprintBle(
            "6A:99:88:77:66:55",
            DeviceFingerprintEngine.ADDRESS_TYPE_RANDOM,
            decodedRotated
        )
        assertTrue(fp1.startsWith("BLE_FP:"))
        assertEquals(fp1, fp2)

        // Wi-Fi APs with distinct BSSIDs never merge
        assertNotEquals(
            DeviceFingerprintEngine.fingerprintWifi("AA:BB:CC:DD:EE:01"),
            DeviceFingerprintEngine.fingerprintWifi("AA:BB:CC:DD:EE:02")
        )

        // Kalman RSSI filter strictly remains negative dBm even on bad input
        val kalman = RssiKalmanFilter()
        assertTrue(kalman.update(15.0) <= -1.0)
        assertTrue(kalman.update(-62.0) < 0.0)
        assertTrue(kalman.currentEstimateDbm() <= -1.0)
    }

    @Test
    fun backgroundEmitterTracker_classifiesAllSixStagesAndWrapAroundSpread() {
        assertEquals(EmitterShapeStage.SEMICIRCLE, BackgroundEmitterTracker.classifyShapeStage(145f, 6f, false))
        assertEquals(EmitterShapeStage.TRIANGLE, BackgroundEmitterTracker.classifyShapeStage(105f, 6f, false))
        assertEquals(EmitterShapeStage.SQUARE, BackgroundEmitterTracker.classifyShapeStage(60f, 6f, false))
        assertEquals(EmitterShapeStage.PENTAGON, BackgroundEmitterTracker.classifyShapeStage(32f, 6f, false))
        assertEquals(EmitterShapeStage.HEXAGON, BackgroundEmitterTracker.classifyShapeStage(15f, 6f, false))
        assertEquals(EmitterShapeStage.PINNED, BackgroundEmitterTracker.classifyShapeStage(150f, 0.8f, false))
        assertEquals(EmitterShapeStage.PINNED, BackgroundEmitterTracker.classifyShapeStage(150f, 8.0f, true))

        // Wrap-around across ±pi (+175 deg and -175 deg -> true circular spread is 10 deg, not 350 deg)
        val spreadWrap = BackgroundEmitterTracker.computeCircularSpreadDegrees(
            listOf(Math.toRadians(175.0), Math.toRadians(-175.0))
        )
        assertEquals(10.0f, spreadWrap, 0.2f)

        // Verify tracker groups randomized MACs under single fingerprintHash
        val tracker = BackgroundEmitterTracker()
        val cal = CalibrationParams(shieldDropDb = 16.0f)
        tracker.ingest(
            RfMeasurement(
                macAddress = "4E:00:00:00:00:01",
                displayName = "AirTag",
                transport = EmitterTransport.BLE,
                rssiDbm = -60,
                timestampElapsedMs = 1000L,
                fingerprintHash = "BLE_FP:AABBCCDDEEFF"
            ),
            UserPose(xMeters = 25.0, yMeters = 25.0, headingRad = 0.0),
            cal
        )
        tracker.ingest(
            RfMeasurement(
                macAddress = "6A:00:00:00:00:02",
                displayName = "AirTag",
                transport = EmitterTransport.BLE,
                rssiDbm = -59,
                timestampElapsedMs = 1500L,
                fingerprintHash = "BLE_FP:AABBCCDDEEFF"
            ),
            UserPose(xMeters = 25.0, yMeters = 25.0, headingRad = Math.toRadians(12.0)),
            cal
        )
        // Only 12 deg heading span and 1 dB contrast -> MUST stay SEMICIRCLE (no false Hexagon!)
        val bgNarrow = tracker.snapshot(2000L, UserPose(), cal)
        assertEquals(1, bgNarrow.size)
        assertEquals(2, bgNarrow[0].observedMacCount)
        assertEquals(EmitterShapeStage.SEMICIRCLE, bgNarrow[0].shapeStage)
        assertEquals(2, tracker.observedMacHistoryFor("BLE_FP:AABBCCDDEEFF").size)

        // Sweep across >= 180 deg with >= 4 dB shielded rear null contrast -> promotes to HEXAGON
        val sweepSamples = listOf(
            90.0 to -70,
            180.0 to -78,
            270.0 to -71
        )
        var ts = 2100L
        for ((deg, rssi) in sweepSamples) {
            tracker.ingest(
                RfMeasurement(
                    macAddress = "6A:00:00:00:00:02",
                    displayName = "AirTag",
                    transport = EmitterTransport.BLE,
                    rssiDbm = rssi,
                    timestampElapsedMs = ts,
                    fingerprintHash = "BLE_FP:AABBCCDDEEFF"
                ),
                UserPose(xMeters = 25.0, yMeters = 25.0, headingRad = Math.toRadians(deg)),
                cal
            )
            ts += 200L
        }
        val bgSwept = tracker.snapshot(ts, UserPose(), cal)
        assertEquals(EmitterShapeStage.HEXAGON, bgSwept[0].shapeStage)

        // Immediate fingerprintHash pinning transitions stage directly to PINNED
        tracker.pinTarget("BLE_FP:AABBCCDDEEFF", "6A:00:00:00:00:02")
        val pinnedBg = tracker.snapshot(ts + 100L, UserPose(), cal)
        assertEquals(EmitterShapeStage.PINNED, pinnedBg[0].shapeStage)
        assertTrue(pinnedBg[0].isSavedInLog)

        // Clearing SQLite log unpins fingerprintHash as well as MACs
        tracker.updateSavedLogMacs(emptySet())
        val unpinnedBg = tracker.snapshot(ts + 200L, UserPose(), cal)
        assertFalse(unpinnedBg[0].isSavedInLog)
        assertEquals(EmitterShapeStage.HEXAGON, unpinnedBg[0].shapeStage)
    }

    @Test
    fun backgroundEmitterTracker_stationaryOmniPacketsDoNotStackOrPromoteToHexagon() {
        val tracker = BackgroundEmitterTracker()
        val omniCal = CalibrationParams(shieldDropDb = 0.0f)
        // Stationary phone facing 320 deg NW (azimuth -40 deg -> Cartesian theta = 130 deg)
        val stationaryHeadingRad = Math.toRadians(130.0)
        val stationaryPose = UserPose(
            xMeters = 25.0,
            yMeters = 25.0,
            headingRad = stationaryHeadingRad,
            stepCount = 0
        )

        // Feed 25 distinct emitters x 10 packets each at the exact same stationary heading
        var ts = 1_000L
        for (i in 0 until 25) {
            val bssid = String.format(java.util.Locale.US, "AA:BB:CC:DD:EE:%02X", i)
            val fp = DeviceFingerprintEngine.fingerprintWifi(bssid)
            for (pkt in 0 until 10) {
                tracker.ingest(
                    RfMeasurement(
                        macAddress = bssid,
                        displayName = "AP-$i",
                        transport = EmitterTransport.WIFI,
                        rssiDbm = -58 - (i % 15) - (pkt % 3),
                        timestampElapsedMs = ts,
                        fingerprintHash = fp
                    ),
                    stationaryPose,
                    omniCal
                )
                ts += 50L
            }
        }

        val snap = tracker.snapshot(ts + 100L, stationaryPose, omniCal)
        assertEquals(25, snap.size)

        // 1. ZERO emitters may promote past SEMICIRCLE while stationary in Omni 0dB
        for (emitter in snap) {
            assertEquals(
                "Stationary Omni emitter ${emitter.displayName} falsely promoted to ${emitter.shapeStage}",
                EmitterShapeStage.SEMICIRCLE,
                emitter.shapeStage
            )
            assertEquals(180.0f, emitter.angularSpreadDeg, 1e-3f)
        }

        // 2. Bearings must NOT stack onto the phone's 130 deg heading laser line;
        //    deterministic hash angles must span wide circular azimuth (> 240 deg across 25 APs)
        val plottedBearings = snap.map { it.peakBearingRad }
        val bearingSpan = BackgroundEmitterTracker.computeSampledHeadingSpanDegrees(plottedBearings)
        assertTrue(
            "Expected hash-distributed stationary bearings spanning > 240 deg, was $bearingSpan",
            bearingSpan > 240.0f
        )
        val alignedCount = plottedBearings.count {
            kotlin.math.abs(com.example.dsp.AngleMath.wrapAngle(it - stationaryHeadingRad)) < Math.toRadians(10.0)
        }
        assertTrue(
            "Too many stationary emitters ($alignedCount/25) stacked near phone heading",
            alignedCount < 6
        )
    }

    @Test
    fun sweepCoachEngine_advancesThroughAllFourStagesAndMagneticMonitorFlagsWarp() {
        val coach = SweepCoachEngine()
        var pose = UserPose(xMeters = 25.0, yMeters = 25.0, headingRad = 0.0)
        var state = coach.startForLockedTarget(pose)
        assertEquals(SweepStage.SPIN_1, state.stage)

        val wideSnap = GridSnapshot.uniform().copy(cep95Meters = 8.0f, updateCount = 5L)

        // Spin 280 degrees in 10-degree increments at Anchor A
        for (deg in 0..280 step 10) {
            pose = pose.copy(headingRad = Math.toRadians(deg.toDouble()))
            state = coach.update(pose, -62, wideSnap)
        }
        assertEquals(SweepStage.WALK_PERPENDICULAR, state.stage)

        // Walk 5.5m from Anchor A to Anchor B
        pose = pose.copy(xMeters = 25.0, yMeters = 30.5)
        state = coach.update(pose, -60, wideSnap)
        assertEquals(SweepStage.SPIN_2, state.stage)

        // Spin 280 degrees at Anchor B and converge CEP95 <= 1.5m
        val convergedSnap = wideSnap.copy(
            cep95Meters = 1.1f,
            updateCount = 14L,
            activeTier = LocalizationTier.SHIELD_TIER2
        )
        for (deg in 0..280 step 10) {
            pose = pose.copy(headingRad = Math.toRadians(deg.toDouble()))
            state = coach.update(pose, -55, convergedSnap)
        }
        assertEquals(SweepStage.LOCKED, state.stage)

        // Magnetic anomaly bounds: <25 uT or >70 uT => warped
        assertFalse(ImuDeadReckoningEngine.computeMagneticStatus(25f, 30f, 20f).isMagneticWarped) // ~43.8 uT
        assertTrue(ImuDeadReckoningEngine.computeMagneticStatus(10f, 5f, 5f).isMagneticWarped)    // ~12.2 uT
        assertTrue(ImuDeadReckoningEngine.computeMagneticStatus(60f, 50f, 30f).isMagneticWarped)  // ~83.7 uT
    }

    @Test
    fun offlineMapTileManager_webMercatorRoundTripsGridCoordinatesAccurately() {
        val anchorLat = 37.7749
        val anchorLon = -122.4194

        val (cx, cy) = OfflineMapTileManager.latLonToGridMeters(anchorLat, anchorLon, anchorLat, anchorLon)
        assertEquals(25.0, cx, 1e-4)
        assertEquals(25.0, cy, 1e-4)

        val (lat2, lon2) = OfflineMapTileManager.gridMetersToLatLon(37.5, 14.0, anchorLat, anchorLon)
        val (gx2, gy2) = OfflineMapTileManager.latLonToGridMeters(lat2, lon2, anchorLat, anchorLon)
        assertEquals(37.5, gx2, 0.01)
        assertEquals(14.0, gy2, 0.01)

        val tile = OfflineMapTileManager.latLonToTile(anchorLat, anchorLon, 17)
        assertEquals(17, tile.z)
        assertTrue(tile.x > 0 && tile.y > 0)
    }

    @Test
    fun cellularAndGnssMonitors_detect2gDowngradeJammingAndSpoofing() {
        // LTE Timing Advance distance
        assertEquals(781.2f, CellularMonitor.lteTimingAdvanceToMeters(10)!!, 0.1f)
        // Canadian MCC-MNC carrier lookups & band derivation
        assertEquals("Rogers Wireless (CA)", CellularMonitor.lookupCarrierName("302-720"))
        assertEquals("Telus Mobility (CA)", CellularMonitor.lookupCarrierName("302-220"))
        assertEquals("Bell Mobility (CA)", CellularMonitor.lookupCarrierName("302-610"))
        assertEquals("Freedom Mobile (CA)", CellularMonitor.lookupCarrierName("302-490"))
        assertEquals("B66 (1700/2100 AWS-3)", CellularMonitor.deriveBandLabel(CellRadioGeneration.LTE_4G, 66786, emptyList()))
        assertEquals("n78 (3500 MHz C-Band)", CellularMonitor.deriveBandLabel(CellRadioGeneration.NR_5G, 640000, emptyList()))
        assertEquals("n78", CellularMonitor.deriveBandLabel(CellRadioGeneration.NR_5G, 640000, listOf(78)))
        // 2G downgrade Stingray alert after prior 4G/5G session
        val (alert, _) = CellularMonitor.evaluate2gDowngradeAlert(
            previouslyObservedModernRat = true,
            servingGen = CellRadioGeneration.GSM_2G,
            voiceNetType = null,
            dataNetType = null,
            servingRsrpDbm = -58,
            neighborCount = 0
        )
        assertTrue(alert)

        // GNSS Jamming: AGC drops AND C/N0 drops
        val jamAnalyzer = GnssInterferenceAnalyzer()
        for (i in 0 until 20) {
            jamAnalyzer.pushEpoch(meanAgcDb = 2.0, meanCn0DbHz = 36.0, top3Cn0DbHz = 40.0, satelliteCount = 12, timestampMs = i * 1000L)
        }
        var jamStatus = jamAnalyzer.pushEpoch(meanAgcDb = -4.0, meanCn0DbHz = 22.0, top3Cn0DbHz = 25.0, satelliteCount = 8, timestampMs = 21_000L)
        for (i in 22..32) {
            jamStatus = jamAnalyzer.pushEpoch(meanAgcDb = -4.0, meanCn0DbHz = 22.0, top3Cn0DbHz = 25.0, satelliteCount = 8, timestampMs = i * 1000L)
        }
        assertEquals(GnssInterferenceLevel.JAMMING_SUSPECTED, jamStatus.interferenceLevel)

        // GNSS Spoofing: AGC drops AND C/N0 surges
        val spoofAnalyzer = GnssInterferenceAnalyzer()
        for (i in 0 until 20) {
            spoofAnalyzer.pushEpoch(meanAgcDb = 2.0, meanCn0DbHz = 32.0, top3Cn0DbHz = 35.0, satelliteCount = 10, timestampMs = i * 1000L)
        }
        var spoofStatus = spoofAnalyzer.pushEpoch(meanAgcDb = -4.5, meanCn0DbHz = 43.0, top3Cn0DbHz = 46.0, satelliteCount = 10, timestampMs = 21_000L)
        for (i in 22..32) {
            spoofStatus = spoofAnalyzer.pushEpoch(meanAgcDb = -4.5, meanCn0DbHz = 43.0, top3Cn0DbHz = 46.0, satelliteCount = 10, timestampMs = i * 1000L)
        }
        assertEquals(GnssInterferenceLevel.SPOOFING_SUSPECTED, spoofStatus.interferenceLevel)
    }
}
