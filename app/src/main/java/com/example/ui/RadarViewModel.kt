package com.example.ui

import android.app.Application
import android.net.Uri
import android.net.wifi.ScanResult
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ExportFormat
import com.example.data.SessionLogRepository
import com.example.dsp.ActivePresetState
import com.example.dsp.BackgroundEmitter
import com.example.dsp.BackgroundEmitterTracker
import com.example.dsp.BayesianGridFilter
import com.example.dsp.BleDecodedPacket
import com.example.dsp.CalibrationParams
import com.example.dsp.CalibrationPresetApplier
import com.example.dsp.DiscoveredEmitter
import com.example.dsp.EmitterClassPreset
import com.example.dsp.EmitterDossierData
import com.example.dsp.EmitterTransport
import com.example.dsp.EnvironmentPreset
import com.example.dsp.GridSnapshot
import com.example.dsp.HardwareStatus
import com.example.dsp.MagneticFieldStatus
import com.example.dsp.PowerMode
import com.example.dsp.RfMeasurement
import com.example.dsp.RssiKalmanFilter
import com.example.dsp.RssiSamplePoint
import com.example.dsp.SessionSnapshotRecord
import com.example.dsp.ShieldPreset
import com.example.dsp.SweepCoachEngine
import com.example.dsp.SweepCoachState
import com.example.dsp.UserPose
import com.example.maps.OfflineMapTileManager
import com.example.maps.TileCacheState
import com.example.sensors.CellularMonitor
import com.example.sensors.CellularSnapshot
import com.example.sensors.Esp32UsbBridge
import com.example.sensors.GeigerFeedbackEngine
import com.example.sensors.GnssRfMonitor
import com.example.sensors.GnssRfStatus
import com.example.sensors.ImuDeadReckoningEngine
import com.example.sensors.RfDataSource
import com.example.sensors.StandaloneRadioDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class RadarUiState(
    val hasPermissions: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
    val hardwareStatus: HardwareStatus = HardwareStatus(),
    val userPose: UserPose = UserPose(),
    val magneticStatus: MagneticFieldStatus = MagneticFieldStatus(),
    val gridSnapshot: GridSnapshot = GridSnapshot.uniform(),
    val calibration: CalibrationParams = CalibrationParams(),
    val activePresets: ActivePresetState = ActivePresetState(),
    val powerMode: PowerMode = PowerMode.TURBO,
    val mapOverlayEnabled: Boolean = true,
    val discoveredEmitters: List<DiscoveredEmitter> = emptyList(),
    val backgroundEmitters: List<BackgroundEmitter> = emptyList(),
    val sweepCoachState: SweepCoachState = SweepCoachState(),
    val tileCacheState: TileCacheState = TileCacheState(),
    val cellularSnapshot: CellularSnapshot = CellularSnapshot(),
    val gnssRfStatus: GnssRfStatus = GnssRfStatus(),
    val lockedTargetMac: String? = null,
    val lockedTargetFingerprint: String? = null,
    val lockedTargetName: String? = null,
    val lockedTargetTransport: EmitterTransport? = null,
    val lockedTargetIsRtt: Boolean = false,
    val lockedTargetLastRssiDbm: Int? = null,
    val lockedTargetKalmanRssiDbm: Float? = null,
    val lockedTargetLastRttMeters: Double? = null,
    val lockedTargetPacketRateHz: Float = 0f,
    val lockedTargetIsStale: Boolean = false,
    val lockedTargetBleDecoded: BleDecodedPacket? = null,
    val sessionMinDistanceMeters: Float? = null,
    val sessionMaxRssiDbm: Int? = null,
    val isGeigerEnabled: Boolean = false,
    val geigerPulseRateHz: Float = 0f,
    val savedSnapshots: List<SessionSnapshotRecord> = emptyList(),
    val activeDossier: EmitterDossierData? = null
)

private class EmitterRecord(
    val macAddress: String,
    var fingerprintHash: String,
    var displayName: String,
    var transport: EmitterTransport,
    var isRttResponder: Boolean,
    var lastRssiDbm: Int,
    var lastSeenElapsedMs: Long,
    var scanResult: ScanResult?,
    var bleDecoded: BleDecodedPacket?,
    val kalman: RssiKalmanFilter = RssiKalmanFilter(),
    val packetTimestampsMs: ArrayDeque<Long> = ArrayDeque(),
    val rssiHistory: ArrayDeque<RssiSamplePoint> = ArrayDeque(),
    val interArrivalIntervalsMs: ArrayDeque<Long> = ArrayDeque()
)

class RadarViewModel(
    application: Application,
    private val standaloneSource: StandaloneRadioDataSource,
    private val optionalUsbSource: RfDataSource,
    private val imuEngine: ImuDeadReckoningEngine,
    private val bayesianGridFilter: BayesianGridFilter,
    private val backgroundEmitterTracker: BackgroundEmitterTracker,
    private val sweepCoachEngine: SweepCoachEngine,
    val offlineMapTileManager: OfflineMapTileManager,
    private val sessionLogRepository: SessionLogRepository
) : AndroidViewModel(application) {

    companion object {
        private const val RATE_WINDOW_MS = 4_000L
        private const val EMITTER_EXPIRY_MS = 35_000L
        private const val STALE_TARGET_MS = 5_000L
        private const val MAX_SPARKLINE_SAMPLES = 30
        private const val MAX_INTERVAL_SAMPLES = 24
    }

    private val geigerEngine = GeigerFeedbackEngine(application, viewModelScope)
    private val cellularMonitor = CellularMonitor(application, viewModelScope)
    private val gnssRfMonitor = GnssRfMonitor(application)

    private val _uiState = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private val _statusMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val statusMessages: SharedFlow<String> = _statusMessages.asSharedFlow()

    private val emitterRegistry = ConcurrentHashMap<String, EmitterRecord>()
    private val registryLock = Any()

    @Volatile
    private var lockedMac: String? = null

    @Volatile
    private var lockedScanResult: ScanResult? = null

    @Volatile
    private var lastLockedPacketElapsedMs: Long = 0L

    @Volatile
    private var gridDirty: Boolean = true

    @Volatile
    private var selectedDossierKey: String? = null // fingerprintHash or MAC

    private var sessionMinDist: Float? = null
    private var sessionMaxRssi: Int? = null

    private var acquisitionJob: Job? = null
    private var rttPollingJob: Job? = null
    private var snapshotRenderJob: Job? = null
    private var poseObserverJob: Job? = null
    private var auxiliaryMonitorsJob: Job? = null

    init {
        viewModelScope.launch {
            sessionLogRepository.refresh()
            offlineMapTileManager.refreshCacheStats()
        }
        viewModelScope.launch {
            sessionLogRepository.snapshots.collect { records ->
                backgroundEmitterTracker.updateSavedLogMacs(records.map { it.targetMac }.toSet())
                _uiState.update { it.copy(savedSnapshots = records) }
            }
        }
        refreshHardwareAndPermissionState()
        startPoseAndSensorObservers()
        startSnapshotRenderLoop()
    }

    fun refreshHardwareAndPermissionState() {
        val ctx = getApplication<Application>()
        val missing = PermissionHelper.missingPermissions(ctx)
        val hasCore = PermissionHelper.hasCoreRfPermissions(ctx)
        val hw = HardwareStatus(
            hasWifiRtt = standaloneSource.hasWifiRttCapability,
            hasBleScanner = standaloneSource.hasBleCapability,
            hasRotationVector = imuEngine.hasRotationVector,
            hasStepDetector = imuEngine.hasStepDetector,
            hasMagnetometer = imuEngine.hasMagnetometer,
            usbBridgeAttached = optionalUsbSource.isAvailable
        )
        _uiState.update {
            it.copy(
                hasPermissions = hasCore,
                missingPermissions = missing,
                hardwareStatus = hw
            )
        }
        if (hasCore) {
            offlineMapTileManager.refreshAnchorFromLocationManager()
            startSensorsAndRadios()
        }
    }

    fun onActivityStarted() {
        refreshHardwareAndPermissionState()
        if (_uiState.value.hasPermissions) {
            startSensorsAndRadios()
        }
    }

    fun onActivityStopped() {
        stopSensorsAndRadios()
        geigerEngine.setEnabled(false)
        _uiState.update { it.copy(isGeigerEnabled = false, geigerPulseRateHz = 0f) }
    }

    private fun startPoseAndSensorObservers() {
        if (poseObserverJob?.isActive != true) {
            poseObserverJob = viewModelScope.launch {
                imuEngine.pose.collect { pose ->
                    _uiState.update { it.copy(userPose = pose) }
                    gridDirty = true
                }
            }
        }
        if (auxiliaryMonitorsJob?.isActive != true) {
            auxiliaryMonitorsJob = viewModelScope.launch {
                launch {
                    imuEngine.magneticStatus.collect { mag ->
                        _uiState.update { it.copy(magneticStatus = mag) }
                    }
                }
                launch {
                    offlineMapTileManager.cacheState.collect { tileState ->
                        _uiState.update { it.copy(tileCacheState = tileState) }
                    }
                }
                launch {
                    cellularMonitor.snapshot.collect { cellSnap ->
                        _uiState.update { it.copy(cellularSnapshot = cellSnap) }
                    }
                }
                launch {
                    gnssRfMonitor.status.collect { gnss ->
                        _uiState.update { it.copy(gnssRfStatus = gnss) }
                    }
                }
            }
        }
    }

    private fun startSensorsAndRadios() {
        imuEngine.start()
        cellularMonitor.start()
        gnssRfMonitor.start()
        if (acquisitionJob?.isActive == true) return

        val sources = listOf(standaloneSource, optionalUsbSource)
        acquisitionJob = viewModelScope.launch(Dispatchers.Default) {
            sources
                .map { source ->
                    source.measurementsFlow().catch { /* Isolate individual source errors */ }
                }
                .merge()
                .collect { measurement ->
                    processMeasurement(measurement)
                }
        }
    }

    private fun stopSensorsAndRadios() {
        imuEngine.stop()
        cellularMonitor.stop()
        gnssRfMonitor.stop()
        acquisitionJob?.cancel()
        acquisitionJob = null
        rttPollingJob?.cancel()
        rttPollingJob = null
    }

    private fun processMeasurement(m: RfMeasurement) {
        val now = m.timestampElapsedMs
        val pose = imuEngine.pose.value
        val currentCalibration = _uiState.value.calibration

        backgroundEmitterTracker.ingest(m, pose, currentCalibration)

        var smoothedRssi = RssiKalmanFilter.sanitizeNegativeDbm(m.rssiDbm.toDouble())
        synchronized(registryLock) {
            val existing = emitterRegistry[m.macAddress]
            if (existing == null) {
                val created = EmitterRecord(
                    macAddress = m.macAddress,
                    fingerprintHash = m.fingerprintHash,
                    displayName = m.displayName,
                    transport = m.transport,
                    isRttResponder = m.isRttResponder,
                    lastRssiDbm = m.rssiDbm,
                    lastSeenElapsedMs = now,
                    scanResult = m.scanResult,
                    bleDecoded = m.bleDecoded
                )
                smoothedRssi = created.kalman.update(m.rssiDbm.toDouble())
                created.packetTimestampsMs.addLast(now)
                created.rssiHistory.addLast(RssiSamplePoint(m.rssiDbm, smoothedRssi.toFloat(), now))
                emitterRegistry[m.macAddress] = created
            } else {
                val dt = now - existing.lastSeenElapsedMs
                if (dt in 5L..10_000L) {
                    existing.interArrivalIntervalsMs.addLast(dt)
                    while (existing.interArrivalIntervalsMs.size > MAX_INTERVAL_SAMPLES) {
                        existing.interArrivalIntervalsMs.removeFirst()
                    }
                }
                if (m.displayName.isNotBlank() &&
                    m.displayName != "BLE Emitter" &&
                    m.displayName != "Hidden AP"
                ) {
                    existing.displayName = m.displayName
                }
                existing.fingerprintHash = m.fingerprintHash
                existing.lastRssiDbm = m.rssiDbm
                existing.lastSeenElapsedMs = now
                if (m.isRttResponder) existing.isRttResponder = true
                if (m.scanResult != null) existing.scanResult = m.scanResult
                if (m.bleDecoded != null) existing.bleDecoded = m.bleDecoded
                smoothedRssi = existing.kalman.update(m.rssiDbm.toDouble())
                existing.packetTimestampsMs.addLast(now)
                pruneTimestamps(existing.packetTimestampsMs, now)
                existing.rssiHistory.addLast(RssiSamplePoint(m.rssiDbm, smoothedRssi.toFloat(), now))
                while (existing.rssiHistory.size > MAX_SPARKLINE_SAMPLES) {
                    existing.rssiHistory.removeFirst()
                }
            }
        }

        val currentLocked = lockedMac ?: return
        if (m.macAddress != currentLocked) return

        if (m.scanResult != null) {
            lockedScanResult = m.scanResult
        }
        lastLockedPacketElapsedMs = now

        var effectiveParams = currentCalibration
        val advertisedTxPower = m.bleDecoded?.advertisedTxPowerDbm
        if (advertisedTxPower != null &&
            _uiState.value.activePresets.autoOverriddenTxPowerDbm != advertisedTxPower
        ) {
            effectiveParams = CalibrationPresetApplier.applyBleTxPowerOverride(
                currentCalibration,
                advertisedTxPower
            )
            _uiState.update { state ->
                state.copy(
                    calibration = effectiveParams,
                    activePresets = state.activePresets.copy(
                        emitterClassPreset = null,
                        autoOverriddenTxPowerDbm = advertisedTxPower
                    )
                )
            }
        }

        val rttDist = m.rttDistanceMeters
        if (rttDist != null && rttDist.isFinite()) {
            bayesianGridFilter.updateWithRtt(
                userX = pose.xMeters,
                userY = pose.yMeters,
                rttDistanceMeters = rttDist,
                sigmaRttMeters = effectiveParams.sigmaRttMeters
            )
        } else {
            bayesianGridFilter.updateWithShieldedRssi(
                userX = pose.xMeters,
                userY = pose.yMeters,
                userHeadingRad = pose.headingRad,
                measuredRssiDbm = m.rssiDbm.toDouble(),
                params = effectiveParams
            )
        }

        val prevMaxRssi = sessionMaxRssi
        if (prevMaxRssi == null || m.rssiDbm > prevMaxRssi) {
            sessionMaxRssi = m.rssiDbm
        }

        gridDirty = true

        _uiState.update { state ->
            state.copy(
                lockedTargetLastRssiDbm = m.rssiDbm,
                lockedTargetKalmanRssiDbm = smoothedRssi.toFloat(),
                lockedTargetLastRttMeters = rttDist ?: state.lockedTargetLastRttMeters,
                lockedTargetIsRtt = state.lockedTargetIsRtt || m.isRttResponder,
                lockedTargetIsStale = false,
                lockedTargetBleDecoded = m.bleDecoded ?: state.lockedTargetBleDecoded,
                sessionMaxRssiDbm = sessionMaxRssi
            )
        }
    }

    private fun startSnapshotRenderLoop() {
        if (snapshotRenderJob?.isActive == true) return
        snapshotRenderJob = viewModelScope.launch(Dispatchers.Default) {
            var tickCount = 0L
            while (isActive) {
                val power = _uiState.value.powerMode
                delay(power.frameIntervalMs)
                tickCount++
                val now = SystemClock.elapsedRealtime()
                val pose = imuEngine.pose.value

                val snapshot = if (gridDirty) {
                    gridDirty = false
                    bayesianGridFilter.createSnapshot(pose.xMeters, pose.yMeters)
                } else {
                    null
                }

                val latestSnapshot = snapshot ?: _uiState.value.gridSnapshot
                val targetMac = lockedMac

                val updatedCoach = if (targetMac != null) {
                    sweepCoachEngine.update(
                        userPose = pose,
                        lockedTargetRssiDbm = _uiState.value.lockedTargetLastRssiDbm,
                        gridSnapshot = latestSnapshot
                    )
                } else {
                    null
                }

                val refreshDivisor = if (power == PowerMode.TURBO) 5L else 3L
                val updatedEmitters = if (tickCount % refreshDivisor == 0L) {
                    buildSortedEmitterList(now)
                } else {
                    null
                }
                val updatedBackground = if (tickCount % refreshDivisor == 0L) {
                    backgroundEmitterTracker.snapshot(
                        nowElapsedMs = now,
                        userPose = pose,
                        calibration = _uiState.value.calibration,
                        lockedMac = targetMac
                    )
                } else {
                    null
                }

                val dossierKey = selectedDossierKey
                val updatedDossier = if (dossierKey != null && tickCount % 3L == 0L) {
                    buildDossierData(
                        key = dossierKey,
                        nowElapsedMs = now,
                        bgList = updatedBackground ?: _uiState.value.backgroundEmitters
                    )
                } else {
                    null
                }

                val lockedRateHz = if (targetMac != null) {
                    computePacketRateHz(targetMac, now)
                } else {
                    0f
                }
                val isStale = targetMac != null &&
                    lastLockedPacketElapsedMs > 0L &&
                    (now - lastLockedPacketElapsedMs) > STALE_TARGET_MS

                if (snapshot != null && targetMac != null && snapshot.updateCount > 0L) {
                    val estDist = snapshot.estimatedDistanceMeters
                    val currentMin = sessionMinDist
                    if (estDist > 0f && (currentMin == null || estDist < currentMin)) {
                        sessionMinDist = estDist
                    }
                    geigerEngine.updateTargetState(
                        locked = true,
                        estimatedDistanceMeters = estDist
                    )
                } else if (targetMac == null) {
                    geigerEngine.updateTargetState(locked = false, estimatedDistanceMeters = 15f)
                }

                val pulseHz = geigerEngine.currentPulseRateHz()

                _uiState.update { state ->
                    state.copy(
                        gridSnapshot = snapshot ?: state.gridSnapshot,
                        sweepCoachState = updatedCoach ?: state.sweepCoachState,
                        discoveredEmitters = updatedEmitters ?: state.discoveredEmitters,
                        backgroundEmitters = updatedBackground ?: state.backgroundEmitters,
                        activeDossier = if (dossierKey == null) null else (updatedDossier ?: state.activeDossier),
                        lockedTargetPacketRateHz = lockedRateHz,
                        lockedTargetIsStale = isStale,
                        sessionMinDistanceMeters = sessionMinDist,
                        geigerPulseRateHz = pulseHz
                    )
                }
            }
        }
    }

    private fun buildSortedEmitterList(nowElapsedMs: Long): List<DiscoveredEmitter> {
        val list = mutableListOf<DiscoveredEmitter>()
        synchronized(registryLock) {
            val iterator = emitterRegistry.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val rec = entry.value
                if (rec.macAddress != lockedMac && (nowElapsedMs - rec.lastSeenElapsedMs) > EMITTER_EXPIRY_MS) {
                    iterator.remove()
                    continue
                }
                pruneTimestamps(rec.packetTimestampsMs, nowElapsedMs)
                val rateHz = rec.packetTimestampsMs.size.toFloat() / (RATE_WINDOW_MS / 1000.0f)
                list.add(
                    DiscoveredEmitter(
                        macAddress = rec.macAddress,
                        displayName = rec.displayName,
                        transport = rec.transport,
                        isRttResponder = rec.isRttResponder,
                        rssiDbm = rec.lastRssiDbm,
                        packetRateHz = rateHz,
                        lastSeenElapsedMs = rec.lastSeenElapsedMs,
                        scanResult = rec.scanResult,
                        fingerprintHash = rec.fingerprintHash,
                        kalmanRssiDbm = rec.kalman.currentEstimateDbm().toFloat(),
                        bleDecoded = rec.bleDecoded,
                        observedMacCount = backgroundEmitterTracker.observedMacCountFor(rec.fingerprintHash)
                    )
                )
            }
        }
        return list.sortedWith(
            compareByDescending<DiscoveredEmitter> { it.isRttResponder }
                .thenByDescending { it.rssiDbm }
                .thenByDescending { it.packetRateHz }
        )
    }

    private fun buildDossierData(
        key: String,
        nowElapsedMs: Long,
        bgList: List<BackgroundEmitter>
    ): EmitterDossierData? {
        val bg = bgList.firstOrNull { it.fingerprintHash == key || it.latestMacAddress == key }
        val rec = synchronized(registryLock) {
            emitterRegistry[key]
                ?: emitterRegistry.values.firstOrNull { it.fingerprintHash == key }
                ?: bg?.let { emitterRegistry[it.latestMacAddress] }
        }
        if (rec == null && bg == null) return null

        val fpHash = rec?.fingerprintHash ?: bg!!.fingerprintHash
        val mac = rec?.macAddress ?: bg!!.latestMacAddress
        val rateHz = if (rec != null) {
            synchronized(registryLock) {
                pruneTimestamps(rec.packetTimestampsMs, nowElapsedMs)
                rec.packetTimestampsMs.size.toFloat() / (RATE_WINDOW_MS / 1000.0f)
            }
        } else {
            0f
        }

        val (jitterMs, skewPpm, history) = if (rec != null) {
            synchronized(registryLock) {
                val intervals = rec.interArrivalIntervalsMs.toList()
                val jitter = computeJitterMs(intervals)
                val skew = computeClockSkewPpm(intervals)
                Triple(jitter, skew, rec.rssiHistory.toList())
            }
        } else {
            Triple(0f, 0f, emptyList())
        }

        val macHistory = backgroundEmitterTracker.observedMacHistoryFor(fpHash)

        return EmitterDossierData(
            fingerprintHash = fpHash,
            macAddress = mac,
            displayName = rec?.displayName ?: bg!!.displayName,
            transport = rec?.transport ?: bg!!.transport,
            isRttResponder = rec?.isRttResponder ?: bg!!.isRttResponder,
            latestRawRssiDbm = rec?.lastRssiDbm ?: bg!!.maxRssiDbm,
            latestKalmanRssiDbm = rec?.kalman?.currentEstimateDbm()?.toFloat() ?: bg!!.kalmanRssiDbm,
            packetRateHz = rateHz,
            interArrivalJitterMs = jitterMs,
            clockSkewPpm = skewPpm,
            rssiHistory = history,
            macHistory = macHistory,
            bleDecoded = rec?.bleDecoded,
            shapeStage = bg?.shapeStage,
            angularSpreadDeg = bg?.angularSpreadDeg,
            estimatedDistanceMeters = bg?.estimatedDistanceMeters,
            isPinned = bg?.isSavedInLog ?: false,
            isLocked = lockedMac != null && (mac == lockedMac || fpHash == _uiState.value.lockedTargetFingerprint),
            scanResult = rec?.scanResult
        )
    }

    private fun computeJitterMs(intervals: List<Long>): Float {
        if (intervals.size < 2) return 0f
        var diffSum = 0.0
        for (i in 1 until intervals.size) {
            diffSum += abs(intervals[i] - intervals[i - 1]).toDouble()
        }
        return (diffSum / (intervals.size - 1)).toFloat()
    }

    private fun computeClockSkewPpm(intervals: List<Long>): Float {
        if (intervals.size < 4) return 0f
        val sorted = intervals.sorted()
        val median = sorted[sorted.size / 2].toDouble().coerceAtLeast(1.0)
        val firstHalfAvg = intervals.take(intervals.size / 2).average()
        val secondHalfAvg = intervals.drop(intervals.size / 2).average()
        val driftRatio = (secondHalfAvg - firstHalfAvg) / median
        return (driftRatio * 1_000_000.0).toFloat().coerceIn(-99_999f, 99_999f)
    }

    private fun computePacketRateHz(mac: String, nowElapsedMs: Long): Float {
        synchronized(registryLock) {
            val rec = emitterRegistry[mac] ?: return 0f
            pruneTimestamps(rec.packetTimestampsMs, nowElapsedMs)
            return rec.packetTimestampsMs.size.toFloat() / (RATE_WINDOW_MS / 1000.0f)
        }
    }

    private fun pruneTimestamps(queue: ArrayDeque<Long>, nowElapsedMs: Long) {
        val cutoff = nowElapsedMs - RATE_WINDOW_MS
        while (queue.isNotEmpty() && queue.first() < cutoff) {
            queue.removeFirst()
        }
    }

    // --- Signal Dossier Selection ---

    fun openDossierForBackgroundEmitter(emitter: BackgroundEmitter) {
        selectedDossierKey = emitter.fingerprintHash
        val dossier = buildDossierData(
            key = emitter.fingerprintHash,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            bgList = _uiState.value.backgroundEmitters
        )
        _uiState.update { it.copy(activeDossier = dossier) }
    }

    fun openDossierForDiscoveredEmitter(emitter: DiscoveredEmitter) {
        selectedDossierKey = emitter.fingerprintHash
        val dossier = buildDossierData(
            key = emitter.fingerprintHash,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            bgList = _uiState.value.backgroundEmitters
        )
        _uiState.update { it.copy(activeDossier = dossier) }
    }

    fun closeDossier() {
        selectedDossierKey = null
        _uiState.update { it.copy(activeDossier = null) }
    }

    fun lockTargetFromDossier(dossier: EmitterDossierData) {
        val emitter = DiscoveredEmitter(
            macAddress = dossier.macAddress,
            displayName = dossier.displayName,
            transport = dossier.transport,
            isRttResponder = dossier.isRttResponder,
            rssiDbm = dossier.latestRawRssiDbm,
            packetRateHz = dossier.packetRateHz,
            lastSeenElapsedMs = SystemClock.elapsedRealtime(),
            scanResult = dossier.scanResult,
            fingerprintHash = dossier.fingerprintHash,
            kalmanRssiDbm = dossier.latestKalmanRssiDbm,
            bleDecoded = dossier.bleDecoded,
            observedMacCount = dossier.macHistory.size.coerceAtLeast(1)
        )
        lockTarget(emitter)
        closeDossier()
    }

    /**
     * Locks Bayesian grid strictly onto a single user-selected emitter MAC.
     * Starts Guided Two-Spin Triangulation Coach and Tier 1 RTT polling if eligible.
     */
    fun lockTarget(emitter: DiscoveredEmitter) {
        lockedMac = emitter.macAddress
        lockedScanResult = emitter.scanResult
        lastLockedPacketElapsedMs = SystemClock.elapsedRealtime()
        sessionMinDist = null
        sessionMaxRssi = emitter.rssiDbm

        bayesianGridFilter.resetToUniform()
        gridDirty = true

        val pose = imuEngine.pose.value
        val coachStart = sweepCoachEngine.startForLockedTarget(pose)

        val txPower0x0A = emitter.bleDecoded?.advertisedTxPowerDbm
        val updatedCalibration = if (txPower0x0A != null) {
            CalibrationPresetApplier.applyBleTxPowerOverride(_uiState.value.calibration, txPower0x0A)
        } else {
            _uiState.value.calibration
        }

        _uiState.update { state ->
            state.copy(
                calibration = updatedCalibration,
                activePresets = state.activePresets.copy(
                    autoOverriddenTxPowerDbm = txPower0x0A
                ),
                lockedTargetMac = emitter.macAddress,
                lockedTargetFingerprint = emitter.fingerprintHash,
                lockedTargetName = emitter.displayName,
                lockedTargetTransport = emitter.transport,
                lockedTargetIsRtt = emitter.isRttResponder,
                lockedTargetLastRssiDbm = emitter.rssiDbm,
                lockedTargetKalmanRssiDbm = emitter.kalmanRssiDbm,
                lockedTargetLastRttMeters = null,
                lockedTargetPacketRateHz = emitter.packetRateHz,
                lockedTargetIsStale = false,
                lockedTargetBleDecoded = emitter.bleDecoded,
                sessionMinDistanceMeters = null,
                sessionMaxRssiDbm = emitter.rssiDbm,
                sweepCoachState = coachStart,
                gridSnapshot = bayesianGridFilter.createSnapshot(pose.xMeters, pose.yMeters)
            )
        }

        startRttPollingIfEligible(emitter)
        _statusMessages.tryEmit("Locked onto ${emitter.displayName} (${emitter.macAddress})")
    }

    private fun startRttPollingIfEligible(emitter: DiscoveredEmitter) {
        rttPollingJob?.cancel()
        if (!emitter.isRttResponder || !standaloneSource.hasWifiRttCapability) return

        rttPollingJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && lockedMac == emitter.macAddress) {
                val sr = lockedScanResult
                if (sr != null) {
                    val rttMeasurement = standaloneSource.requestRttRanging(sr)
                    if (rttMeasurement != null) {
                        processMeasurement(rttMeasurement)
                    }
                }
                delay(_uiState.value.powerMode.rttPollMs)
            }
        }
    }

    fun unlockTarget() {
        lockedMac = null
        lockedScanResult = null
        lastLockedPacketElapsedMs = 0L
        sessionMinDist = null
        sessionMaxRssi = null
        rttPollingJob?.cancel()
        rttPollingJob = null

        bayesianGridFilter.resetToUniform()
        sweepCoachEngine.reset()
        gridDirty = true
        geigerEngine.updateTargetState(locked = false, estimatedDistanceMeters = 15f)

        val pose = imuEngine.pose.value
        _uiState.update { state ->
            state.copy(
                activePresets = state.activePresets.copy(autoOverriddenTxPowerDbm = null),
                lockedTargetMac = null,
                lockedTargetFingerprint = null,
                lockedTargetName = null,
                lockedTargetTransport = null,
                lockedTargetIsRtt = false,
                lockedTargetLastRssiDbm = null,
                lockedTargetKalmanRssiDbm = null,
                lockedTargetLastRttMeters = null,
                lockedTargetPacketRateHz = 0f,
                lockedTargetIsStale = false,
                lockedTargetBleDecoded = null,
                sessionMinDistanceMeters = null,
                sessionMaxRssiDbm = null,
                geigerPulseRateHz = 0f,
                sweepCoachState = sweepCoachEngine.currentState(pose),
                gridSnapshot = bayesianGridFilter.createSnapshot(pose.xMeters, pose.yMeters)
            )
        }
        _statusMessages.tryEmit("Target unlocked — grid cleared")
    }

    fun resetGridAndPose() {
        bayesianGridFilter.resetToUniform()
        imuEngine.resetPoseToCenter()
        sessionMinDist = null
        gridDirty = true

        val pose = imuEngine.pose.value
        val coach = if (lockedMac != null) {
            sweepCoachEngine.startForLockedTarget(pose)
        } else {
            sweepCoachEngine.reset()
            sweepCoachEngine.currentState(pose)
        }

        _uiState.update { state ->
            state.copy(
                userPose = pose,
                sessionMinDistanceMeters = null,
                sweepCoachState = coach,
                gridSnapshot = bayesianGridFilter.createSnapshot(pose.xMeters, pose.yMeters)
            )
        }
        _statusMessages.tryEmit("Grid reset to uniform · User re-centered (25.0m, 25.0m)")
    }

    fun zeroHeading() {
        imuEngine.zeroHeading()
        _statusMessages.tryEmit("Compass heading zeroed at current facing")
    }

    fun clearHeadingOffset() {
        imuEngine.clearHeadingOffset()
        _statusMessages.tryEmit("Compass offset cleared (raw magnetic north)")
    }

    fun toggleGeigerFeedback() {
        val next = !_uiState.value.isGeigerEnabled
        geigerEngine.setEnabled(next)
        _uiState.update {
            it.copy(
                isGeigerEnabled = next,
                geigerPulseRateHz = geigerEngine.currentPulseRateHz()
            )
        }
    }

    fun togglePowerMode() {
        val next = if (_uiState.value.powerMode == PowerMode.TURBO) PowerMode.ECO else PowerMode.TURBO
        _uiState.update { it.copy(powerMode = next) }
        _statusMessages.tryEmit(
            if (next == PowerMode.TURBO) "TURBO mode (12.5 FPS · High-rate RTT)"
            else "ECO battery mode (5 FPS · Reduced poll duty)"
        )
    }

    fun toggleMapOverlay() {
        val next = !_uiState.value.mapOverlayEnabled
        _uiState.update { it.copy(mapOverlayEnabled = next) }
    }

    fun triggerManualWifiScan() {
        val initiated = standaloneSource.triggerWifiScan()
        if (initiated) {
            _statusMessages.tryEmit("Wi-Fi scan sweep triggered")
        } else {
            _statusMessages.tryEmit("Wi-Fi scan throttled by OS — BLE & cached APs active")
        }
    }

    // --- One-Tap Carrying/Shield, Target Class, and Environment Presets ---

    fun cycleQuickCarryingMode() {
        val current = _uiState.value.activePresets.shieldPreset ?: ShieldPreset.OMNI_HANDHELD
        applyShieldPreset(current.nextQuickCycle())
    }

    fun applyShieldPreset(preset: ShieldPreset) {
        _uiState.update { state ->
            state.copy(
                calibration = CalibrationPresetApplier.applyShield(state.calibration, preset),
                activePresets = state.activePresets.copy(shieldPreset = preset)
            )
        }
        _statusMessages.tryEmit("Carrying mode: ${preset.displayName} (${preset.shieldDropDb.toInt()} dB)")
    }

    fun applyEmitterClassPreset(preset: EmitterClassPreset) {
        _uiState.update { state ->
            state.copy(
                calibration = CalibrationPresetApplier.applyEmitterClass(state.calibration, preset),
                activePresets = state.activePresets.copy(
                    emitterClassPreset = preset,
                    autoOverriddenTxPowerDbm = null
                )
            )
        }
        _statusMessages.tryEmit("Target class: ${preset.displayName} (${preset.txPowerP0Dbm.toInt()} dBm)")
    }

    fun applyEnvironmentPreset(preset: EnvironmentPreset) {
        imuEngine.updateStepLength(preset.stepLengthMeters)
        _uiState.update { state ->
            state.copy(
                calibration = CalibrationPresetApplier.applyEnvironment(state.calibration, preset),
                activePresets = state.activePresets.copy(environmentPreset = preset)
            )
        }
        _statusMessages.tryEmit("Environment: ${preset.displayName} (n=${preset.pathLossExponentN}, L=${preset.stepLengthMeters}m)")
    }

    fun updateShieldDropDb(value: Float) {
        val clamped = value.coerceIn(CalibrationParams.MIN_SHIELD_DB, CalibrationParams.MAX_SHIELD_DB)
        val matched = ShieldPreset.entries.firstOrNull { abs(it.shieldDropDb - clamped) < 0.25f }
        _uiState.update {
            it.copy(
                calibration = it.calibration.copy(shieldDropDb = clamped),
                activePresets = it.activePresets.copy(shieldPreset = matched)
            )
        }
    }

    fun updateTxPowerP0(value: Float) {
        val clamped = value.coerceIn(CalibrationParams.MIN_P0_DBM, CalibrationParams.MAX_P0_DBM)
        val matched = EmitterClassPreset.entries.firstOrNull { abs(it.txPowerP0Dbm - clamped) < 0.25f }
        _uiState.update {
            it.copy(
                calibration = it.calibration.copy(txPowerP0Dbm = clamped),
                activePresets = it.activePresets.copy(
                    emitterClassPreset = matched,
                    autoOverriddenTxPowerDbm = null
                )
            )
        }
    }

    fun updatePathLossExponent(value: Float) {
        val clamped = value.coerceIn(CalibrationParams.MIN_PATH_LOSS_N, CalibrationParams.MAX_PATH_LOSS_N)
        _uiState.update {
            it.copy(
                calibration = it.calibration.copy(pathLossExponentN = clamped),
                activePresets = it.activePresets.copy(environmentPreset = null)
            )
        }
    }

    fun updateStepLengthMeters(value: Float) {
        val clamped = value.coerceIn(CalibrationParams.MIN_STEP_M, CalibrationParams.MAX_STEP_M)
        imuEngine.updateStepLength(clamped)
        _uiState.update {
            it.copy(
                calibration = it.calibration.copy(stepLengthMeters = clamped),
                activePresets = it.activePresets.copy(environmentPreset = null)
            )
        }
    }

    fun restoreDefaultCalibration() {
        val defaults = CalibrationParams()
        imuEngine.updateStepLength(defaults.stepLengthMeters)
        _uiState.update {
            it.copy(
                calibration = defaults,
                activePresets = ActivePresetState()
            )
        }
        _statusMessages.tryEmit("Calibration restored to RF defaults")
    }

    fun cacheOfflineMapArea(radiusMeters: Double = OfflineMapTileManager.DEFAULT_CACHE_RADIUS_METERS) {
        viewModelScope.launch {
            val result = offlineMapTileManager.cacheMapArea(radiusMeters = radiusMeters)
            result.onSuccess { count ->
                _statusMessages.tryEmit("Cached $count dark tactical map tiles offline (z16–z18)")
            }.onFailure { err ->
                _statusMessages.tryEmit(err.message ?: "Unable to cache map tiles")
            }
        }
    }

    fun clearOfflineMapCache() {
        viewModelScope.launch {
            offlineMapTileManager.clearCache()
            _statusMessages.tryEmit("Offline map tile cache cleared")
        }
    }

    fun refreshCellularMonitor() {
        viewModelScope.launch(Dispatchers.Default) {
            cellularMonitor.refreshOnce()
        }
    }

    fun resetGnssBaseline() {
        gnssRfMonitor.resetBaseline()
        _statusMessages.tryEmit("GNSS AGC/C/N0 baseline reset")
    }

    /**
     * Saves timestamped snapshot to offline SQLite AND immediately marks the target's
     * fingerprintHash as PINNED inside [BackgroundEmitterTracker] so its radar marker
     * transitions instantaneously to the permanent emerald/gold Pinned Target Circle.
     */
    fun saveCurrentSnapshot() {
        val state = _uiState.value
        val mac = state.lockedTargetMac
        if (mac == null) {
            _statusMessages.tryEmit("Lock onto a target before saving a snapshot")
            return
        }

        // Immediate fingerprintHash pinning in BackgroundEmitterTracker
        backgroundEmitterTracker.pinTarget(
            fingerprintHash = state.lockedTargetFingerprint,
            macAddress = mac
        )
        val immediateBg = backgroundEmitterTracker.snapshot(
            nowElapsedMs = SystemClock.elapsedRealtime(),
            userPose = state.userPose,
            calibration = state.calibration,
            lockedMac = mac
        )
        _uiState.update { it.copy(backgroundEmitters = immediateBg) }

        val snap = state.gridSnapshot
        val minDist = state.sessionMinDistanceMeters?.toDouble()
            ?: snap.estimatedDistanceMeters.toDouble()
        val maxRssi = state.sessionMaxRssiDbm
            ?: state.lockedTargetLastRssiDbm
            ?: -100

        val record = SessionSnapshotRecord(
            timestampEpochMs = System.currentTimeMillis(),
            targetMac = mac,
            targetName = state.lockedTargetName ?: "Unknown Emitter",
            estimatedX = snap.estimatedTargetX.toDouble(),
            estimatedY = snap.estimatedTargetY.toDouble(),
            minDistanceMeters = minDist,
            maxRssiDbm = maxRssi,
            localizationTier = snap.activeTier.displayLabel
        )
        viewModelScope.launch {
            sessionLogRepository.saveSnapshot(record)
            _statusMessages.tryEmit("Saved & PINNED ${record.targetName} to offline log")
        }
    }

    fun deleteSnapshot(id: Long) {
        viewModelScope.launch {
            sessionLogRepository.deleteSnapshot(id)
        }
    }

    fun clearAllSnapshots() {
        viewModelScope.launch {
            sessionLogRepository.clearAll()
            _statusMessages.tryEmit("Session log cleared")
        }
    }

    fun exportSnapshots(uri: Uri, format: ExportFormat) {
        viewModelScope.launch {
            val result = sessionLogRepository.exportToUri(uri, format)
            result.onSuccess { count ->
                _statusMessages.tryEmit("Exported $count snapshots (${format.fileExtension.uppercase()})")
            }.onFailure { err ->
                _statusMessages.tryEmit("Export failed: ${err.message ?: "I/O error"}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSensorsAndRadios()
        snapshotRenderJob?.cancel()
        poseObserverJob?.cancel()
        auxiliaryMonitorsJob?.cancel()
        geigerEngine.release()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RadarViewModel(
                application = application,
                standaloneSource = StandaloneRadioDataSource(application),
                optionalUsbSource = Esp32UsbBridge(application),
                imuEngine = ImuDeadReckoningEngine(application),
                bayesianGridFilter = BayesianGridFilter(),
                backgroundEmitterTracker = BackgroundEmitterTracker(),
                sweepCoachEngine = SweepCoachEngine(),
                offlineMapTileManager = OfflineMapTileManager(application),
                sessionLogRepository = SessionLogRepository(application)
            ) as T
        }
    }
}
