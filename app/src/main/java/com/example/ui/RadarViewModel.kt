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
import com.example.dsp.BayesianGridFilter
import com.example.dsp.CalibrationParams
import com.example.dsp.DiscoveredEmitter
import com.example.dsp.EmitterTransport
import com.example.dsp.GridSnapshot
import com.example.dsp.HardwareStatus
import com.example.dsp.LocalizationTier
import com.example.dsp.RfMeasurement
import com.example.dsp.SessionSnapshotRecord
import com.example.dsp.UserPose
import com.example.sensors.Esp32UsbBridge
import com.example.sensors.GeigerFeedbackEngine
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

data class RadarUiState(
    val hasPermissions: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
    val hardwareStatus: HardwareStatus = HardwareStatus(),
    val userPose: UserPose = UserPose(),
    val gridSnapshot: GridSnapshot = GridSnapshot.uniform(),
    val calibration: CalibrationParams = CalibrationParams(),
    val discoveredEmitters: List<DiscoveredEmitter> = emptyList(),
    val lockedTargetMac: String? = null,
    val lockedTargetName: String? = null,
    val lockedTargetTransport: EmitterTransport? = null,
    val lockedTargetIsRtt: Boolean = false,
    val lockedTargetLastRssiDbm: Int? = null,
    val lockedTargetLastRttMeters: Double? = null,
    val lockedTargetPacketRateHz: Float = 0f,
    val lockedTargetIsStale: Boolean = false,
    val sessionMinDistanceMeters: Float? = null,
    val sessionMaxRssiDbm: Int? = null,
    val isGeigerEnabled: Boolean = false,
    val geigerPulseRateHz: Float = 0f,
    val savedSnapshots: List<SessionSnapshotRecord> = emptyList()
)

private class EmitterRecord(
    val macAddress: String,
    var displayName: String,
    var transport: EmitterTransport,
    var isRttResponder: Boolean,
    var lastRssiDbm: Int,
    var lastSeenElapsedMs: Long,
    var scanResult: ScanResult?,
    val packetTimestampsMs: ArrayDeque<Long> = ArrayDeque()
)

class RadarViewModel(
    application: Application,
    private val standaloneSource: StandaloneRadioDataSource,
    private val optionalUsbSource: RfDataSource,
    private val imuEngine: ImuDeadReckoningEngine,
    private val bayesianGridFilter: BayesianGridFilter,
    private val sessionLogRepository: SessionLogRepository
) : AndroidViewModel(application) {

    companion object {
        private const val RATE_WINDOW_MS = 4_000L
        private const val EMITTER_EXPIRY_MS = 35_000L
        private const val STALE_TARGET_MS = 5_000L
        private const val RENDER_FRAME_INTERVAL_MS = 80L // 12.5 FPS snapshot cadence
        private const val RTT_POLL_INTERVAL_MS = 450L
    }

    private val geigerEngine = GeigerFeedbackEngine(application, viewModelScope)

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

    private var sessionMinDist: Float? = null
    private var sessionMaxRssi: Int? = null

    private var acquisitionJob: Job? = null
    private var rttPollingJob: Job? = null
    private var snapshotRenderJob: Job? = null
    private var poseObserverJob: Job? = null

    init {
        viewModelScope.launch {
            sessionLogRepository.refresh()
        }
        viewModelScope.launch {
            sessionLogRepository.snapshots.collect { records ->
                _uiState.update { it.copy(savedSnapshots = records) }
            }
        }
        refreshHardwareAndPermissionState()
        startPoseObserver()
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

    private fun startPoseObserver() {
        if (poseObserverJob?.isActive == true) return
        poseObserverJob = viewModelScope.launch {
            imuEngine.pose.collect { pose ->
                _uiState.update { it.copy(userPose = pose) }
                gridDirty = true
            }
        }
    }

    private fun startSensorsAndRadios() {
        imuEngine.start()
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
        acquisitionJob?.cancel()
        acquisitionJob = null
        rttPollingJob?.cancel()
        rttPollingJob = null
    }

    /**
     * Runs strictly on Dispatchers.Default.
     * Updates discovered emitter telemetry for all MACs, but isolates Bayesian
     * grid updates STRICTLY to [lockedMac].
     */
    private fun processMeasurement(m: RfMeasurement) {
        val now = m.timestampElapsedMs

        // 1. Update live emitter registry & sliding-window packet rate
        synchronized(registryLock) {
            val existing = emitterRegistry[m.macAddress]
            if (existing == null) {
                val created = EmitterRecord(
                    macAddress = m.macAddress,
                    displayName = m.displayName,
                    transport = m.transport,
                    isRttResponder = m.isRttResponder,
                    lastRssiDbm = m.rssiDbm,
                    lastSeenElapsedMs = now,
                    scanResult = m.scanResult
                )
                created.packetTimestampsMs.addLast(now)
                emitterRegistry[m.macAddress] = created
            } else {
                if (m.displayName.isNotBlank() &&
                    m.displayName != "BLE Emitter" &&
                    m.displayName != "Hidden AP"
                ) {
                    existing.displayName = m.displayName
                }
                existing.lastRssiDbm = m.rssiDbm
                existing.lastSeenElapsedMs = now
                if (m.isRttResponder) existing.isRttResponder = true
                if (m.scanResult != null) existing.scanResult = m.scanResult
                existing.packetTimestampsMs.addLast(now)
                pruneTimestamps(existing.packetTimestampsMs, now)
            }
        }

        // 2. Single-target lock isolation: ignore all other MACs for Bayesian math
        val currentLocked = lockedMac ?: return
        if (m.macAddress != currentLocked) return

        if (m.scanResult != null) {
            lockedScanResult = m.scanResult
        }
        lastLockedPacketElapsedMs = now

        val pose = imuEngine.pose.value
        val params = _uiState.value.calibration

        val rttDist = m.rttDistanceMeters
        if (rttDist != null && rttDist.isFinite()) {
            // Tier 1: Hardware Wi-Fi RTT Gaussian distance likelihood
            bayesianGridFilter.updateWithRtt(
                userX = pose.xMeters,
                userY = pose.yMeters,
                rttDistanceMeters = rttDist,
                sigmaRttMeters = params.sigmaRttMeters
            )
        } else {
            // Tier 2: Standalone Shielded Cardioid RSSI likelihood
            bayesianGridFilter.updateWithShieldedRssi(
                userX = pose.xMeters,
                userY = pose.yMeters,
                userHeadingRad = pose.headingRad,
                measuredRssiDbm = m.rssiDbm.toDouble(),
                params = params
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
                lockedTargetLastRttMeters = rttDist ?: state.lockedTargetLastRttMeters,
                lockedTargetIsRtt = state.lockedTargetIsRtt || m.isRttResponder,
                lockedTargetIsStale = false,
                sessionMaxRssiDbm = sessionMaxRssi
            )
        }
    }

    /**
     * Emits immutable 100x100 GridSnapshots and refreshed Scanner List telemetry
     * at 12.5 FPS (80 ms interval) from Dispatchers.Default.
     */
    private fun startSnapshotRenderLoop() {
        if (snapshotRenderJob?.isActive == true) return
        snapshotRenderJob = viewModelScope.launch(Dispatchers.Default) {
            var tickCount = 0L
            while (isActive) {
                delay(RENDER_FRAME_INTERVAL_MS)
                tickCount++
                val now = SystemClock.elapsedRealtime()
                val pose = imuEngine.pose.value

                val snapshot = if (gridDirty) {
                    gridDirty = false
                    bayesianGridFilter.createSnapshot(pose.xMeters, pose.yMeters)
                } else {
                    null
                }

                // Refresh sorted scanner list & Hz packet rates every ~400 ms (5 ticks)
                val updatedEmitters = if (tickCount % 5L == 0L) {
                    buildSortedEmitterList(now)
                } else {
                    null
                }

                val targetMac = lockedMac
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
                        discoveredEmitters = updatedEmitters ?: state.discoveredEmitters,
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
                // Keep currently locked target even if temporarily silenced by shield
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
                        scanResult = rec.scanResult
                    )
                )
            }
        }
        // Strict sort order: RTT responders first, then strongest live RSSI, then packet Hz
        return list.sortedWith(
            compareByDescending<DiscoveredEmitter> { it.isRttResponder }
                .thenByDescending { it.rssiDbm }
                .thenByDescending { it.packetRateHz }
        )
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

    /**
     * Locks Bayesian grid strictly onto a single user-selected emitter MAC.
     * Starts Tier 1 RTT polling if the target supports IEEE 802.11mc/az.
     */
    fun lockTarget(emitter: DiscoveredEmitter) {
        lockedMac = emitter.macAddress
        lockedScanResult = emitter.scanResult
        lastLockedPacketElapsedMs = SystemClock.elapsedRealtime()
        sessionMinDist = null
        sessionMaxRssi = emitter.rssiDbm

        bayesianGridFilter.resetToUniform()
        gridDirty = true

        _uiState.update { state ->
            state.copy(
                lockedTargetMac = emitter.macAddress,
                lockedTargetName = emitter.displayName,
                lockedTargetTransport = emitter.transport,
                lockedTargetIsRtt = emitter.isRttResponder,
                lockedTargetLastRssiDbm = emitter.rssiDbm,
                lockedTargetLastRttMeters = null,
                lockedTargetPacketRateHz = emitter.packetRateHz,
                lockedTargetIsStale = false,
                sessionMinDistanceMeters = null,
                sessionMaxRssiDbm = emitter.rssiDbm,
                gridSnapshot = bayesianGridFilter.createSnapshot(
                    state.userPose.xMeters,
                    state.userPose.yMeters
                )
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
                delay(RTT_POLL_INTERVAL_MS)
            }
        }
    }

    /** Manually unlocks the current target MAC ("Reset Target"). */
    fun unlockTarget() {
        lockedMac = null
        lockedScanResult = null
        lastLockedPacketElapsedMs = 0L
        sessionMinDist = null
        sessionMaxRssi = null
        rttPollingJob?.cancel()
        rttPollingJob = null

        bayesianGridFilter.resetToUniform()
        gridDirty = true
        geigerEngine.updateTargetState(locked = false, estimatedDistanceMeters = 15f)

        _uiState.update { state ->
            state.copy(
                lockedTargetMac = null,
                lockedTargetName = null,
                lockedTargetTransport = null,
                lockedTargetIsRtt = false,
                lockedTargetLastRssiDbm = null,
                lockedTargetLastRttMeters = null,
                lockedTargetPacketRateHz = 0f,
                lockedTargetIsStale = false,
                sessionMinDistanceMeters = null,
                sessionMaxRssiDbm = null,
                geigerPulseRateHz = 0f,
                gridSnapshot = bayesianGridFilter.createSnapshot(
                    state.userPose.xMeters,
                    state.userPose.yMeters
                )
            )
        }
        _statusMessages.tryEmit("Target unlocked — grid cleared")
    }

    /**
     * "Reset Grid" field control:
     * Resets all 100x100 cell probabilities to uniform 1/10000, clears walking
     * breadcrumb trail, and snaps user back to center (25.0m, 25.0m).
     */
    fun resetGridAndPose() {
        bayesianGridFilter.resetToUniform()
        imuEngine.resetPoseToCenter()
        sessionMinDist = null
        gridDirty = true

        val pose = imuEngine.pose.value
        _uiState.update { state ->
            state.copy(
                userPose = pose,
                sessionMinDistanceMeters = null,
                gridSnapshot = bayesianGridFilter.createSnapshot(pose.xMeters, pose.yMeters)
            )
        }
        _statusMessages.tryEmit("Grid reset to uniform · User re-centered (25.0m, 25.0m)")
    }

    /**
     * "Zero Heading" field control:
     * Applies manual compass offset calibration (or clears if already zeroed when clearRequested=true).
     */
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

    fun triggerManualWifiScan() {
        val initiated = standaloneSource.triggerWifiScan()
        if (initiated) {
            _statusMessages.tryEmit("Wi-Fi scan sweep triggered")
        } else {
            _statusMessages.tryEmit("Wi-Fi scan throttled by OS — BLE & cached APs active")
        }
    }

    fun updateShieldDropDb(value: Float) {
        val clamped = value.coerceIn(CalibrationParams.MIN_SHIELD_DB, CalibrationParams.MAX_SHIELD_DB)
        _uiState.update { it.copy(calibration = it.calibration.copy(shieldDropDb = clamped)) }
    }

    fun updateTxPowerP0(value: Float) {
        val clamped = value.coerceIn(CalibrationParams.MIN_P0_DBM, CalibrationParams.MAX_P0_DBM)
        _uiState.update { it.copy(calibration = it.calibration.copy(txPowerP0Dbm = clamped)) }
    }

    fun updatePathLossExponent(value: Float) {
        val clamped = value.coerceIn(CalibrationParams.MIN_PATH_LOSS_N, CalibrationParams.MAX_PATH_LOSS_N)
        _uiState.update { it.copy(calibration = it.calibration.copy(pathLossExponentN = clamped)) }
    }

    fun updateStepLengthMeters(value: Float) {
        val clamped = value.coerceIn(CalibrationParams.MIN_STEP_M, CalibrationParams.MAX_STEP_M)
        imuEngine.updateStepLength(clamped)
        _uiState.update { it.copy(calibration = it.calibration.copy(stepLengthMeters = clamped)) }
    }

    fun restoreDefaultCalibration() {
        val defaults = CalibrationParams()
        imuEngine.updateStepLength(defaults.stepLengthMeters)
        _uiState.update { it.copy(calibration = defaults) }
        _statusMessages.tryEmit("Calibration restored to RF defaults")
    }

    /** Saves a timestamped snapshot of the currently locked target to offline SQLite. */
    fun saveCurrentSnapshot() {
        val state = _uiState.value
        val mac = state.lockedTargetMac
        if (mac == null) {
            _statusMessages.tryEmit("Lock onto a target before saving a snapshot")
            return
        }
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
            _statusMessages.tryEmit("Snapshot saved for ${record.targetName}")
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
                sessionLogRepository = SessionLogRepository(application)
            ) as T
        }
    }
}
