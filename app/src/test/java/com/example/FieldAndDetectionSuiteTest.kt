package com.example

import com.example.data.WatchlistRepository
import com.example.dsp.AngleMath
import com.example.dsp.BackgroundEmitterTracker
import com.example.dsp.BayesianGridFilter
import com.example.dsp.BleDecodedField
import com.example.dsp.BleDecodedPacket
import com.example.dsp.CalibrationParams
import com.example.dsp.DeviceFingerprintEngine
import com.example.dsp.DroneDetectorEngine
import com.example.dsp.EmitterTransport
import com.example.dsp.EvilTwinDetector
import com.example.dsp.DiscoveredEmitter
import com.example.dsp.GridSnapshot
import com.example.dsp.PerimeterGuardEngine
import com.example.dsp.PerimeterScope
import com.example.dsp.PerimeterTriggerMode
import com.example.dsp.Point2D
import com.example.dsp.RfMeasurement
import com.example.dsp.SigintForensics
import com.example.dsp.SweepCoachEngine
import com.example.dsp.SweepStage
import com.example.dsp.UserPose
import com.example.dsp.WatchlistEntry
import com.example.dsp.WatchlistMatchType
import com.example.sensors.CellRadioGeneration
import com.example.sensors.CellTowerInfo
import com.example.sensors.CellularMonitor
import com.example.sensors.ImuDeadReckoningEngine
import com.example.ui.RadarViewportMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FieldAndDetectionSuiteTest {

    @Test
    fun signed350SpinRejectsWobbleAndCompletesOnTrueRotation() {
        val coach = SweepCoachEngine()
        val startPose = UserPose(xMeters = 25.0, yMeters = 25.0, headingRad = 0.0)
        val uniform = GridSnapshot.uniform()
        coach.startForLockedTarget(startPose)

        // Simulate +/- 15 deg hand wobble 40 times: net yaw remains ~0
        for (i in 0 until 40) {
            val wobbleDeg = if (i % 2 == 0) 15.0 else -15.0
            val state = coach.update(
                userPose = startPose.copy(headingRad = Math.toRadians(wobbleDeg)),
                lockedTargetRssiDbm = -60,
                gridSnapshot = uniform
            )
            assertEquals(SweepStage.SPIN_1, state.stage)
            assertTrue("Wobble should not accumulate >20 deg", state.spinCoverageDegrees <= 20)
        }

        // Now perform a true 355 deg CCW rotation in 30-deg increments
        var currentDeg = 0.0
        var lastState = coach.currentState(startPose)
        for (step in 1..12) {
            currentDeg += 30.0
            val rad = AngleMath.wrapAngle(Math.toRadians(currentDeg))
            lastState = coach.update(
                userPose = startPose.copy(headingRad = rad),
                lockedTargetRssiDbm = if (step == 3) -42 else -65,
                gridSnapshot = uniform
            )
        }
        assertEquals(SweepStage.WALK_PERPENDICULAR, lastState.stage)

        // Walk 5.5m to Anchor B -> enters SPIN_2, then lock immediately if CEP95 <= 1.5m
        val anchorBPose = startPose.copy(xMeters = 30.5, yMeters = 25.0, headingRad = 0.0)
        val spin2State = coach.update(anchorBPose, -50, uniform)
        assertEquals(SweepStage.SPIN_2, spin2State.stage)

        val converged = uniform.copy(cep95Meters = 1.2f, updateCount = 8L)
        val lockedState = coach.update(anchorBPose, -50, converged)
        assertEquals(SweepStage.LOCKED, lockedState.stage)
    }

    @Test
    fun magneticDrift6uTAnd60DegTiltGyroCoasting() {
        val baseline = 53.5f
        val clean = ImuDeadReckoningEngine.computeMagneticStatus(53.5f, 0f, 0f, baselineUt = baseline)
        assertFalse(clean.isMagneticWarped)

        val drifted = ImuDeadReckoningEngine.computeMagneticStatus(60.5f, 0f, 0f, baselineUt = baseline)
        assertTrue(drifted.isMagneticWarped)
        assertEquals(7.0f, drifted.driftDeltaMicroTesla, 0.01f)

        // Phone pitched 60 deg upright around X-axis:
        // World +Up in device coords = (0, sin(60°), cos(60°)) = (R[6]=0, R[7]=0.866025, R[8]=0.5)
        val pitchRad = Math.toRadians(60.0)
        val rotMatrix = floatArrayOf(
            1f, 0f, 0f,
            0f, cos(pitchRad).toFloat(), (-sin(pitchRad)).toFloat(),
            0f, sin(pitchRad).toFloat(), cos(pitchRad).toFloat()
        )
        // Pure horizontal CCW body rotation of 1.0 rad/s splits across device Y and Z:
        val wx = 0f
        val wy = sin(pitchRad).toFloat()
        val wz = cos(pitchRad).toFloat()
        val worldYawRate = AngleMath.computeWorldVerticalYawRate(wx, wy, wz, rotMatrix)
        assertEquals(1.0, worldYawRate, 1e-4)
    }

    @Test
    fun dynamicOriginGridConvergesBeyond50m() {
        val grid = BayesianGridFilter()
        // Operator walked to (120.0m, 85.0m)
        val userX = 120.0
        val userY = 85.0
        grid.resetToUniform(centerX = userX, centerY = userY)

        // Target is at (130.0m, 95.0m) -> distance = hypot(10, 10) = 14.142m
        val targetX = 130.0
        val targetY = 95.0
        val d1 = kotlin.math.hypot(targetX - userX, targetY - userY)
        grid.updateWithRtt(userX, userY, d1, sigmaRttMeters = 0.8)

        // Move 8m East to (128.0m, 85.0m) -> distance = hypot(2, 10) = 10.198m
        val user2X = 128.0
        val user2Y = 85.0
        val d2 = kotlin.math.hypot(targetX - user2X, targetY - user2Y)
        grid.updateWithRtt(user2X, user2Y, d2, sigmaRttMeters = 0.8)

        // Move 6m North to (128.0m, 91.0m) -> distance = hypot(2, 4) = 4.472m
        val user3X = 128.0
        val user3Y = 91.0
        val d3 = kotlin.math.hypot(targetX - user3X, targetY - user3Y)
        grid.updateWithRtt(user3X, user3Y, d3, sigmaRttMeters = 0.8)

        val snap = grid.createSnapshot(user3X, user3Y)
        assertEquals(95.0f, snap.gridOriginX, 0.01f)
        assertEquals(60.0f, snap.gridOriginY, 0.01f)
        assertEquals(130.0f, snap.estimatedTargetX, 1.5f)
        assertEquals(95.0f, snap.estimatedTargetY, 1.5f)
    }

    @Test
    fun followMeViewportTransformCentersUserAtArbitraryCoordinates() {
        val (px, py) = RadarViewportMath.worldToScreenPx(
            wx = 142.5,
            wy = -38.0,
            cameraCenterX = 142.5,
            cameraCenterY = -38.0,
            viewportWidthMeters = 50f,
            canvasWidthPx = 1000f,
            canvasHeightPx = 1000f
        )
        assertEquals(500f, px, 0.01f)
        assertEquals(500f, py, 0.01f)

        // 10m East at 25m viewport width on 1000px canvas -> +400px
        val (eastPx, _) = RadarViewportMath.worldToScreenPx(
            wx = 152.5,
            wy = -38.0,
            cameraCenterX = 142.5,
            cameraCenterY = -38.0,
            viewportWidthMeters = 25f,
            canvasWidthPx = 1000f,
            canvasHeightPx = 1000f
        )
        assertEquals(900f, eastPx, 0.01f)
    }

    @Test
    fun purgeScansClearsBackgroundTrackerIndependently() {
        val tracker = BackgroundEmitterTracker()
        val pose = UserPose()
        val cal = CalibrationParams()
        tracker.ingest(
            RfMeasurement(
                macAddress = "AA:BB:CC:11:22:33",
                displayName = "TestAP",
                transport = EmitterTransport.WIFI,
                rssiDbm = -55,
                timestampElapsedMs = 1000L
            ),
            pose,
            cal
        )
        assertEquals(1, tracker.snapshot(1000L, pose, cal, null, null).size)
        tracker.clear()
        assertEquals(0, tracker.snapshot(1000L, pose, cal, null, null).size)
    }

    @Test
    fun droneDetectorRequiresStrictSignaturesAndRejectsNormalRouters() {
        val blePacket = BleDecodedPacket.EMPTY.copy(
            adTypes = listOf(0x01, 0x16),
            serviceUuids = listOf("FFFA"),
            fields = listOf(BleDecodedField("0x16", "Service Data (0xFFFA)", "OpenDroneID"))
        )
        val bleRes = DroneDetectorEngine.detect("12:34:56:78:9A:BC", EmitterTransport.BLE, bleDecoded = blePacket)
        assertTrue(bleRes.isDrone)
        assertEquals("ASTM F3411 BLE", bleRes.protocolLabel)

        val wifiOdId = DroneDetectorEngine.detect(
            macAddress = "11:22:33:44:55:66",
            transport = EmitterTransport.WIFI,
            wifiVendorOuiHexes = listOf("FA:0B:BC:0D")
        )
        assertTrue(wifiOdId.isDrone)
        assertEquals("ASTM F3411 Wi-Fi", wifiOdId.protocolLabel)

        val djiIe = DroneDetectorEngine.detect(
            macAddress = "11:22:33:44:55:66",
            transport = EmitterTransport.WIFI,
            wifiVendorOuiHexes = listOf("26:37:12")
        )
        assertTrue(djiIe.isDrone)
        assertEquals("DJI DroneID", djiIe.protocolLabel)

        // Verified Skydio OUI 38:1D:14 with Skydio SSID -> Drone
        val skydio = DroneDetectorEngine.detect(
            macAddress = "38:1D:14:AA:BB:CC",
            transport = EmitterTransport.WIFI,
            displayName = "Skydio2_1234"
        )
        assertTrue(skydio.isDrone)

        // Known OUI without drone SSID -> NOT a drone (prevents false positives)
        val nonDroneOui = DroneDetectorEngine.detect(
            macAddress = "60:60:1F:AA:BB:CC",
            transport = EmitterTransport.WIFI,
            displayName = "HomeRouter_5G"
        )
        assertFalse(nonDroneOui.isDrone)

        // Normal router -> NOT a drone
        val normalRouter = DroneDetectorEngine.detect(
            macAddress = "10:78:5B:F4:89:12",
            transport = EmitterTransport.WIFI,
            displayName = "TELUS3690"
        )
        assertFalse(normalRouter.isDrone)

        // 3D slant-to-ground: 50m slant at 30m altitude -> 40m ground
        assertEquals(40.0, DroneDetectorEngine.slantToGroundDistance(50.0, 30.0), 0.01)
    }

    @Test
    fun cellTowerCacheRetainsNeighborsAcrossPartialSweepsAndEvictsAfter35s() {
        val cache = LinkedHashMap<CellularMonitor.Companion.CellTowerKey, CellularMonitor.Companion.CachedCellTower>()
        val serving = CellTowerInfo(
            generation = CellRadioGeneration.NR_5G,
            isServing = true,
            pci = 101,
            rsrpDbm = -82,
            sinrDb = 14,
            arfcn = 640000,
            timingAdvance = null,
            timingAdvanceDistanceMeters = null,
            plmn = "302-220"
        )
        val neighbor = CellTowerInfo(
            generation = CellRadioGeneration.LTE_4G,
            isServing = false,
            pci = 202,
            rsrpDbm = -95,
            sinrDb = 5,
            arfcn = 5255,
            timingAdvance = null,
            timingAdvanceDistanceMeters = null,
            plmn = "302-220"
        )

        // T=1000ms: both serving + neighbor present
        val t1 = CellularMonitor.mergeCellTowerCache(cache, listOf(serving, neighbor), 1_000L)
        assertEquals(2, t1.size)

        // T=5000ms: baseband returns serving-only partial sweep -> neighbor still retained!
        val t2 = CellularMonitor.mergeCellTowerCache(cache, listOf(serving), 5_000L)
        assertEquals(2, t2.size)

        // T=37000ms (>35s since neighbor seen): neighbor evicted
        val t3 = CellularMonitor.mergeCellTowerCache(cache, listOf(serving), 37_000L)
        assertEquals(1, t3.size)
        assertEquals(101, t3.first().pci)
    }

    @Test
    fun backgroundTrackerProjectsBeyond50mWithoutClamping() {
        val tracker = BackgroundEmitterTracker()
        val pose = UserPose(xMeters = 100.0, yMeters = 100.0)
        val cal = CalibrationParams(txPowerP0Dbm = -45f, pathLossExponentN = 2.0f)
        // RSSI = -85 dBm at P0=-45, n=2.0 -> d = 10^((-45 - (-85))/20) = 100m
        tracker.ingest(
            RfMeasurement(
                macAddress = "AA:BB:CC:11:22:33",
                displayName = "FarAP",
                transport = EmitterTransport.WIFI,
                rssiDbm = -85,
                timestampElapsedMs = 1_000L
            ),
            pose,
            cal
        )
        val bg = tracker.snapshot(1_000L, pose, cal, null, null).first()
        assertTrue("Distance should project > 50m without clamping", bg.estimatedDistanceMeters > 80f)
        assertTrue("Coordinates should not clamp to [0..50m]", bg.estimatedX > 50f || bg.estimatedY > 50f)
    }

    @Test
    fun safeRpaFingerprintCollapsesHighEntropyRpaButKeepsBarebonesBeaconsSeparate() {
        // Barebones beacon (only flags 0x01) with two different RPA MACs -> must NOT collapse
        val barebones = BleDecodedPacket.EMPTY.copy(
            adTypes = listOf(0x01),
            rawHexDump = "020106"
        )
        val fp1 = DeviceFingerprintEngine.fingerprintBle("52:11:22:33:44:55", 1, barebones)
        val fp2 = DeviceFingerprintEngine.fingerprintBle("62:AA:BB:CC:DD:EE", 1, barebones)
        assertTrue(fp1.startsWith("BLE_MAC:"))
        assertTrue(fp2.startsWith("BLE_MAC:"))
        assertFalse(fp1 == fp2)

        // High-entropy RPA beacon (>= 6 bytes payload + static manufacturer header) -> MUST collapse
        val richPacket = BleDecodedPacket.EMPTY.copy(
            adTypes = listOf(0x01, 0xFF),
            companyId = 0x004C,
            staticManufacturerHeaderHex = "4C00121910",
            rawHexDump = "02011A1BFF4C00121910112233445566"
        )
        val rich1 = DeviceFingerprintEngine.fingerprintBle("52:11:22:33:44:55", 1, richPacket)
        val rich2 = DeviceFingerprintEngine.fingerprintBle("62:AA:BB:CC:DD:EE", 1, richPacket)
        assertTrue(rich1.startsWith("BLE_FP:"))
        assertEquals(rich1, rich2)
    }

    @Test
    fun sigintTimingStatsAndTailChaseScoring() {
        val stats = SigintForensics.computeTimingStats(
            intervalsMs = listOf(102.4, 102.5, 102.3, 102.6, 102.4),
            isWifi = true
        )
        assertEquals(102.44f, stats.meanDeltaMs, 0.1f)
        assertTrue(stats.jitterSigmaMs < 1.0f)

        val clearScore = SigintForensics.computeTailChaseScore(
            strongSightingPositions = listOf(Point2D(0.0, 0.0), Point2D(5.0, 0.0)),
            operatorWalkedMeters = 10.0
        )
        assertEquals(0, clearScore)

        val tailScore = SigintForensics.computeTailChaseScore(
            strongSightingPositions = listOf(
                Point2D(0.0, 0.0),
                Point2D(25.0, 0.0),
                Point2D(55.0, 0.0)
            ),
            operatorWalkedMeters = 60.0
        )
        assertTrue("Expected elevated tail-chase score > 50, got $tailScore", tailScore > 50)
    }

    @Test
    fun watchlistMatchesOuiSubstringAndFingerprint() {
        val entries = listOf(
            WatchlistEntry(1L, WatchlistMatchType.OUI, "60:60:1F", "DJI Mavic"),
            WatchlistEntry(2L, WatchlistMatchType.SUBSTRING, "CoffeeShop", "Rogue AP"),
            WatchlistEntry(3L, WatchlistMatchType.FINGERPRINT, "BLE_FP:A1B2C3", "Tracker")
        )
        assertNotNull(WatchlistRepository.matchEntries(entries, "60:60:1F:01:02:03", "Drone", "WIFI:60:60:1F:01:02:03"))
        assertNotNull(WatchlistRepository.matchEntries(entries, "11:22:33:44:55:66", "MyCoffeeShop_5G", "WIFI:11"))
        assertNotNull(WatchlistRepository.matchEntries(entries, "44:55:66:77:88:99", "BLE Emitter", "BLE_FP:A1B2C3"))
        assertNull(WatchlistRepository.matchEntries(entries, "00:11:22:33:44:55", "HomeWiFi", "WIFI:00"))
    }

    @Test
    fun perimeterGuardEnforces3PacketHysteresis() {
        val guard = PerimeterGuardEngine().apply {
            enabled = true
            radiusMeters = 10.0f
            triggerMode = PerimeterTriggerMode.BOTH
            scope = PerimeterScope.ALL_UNKNOWN
        }

        // First 2 packets inside: no event yet
        assertNull(guard.evaluatePacket("K1", "Target", 6.0, -55, false, false, 100L))
        assertNull(guard.evaluatePacket("K1", "Target", 7.0, -56, false, false, 200L))
        // 3rd consecutive packet inside -> ENTER!
        val enter = guard.evaluatePacket("K1", "Target", 5.5, -54, false, false, 300L)
        assertNotNull(enter)
        assertEquals("ENTER", enter?.eventType)

        // 1 spike outside, then back inside -> no LEAVE
        assertNull(guard.evaluatePacket("K1", "Target", 15.0, -75, false, false, 400L))
        assertNull(guard.evaluatePacket("K1", "Target", 8.0, -60, false, false, 500L))

        // 3 consecutive packets outside -> LEAVE!
        assertNull(guard.evaluatePacket("K1", "Target", 14.0, -74, false, false, 600L))
        assertNull(guard.evaluatePacket("K1", "Target", 16.0, -76, false, false, 700L))
        val leave = guard.evaluatePacket("K1", "Target", 18.0, -78, false, false, 800L)
        assertNotNull(leave)
        assertEquals("LEAVE", leave?.eventType)
    }

    @Test
    fun evilTwinFlagsMismatchedSecurityForSameSsid() {
        val legAp = DiscoveredEmitter(
            macAddress = "AA:BB:CC:00:00:01",
            displayName = "CorpWiFi",
            transport = EmitterTransport.WIFI,
            isRttResponder = false,
            rssiDbm = -50,
            packetRateHz = 1f,
            lastSeenElapsedMs = 1000L,
            wifiCapabilities = "[WPA2-PSK-CCMP][ESS]"
        )
        val rogueAp = DiscoveredEmitter(
            macAddress = "DE:AD:BE:EF:00:02",
            displayName = "CorpWiFi",
            transport = EmitterTransport.WIFI,
            isRttResponder = false,
            rssiDbm = -45,
            packetRateHz = 1f,
            lastSeenElapsedMs = 1000L,
            wifiCapabilities = "[ESS]"
        )
        val flagged = EvilTwinDetector.flagEvilTwins(listOf(legAp, rogueAp))
        assertTrue(flagged[0].isEvilTwinSuspect)
        assertTrue(flagged[1].isEvilTwinSuspect)
    }
}
