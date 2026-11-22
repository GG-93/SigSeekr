package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.ScanResult
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ExportFormat
import com.example.data.SessionLogDbHelper
import com.example.data.SessionLogRepository
import com.example.data.WatchlistRepository
import com.example.dsp.ActivePresetState
import com.example.dsp.BackgroundEmitter
import com.example.dsp.BackgroundEmitterTracker
import com.example.dsp.BayesianGridFilter
import com.example.dsp.BleDecodedPacket
import com.example.dsp.CalibrationParams
import com.example.dsp.CalibrationPresetApplier
import com.example.dsp.DiscoveredEmitter
import com.example.dsp.DroneDetectorEngine
import com.example.dsp.EmitterClassPreset
import com.example.dsp.EmitterDossierData
import com.example.dsp.EmitterTransport
import com.example.dsp.EnvironmentPreset
import com.example.dsp.EvilTwinDetector
import com.example.dsp.GridSnapshot
import com.example.dsp.HampelRssiFilter
import com.example.dsp.HardwareStatus
import com.example.dsp.MagneticFieldStatus
import com.example.dsp.PerimeterEventRecord
import com.example.dsp.PerimeterGuardEngine
import com.example.dsp.PerimeterScope
import com.example.dsp.PerimeterTriggerMode
import com.example.dsp.PowerMode
import com.example.dsp.Point2D
import com.example.dsp.RfMeasurement
import com.example.dsp.RssiKalmanFilter
import com.example.dsp.RssiSamplePoint
import com.example.dsp.SessionSnapshotRecord
import com.example.dsp.ShieldPreset
import com.example.dsp.SigintForensics
import com.example.dsp.SweepCoachEngine
import com.example.dsp.SweepCoachState
import com.example.dsp.UserPose
import com.example.dsp.WatchlistEntry
import com.example.dsp.WatchlistMatchType
import com.example.maps.OfflineMapTileManager
import com.example.maps.TileCacheState
import com.example.sensors.CellTowerInfo
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

enum class MainTab(val label: String) {
    RADAR("Radar"),
    SIGNALS("Signals & Watchlist"),
    PERIMETER_LOG("Perimeter & Log")
}

data class RadarUiState(
    val hasPermissions: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
    val hasCompletedFirstRunWalkthrough: Boolean = false,
    val isSweepStarted: Boolean = false,
    val activeTab: MainTab = MainTab.RADAR,
    val followMeEnabled: Boolean = true,
    val radarZoomWidthMeters: Float = 50.0f,
    val showLabels: Boolean = true,
    val hardwareStatus: HardwareStatus = HardwareStatus(),
    val userPose: UserPose = UserPose(),
    val magneticStatus: MagneticFieldStatus = MagneticFieldStatus(),
    val gridSnapshot: GridSnapshot = GridSnapshot.uniform(),
    val calibration: CalibrationParams = CalibrationParams(),
    val activePresets: ActivePresetState = ActivePresetState(),
    val powerMode: PowerMode = PowerMode.TURBO,
    val mapOverlayEnabled: Boolean = true,
    val showBackgroundPins: Boolean = true,
    val discoveredEmitters: List<DiscoveredEmitter> = emptyList(),
    val backgroundEmitters: List<BackgroundEmitter> = emptyList(),
    val sweepCoachState: SweepCoachState = SweepCoachState(),
    val tileCacheState: TileCacheState = TileCacheState(),
    val cellularSnapshot: CellularSnapshot = CellularSnapshot(),
    val lockedCellTower: CellTowerInfo? = null,
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
    val activeDossier: EmitterDossierData? = null,
    val watchlistEntries: List<WatchlistEntry> = emptyList(),
    val watchlistHitBanner: String? = null,
    val droneAlertBanner: String? = null,
    val topDroneEmitterMac: String? = null,
    val droneSlantAltitudeMeters: Float = 0.0f,
    val perimeterEnabled: Boolean = false,
    val perimeterRadiusMeters: Float = 15.0f,
    val perimeterTriggerMode: PerimeterTriggerMode = PerimeterTriggerMode.BOTH,
    val perimeterScope: PerimeterScope = PerimeterScope.ALL_UNKNOWN,
    val perimeterEvents: List<PerimeterEventRecord> = emptyList()
)

private class EmitterRecord(
    var macAddress: String,
    val fingerprintHash: String,
    var displayName: String,
    var transport: EmitterTransport,
    var isRttResponder: Boolean,
    var lastRssiDbm: Int,
    var lastSeenElapsedMs: Long,
    var lastSeenElapsedNs: Long,
    var firstSeenEpochMs: Long,
    var lastSeenEpochMs: Long,
    var scanResult: ScanResult?,
    var bleDecoded: BleDecodedPacket?,
    var wifiCapabilities: String? = null,
    var wifiVendorOuiHexes: List<String> = emptyList(),
    var frequencyMhz: Int? = null,
    var channelNumber: Int? = null,
    var channelWidthMhz: Int? = null,
    var wifiStandardLabel: String? = null,
    var wifiIeTags: List<Int> = emptyList(),
    var wifiVendorOuis: List<Pair<String, String>> = emptyList(),
    var wpsDeviceInfo: String? = null,
    var droneProtocol: String? = null,
    var isDroneSuspect: Boolean = false,
    var totalPacketCount: Int = 0,
    val linkedMacs: LinkedHashSet<String> = LinkedHashSet(),
    val strongPositions: ArrayDeque<Point2D> = ArrayDeque(),
    val kalman: RssiKalmanFilter = RssiKalmanFilter(),
    val packetTimestampsMs: ArrayDeque<Long> = ArrayDeque(),
    val rssiHistory: ArrayDeque<RssiSamplePoint> = ArrayDeque(),
    val interArrivalIntervalsMs: ArrayDeque<Double> = ArrayDeque()
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
    private val sessionLogRepository: SessionLogRepository,
    private val watchlistRepository: WatchlistRepository = WatchlistRepository(SessionLogDbHelper(application))
) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "sigseeker_prefs"
        private const val KEY_WALKTHROUGH_COMPLETED = "has_completed_walkthrough"
        private const val RATE_WINDOW_MS = 4_000L
        private const val EMITTER_EXPIRY_MS = 35_000L
        private const val STALE_TARGET_MS = 5_000L
        private const val MAX_SPARKLINE_SAMPLES = 30
        private const val MAX_INTERVAL_SAMPLES = 24
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val geigerEngine = GeigerFeedbackEngine(application, viewModelScope)
    private val cellularMonitor = CellularMonitor(application, viewModelScope)
    private val gnssRfMonitor = GnssRfMonitor(application)
    private val lockedTargetHampelFilter = HampelRssiFilter()
    private val perimeterGuardEngine = PerimeterGuardEngine()
    private var lastWatchlistHapticMs: Long = 0L

    @Volatile
    private var cachedWatchlist: List<WatchlistEntry> = emptyList()

    private val mutedFingerprints = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _uiState = MutableStateFlow(
        RadarUiState(
            hasCompletedFirstRunWalkthrough = prefs.getBoolean(KEY_WALKTHROUGH_COMPLETED, false),
            isSweepStarted = false,
            calibration = CalibrationParams(
                shieldDropDb = ShieldPreset.OMNI_HANDHELD.shieldDropDb,
                pathLossExponentN = EnvironmentPreset.INDOORS_DRYWALL.pathLossExponentN,
                stepLengthMeters = EnvironmentPreset.INDOORS_DRYWALL.stepLengthMeters
            ),
            activePresets = ActivePresetState(
                shieldPreset = ShieldPreset.OMNI_HANDHELD,
                environmentPreset = EnvironmentPreset.INDOORS_DRYWALL,
                screenOutwardMode = false
            )
        )
    )
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private val _statusMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val statusMessages: SharedFlow<String> = _statusMessages.asSharedFlow()

    private val emitterRegistry = ConcurrentHashMap<String, EmitterRecord>()
    private val registryLock = Any()

    @Volatile
    private var lockedMac: String? = null

    @Volatile
    private var lockedFingerprint: String? = null

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
            val wl = watchlistRepository.getAllWatchlist()
            val pe = watchlistRepository.getPerimeterEvents()
            cachedWatchlist = wl
            _uiState.update { it.copy(watchlistEntries = wl, perimeterEvents = pe) }
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
            hasStepDetector = imuEngine.hasStepDetector && PermissionHelper.hasActivityRecognitionPermission(ctx),
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
        // Strict Cold-Launch Gate: do NOT auto-start radio/grid ingestion until user taps START SWEEP
        if (hasCore && _uiState.value.isSweepStarted) {
            offlineMapTileManager.refreshAnchorFromLocationManager()
            startSensorsAndRadios()
        }
    }

    fun completeFirstRunWalkthrough() {
        prefs.edit().putBoolean(KEY_WALKTHROUGH_COMPLETED, true).apply()
        _uiState.update { it.copy(hasCompletedFirstRunWalkthrough = true) }
    }

    fun reopenFirstRunWalkthrough() {
        _uiState.update { it.copy(hasCompletedFirstRunWalkthrough = false) }
    }

    fun startSweepWithSetup(holdMode: SetupHoldMode, environment: EnvironmentPreset) {
        val baseCal = CalibrationParams(
            shieldDropDb = holdMode.shieldPreset.shieldDropDb,
            pathLossExponentN = environment.pathLossExponentN,
            stepLengthMeters = environment.stepLengthMeters
        )
        imuEngine.updateStepLength(environment.stepLengthMeters)
        imuEngine.setScreenOutwardMode(holdMode.screenOutwardMode)
        imuEngine.resetPoseToCenter()
        imuEngine.snapshotMagneticBaseline()

        bayesianGridFilter.resetToUniform()
        backgroundEmitterTracker.clear()
        lockedTargetHampelFilter.reset()
        synchronized(registryLock) {
            emitterRegistry.clear()
        }
        gridDirty = true

        val pose = imuEngine.pose.value
        _uiState.update { state ->
            state.copy(
                isSweepStarted = true,
                calibration = baseCal,
                activePresets = ActivePresetState(
                    shieldPreset = holdMode.shieldPreset,
                    emitterClassPreset = null,
                    environmentPreset = environment,
                    autoOverriddenTxPowerDbm = null,
                    screenOutwardMode = holdMode.screenOutwardMode
                ),
                userPose = pose,
                gridSnapshot = bayesianGridFilter.createSnapshot(pose.xMeters, pose.yMeters),
                discoveredEmitters = emptyList(),
                backgroundEmitters = emptyList()
            )
        }

        if (_uiState.value.hasPermissions) {
            offlineMapTileManager.refreshAnchorFromLocationManager()
            startSensorsAndRadios()
        }
        _statusMessages.tryEmit("Sweep started · ${holdMode.title} · ${environment.shortLabel}")
    }

    fun onActivityStarted() {
        refreshHardwareAndPermissionState()
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
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val epochNow = System.currentTimeMillis()
        val pose = imuEngine.pose.value
        val currentCalibration = _uiState.value.calibration

        backgroundEmitterTracker.ingest(m, pose, currentCalibration)

        var smoothedRssi = RssiKalmanFilter.sanitizeNegativeDbm(m.rssiDbm.toDouble())
        synchronized(registryLock) {
            val registryKey = m.fingerprintHash
            val existing = emitterRegistry[registryKey]
            if (existing == null) {
                val droneRes = DroneDetectorEngine.detect(
                    macAddress = m.macAddress,
                    transport = m.transport,
                    displayName = m.displayName,
                    bleDecoded = m.bleDecoded,
                    wifiVendorOuiHexes = m.wifiVendorOuiHexes
                )
                val created = EmitterRecord(
                    macAddress = m.macAddress,
                    fingerprintHash = m.fingerprintHash,
                    displayName = m.displayName,
                    transport = m.transport,
                    isRttResponder = m.isRttResponder,
                    lastRssiDbm = m.rssiDbm,
                    lastSeenElapsedMs = now,
                    lastSeenElapsedNs = nowNs,
                    firstSeenEpochMs = epochNow,
                    lastSeenEpochMs = epochNow,
                    scanResult = m.scanResult,
                    bleDecoded = m.bleDecoded,
                    wifiCapabilities = m.wifiCapabilities,
                    wifiVendorOuiHexes = m.wifiVendorOuiHexes,
                    frequencyMhz = m.frequencyMhz,
                    channelNumber = m.channelNumber,
                    channelWidthMhz = m.channelWidthMhz,
                    wifiStandardLabel = m.wifiStandardLabel,
                    wifiIeTags = m.wifiIeTags,
                    wifiVendorOuis = m.wifiVendorOuis,
                    wpsDeviceInfo = m.wpsDeviceInfo,
                    droneProtocol = if (droneRes.isDrone) droneRes.protocolLabel else null,
                    isDroneSuspect = droneRes.isDrone,
                    totalPacketCount = 1
                )
                created.linkedMacs.add(m.macAddress)
                if (m.rssiDbm >= -75) {
                    created.strongPositions.addLast(Point2D(pose.xMeters, pose.yMeters))
                }
                smoothedRssi = created.kalman.update(m.rssiDbm.toDouble())
                created.packetTimestampsMs.addLast(now)
                created.rssiHistory.addLast(RssiSamplePoint(m.rssiDbm, smoothedRssi.toFloat(), now))
                emitterRegistry[registryKey] = created
            } else {
                val dtMs = (nowNs - existing.lastSeenElapsedNs) / 1_000_000.0
                if (dtMs in 5.0..10_000.0) {
                    existing.interArrivalIntervalsMs.addLast(dtMs)
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
                existing.macAddress = m.macAddress
                existing.linkedMacs.add(m.macAddress)
                existing.lastRssiDbm = m.rssiDbm
                existing.lastSeenElapsedMs = now
                existing.lastSeenElapsedNs = nowNs
                existing.lastSeenEpochMs = epochNow
                existing.totalPacketCount++
                if (m.isRttResponder) existing.isRttResponder = true
                if (m.scanResult != null) existing.scanResult = m.scanResult
                if (m.bleDecoded != null) existing.bleDecoded = m.bleDecoded
                if (m.wifiCapabilities != null) existing.wifiCapabilities = m.wifiCapabilities
                if (m.wifiVendorOuiHexes.isNotEmpty()) existing.wifiVendorOuiHexes = m.wifiVendorOuiHexes
                if (m.frequencyMhz != null) existing.frequencyMhz = m.frequencyMhz
                if (m.channelNumber != null) existing.channelNumber = m.channelNumber
                if (m.channelWidthMhz != null) existing.channelWidthMhz = m.channelWidthMhz
                if (m.wifiStandardLabel != null) existing.wifiStandardLabel = m.wifiStandardLabel
                if (m.wifiIeTags.isNotEmpty()) existing.wifiIeTags = m.wifiIeTags
                if (m.wifiVendorOuis.isNotEmpty()) existing.wifiVendorOuis = m.wifiVendorOuis
                if (m.wpsDeviceInfo != null) existing.wpsDeviceInfo = m.wpsDeviceInfo

                if (m.rssiDbm >= -75) {
                    val lastPos = existing.strongPositions.lastOrNull()
                    if (lastPos == null || kotlin.math.hypot(pose.xMeters - lastPos.x, pose.yMeters - lastPos.y) >= 10.0) {
                        existing.strongPositions.addLast(Point2D(pose.xMeters, pose.yMeters))
                        while (existing.strongPositions.size > 16) {
                            existing.strongPositions.removeFirst()
                        }
                    }
                }

                val droneRes = DroneDetectorEngine.detect(
                    macAddress = m.macAddress,
                    transport = m.transport,
                    displayName = existing.displayName,
                    bleDecoded = existing.bleDecoded ?: m.bleDecoded,
                    wifiVendorOuiHexes = existing.wifiVendorOuiHexes
                )
                if (droneRes.isDrone) {
                    existing.isDroneSuspect = true
                    existing.droneProtocol = droneRes.protocolLabel
                }

                smoothedRssi = existing.kalman.update(m.rssiDbm.toDouble())
                existing.packetTimestampsMs.addLast(now)
                pruneTimestamps(existing.packetTimestampsMs, now)
                existing.rssiHistory.addLast(RssiSamplePoint(m.rssiDbm, smoothedRssi.toFloat(), now))
                while (existing.rssiHistory.size > MAX_SPARKLINE_SAMPLES) {
                    existing.rssiHistory.removeFirst()
                }
            }
        }

        val isMuted = m.fingerprintHash in mutedFingerprints

        // Watchlist & Perimeter evaluation (suppressed if muted)
        val watchlistHit = if (!isMuted) {
            WatchlistRepository.matchEntries(
                entries = cachedWatchlist,
                macAddress = m.macAddress,
                displayName = m.displayName,
                fingerprintHash = m.fingerprintHash
            )
        } else {
            null
        }
        if (watchlistHit != null && now - lastWatchlistHapticMs > 4_000L) {
            lastWatchlistHapticMs = now
            triggerDoubleHapticPulse()
            _uiState.update {
                it.copy(
                    watchlistHitBanner = "WATCHLIST HIT: ${watchlistHit.label} (${m.displayName})"
                )
            }
        }

        val estDist = BackgroundEmitterTracker.estimateDistanceMeters(
            kalmanRssiDbm = smoothedRssi,
            txPowerP0Dbm = currentCalibration.txPowerP0Dbm.toDouble(),
            pathLossExponentN = currentCalibration.pathLossExponentN.toDouble()
        )
        val isLockedMatch = m.macAddress == lockedMac ||
            (lockedFingerprint != null && m.fingerprintHash == lockedFingerprint)
        val perimeterEvent = if (!isMuted) {
            perimeterGuardEngine.evaluatePacket(
                emitterKey = m.fingerprintHash,
                emitterName = m.displayName,
                distanceMeters = estDist,
                rssiDbm = m.rssiDbm,
                isWatchlistMatch = watchlistHit != null,
                isLockedTarget = isLockedMatch,
                timestampMs = epochNow
            )
        } else {
            null
        }
        if (perimeterEvent != null) {
            triggerDoubleHapticPulse()
            viewModelScope.launch {
                val updated = watchlistRepository.addPerimeterEvent(perimeterEvent)
                _uiState.update { it.copy(perimeterEvents = updated) }
            }
            _statusMessages.tryEmit(
                "PERIMETER ${perimeterEvent.eventType}: ${perimeterEvent.emitterName} (${String.format(java.util.Locale.US, "%.1fm", estDist)})"
            )
        }

        val currentLocked = lockedMac ?: return
        val currentFp = lockedFingerprint
        val matchesLocked = m.macAddress == currentLocked ||
            (currentFp != null && currentFp.startsWith("BLE_FP:") && m.fingerprintHash == currentFp)
        if (!matchesLocked) return
        if (m.macAddress != currentLocked) {
            lockedMac = m.macAddress
        }

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
            val hampelFilteredRssi = lockedTargetHampelFilter.update(m.rssiDbm.toDouble())
            val tiltGuardActive = pose.hasOrientationReading && pose.isShieldTiltGuarded
            bayesianGridFilter.updateWithShieldedRssi(
                userX = pose.xMeters,
                userY = pose.yMeters,
                userHeadingRad = pose.headingRad,
                measuredRssiDbm = hampelFilteredRssi,
                params = effectiveParams,
                tiltGuardActive = tiltGuardActive
            )
        }

        val prevMaxRssi = sessionMaxRssi
        if (prevMaxRssi == null || m.rssiDbm > prevMaxRssi) {
            sessionMaxRssi = m.rssiDbm
        }

        gridDirty = true

        _uiState.update { state ->
            state.copy(
                lockedTargetMac = m.macAddress,
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
                        lockedMac = targetMac,
                        lockedFingerprint = lockedFingerprint
                    ).filterNot { it.fingerprintHash in mutedFingerprints }
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
                val topDrone = updatedEmitters?.firstOrNull { it.isDroneSuspect }

                _uiState.update { state ->
                    val droneList = updatedEmitters ?: state.discoveredEmitters
                    val droneTop = if (updatedEmitters != null) topDrone else droneList.firstOrNull { it.isDroneSuspect }
                    val droneBanner = if (droneTop != null) {
                        "DRONE DETECTED · ${droneTop.droneProtocol ?: "UAV"} · ${droneTop.displayName} (${droneTop.rssiDbm} dBm)"
                    } else {
                        null
                    }
                    state.copy(
                        gridSnapshot = snapshot ?: state.gridSnapshot,
                        sweepCoachState = updatedCoach ?: state.sweepCoachState,
                        discoveredEmitters = updatedEmitters ?: state.discoveredEmitters,
                        backgroundEmitters = updatedBackground ?: state.backgroundEmitters,
                        activeDossier = if (dossierKey == null) null else (updatedDossier ?: state.activeDossier),
                        lockedTargetPacketRateHz = lockedRateHz,
                        lockedTargetIsStale = isStale,
                        sessionMinDistanceMeters = sessionMinDist,
                        geigerPulseRateHz = pulseHz,
                        droneAlertBanner = droneBanner,
                        topDroneEmitterMac = droneTop?.macAddress
                    )
                }
            }
        }
    }

    private fun buildSortedEmitterList(nowElapsedMs: Long): List<DiscoveredEmitter> {
        val rawRecords = mutableListOf<DiscoveredEmitter>()
        val ssidCapabilitiesMap = HashMap<String, MutableSet<String>>()

        synchronized(registryLock) {
            val iterator = emitterRegistry.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val rec = entry.value
                val isCurrentlyLocked = rec.macAddress == lockedMac ||
                    (lockedFingerprint != null && rec.fingerprintHash == lockedFingerprint)
                if (!isCurrentlyLocked && (nowElapsedMs - rec.lastSeenElapsedMs) > EMITTER_EXPIRY_MS) {
                    iterator.remove()
                    continue
                }
                pruneTimestamps(rec.packetTimestampsMs, nowElapsedMs)
                val rateHz = rec.packetTimestampsMs.size.toFloat() / (RATE_WINDOW_MS / 1000.0f)

                if (rec.transport == EmitterTransport.WIFI &&
                    rec.displayName.isNotBlank() &&
                    rec.displayName != "Hidden AP"
                ) {
                    val normCap = normalizeWifiSecurity(rec.wifiCapabilities)
                    ssidCapabilitiesMap.getOrPut(rec.displayName) { mutableSetOf() }.add(normCap)
                }

                val isWatchlist = WatchlistRepository.matchEntries(
                    entries = cachedWatchlist,
                    macAddress = rec.macAddress,
                    displayName = rec.displayName,
                    fingerprintHash = rec.fingerprintHash
                ) != null

                rawRecords.add(
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
                        observedMacCount = rec.linkedMacs.size.coerceAtLeast(
                            backgroundEmitterTracker.observedMacCountFor(rec.fingerprintHash)
                        ),
                        linkedMacs = rec.linkedMacs.toList(),
                        wifiCapabilities = rec.wifiCapabilities,
                        droneProtocol = rec.droneProtocol,
                        isDroneSuspect = rec.isDroneSuspect,
                        isWatchlistMatch = isWatchlist,
                        isMuted = rec.fingerprintHash in mutedFingerprints
                    )
                )
            }
        }

        val list = EvilTwinDetector.flagEvilTwins(rawRecords)

        return list.sortedWith(
            compareByDescending<DiscoveredEmitter> { it.isDroneSuspect }
                .thenByDescending { it.isWatchlistMatch }
                .thenByDescending { it.isRttResponder }
                .thenByDescending { it.rssiDbm }
                .thenByDescending { it.packetRateHz }
        )
    }

    private fun normalizeWifiSecurity(capabilities: String?): String {
        val cap = capabilities?.uppercase(java.util.Locale.US) ?: return "OPEN"
        return when {
            cap.contains("SAE") || cap.contains("WPA3") -> "WPA3"
            cap.contains("WPA2") || cap.contains("RSN") -> "WPA2"
            cap.contains("WPA") -> "WPA"
            cap.contains("WEP") -> "WEP"
            else -> "OPEN"
        }
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

        val timingAndHistory = if (rec != null) {
            synchronized(registryLock) {
                val intervals = rec.interArrivalIntervalsMs.toList()
                val stats = SigintForensics.computeTimingStats(
                    intervalsMs = intervals,
                    isWifi = rec.transport == EmitterTransport.WIFI
                )
                Triple(stats, rec.rssiHistory.toList(), rec.strongPositions.toList())
            }
        } else {
            Triple(com.example.dsp.TimingStats(0f, 0f, 0f), emptyList(), emptyList())
        }
        val stats = timingAndHistory.first
        val history = timingAndHistory.second
        val strongPositions = timingAndHistory.third

        val macHistory = backgroundEmitterTracker.observedMacHistoryFor(fpHash)
        val pose = imuEngine.pose.value
        val operatorWalkedM = pose.stepCount * _uiState.value.calibration.stepLengthMeters.toDouble()
        val tailChaseScore = SigintForensics.computeTailChaseScore(strongPositions, operatorWalkedM)

        val estX = bg?.estimatedX?.toDouble() ?: pose.xMeters
        val estY = bg?.estimatedY?.toDouble() ?: pose.yMeters
        val enuEast = (estX - pose.xMeters).toFloat()
        val enuNorth = (estY - pose.yMeters).toFloat()
        val estDist = bg?.estimatedDistanceMeters
        val anchorGps = _uiState.value.tileCacheState.anchorGps
        val wgs84 = if (anchorGps != null) {
            OfflineMapTileManager.gridMetersToLatLon(
                gridX = estX,
                gridY = estY,
                anchorLat = anchorGps.latitude,
                anchorLon = anchorGps.longitude
            )
        } else {
            null
        }

        val bearingDegTrue = bg?.peakBearingRad?.let { rad ->
            val deg = (90.0 - Math.toDegrees(rad))
            (((deg % 360.0) + 360.0) % 360.0).toInt()
        }
        val isLocked = lockedMac != null && (mac == lockedMac || fpHash == _uiState.value.lockedTargetFingerprint)
        val locMode = when {
            rec?.isRttResponder == true -> "802.11mc RTT"
            _uiState.value.calibration.shieldDropDb > 0f && !pose.isShieldTiltGuarded -> "Body Shadow DF"
            else -> "Omni SAR"
        }
        val spreadDeg = bg?.angularSpreadDeg ?: 180f
        val bearingConfPct = ((1.0f - (spreadDeg / 180.0f)) * 100f).toInt().coerceIn(5, 99)

        val transport = rec?.transport ?: bg!!.transport
        val protoProfile = when (transport) {
            EmitterTransport.WIFI -> {
                val std = rec?.wifiStandardLabel ?: "802.11"
                val bw = rec?.channelWidthMhz?.let { "${it}MHz" } ?: "20MHz"
                "$std / $bw"
            }
            EmitterTransport.BLE -> {
                if (rec?.bleDecoded?.serviceUuids?.isNotEmpty() == true) "BLE 5.0 / GATT-Adv" else "BLE Legacy Adv"
            }
            EmitterTransport.EXTERNAL_USB -> "USB Bridge RF"
        }
        val freqLabel = when (transport) {
            EmitterTransport.WIFI -> {
                val f = rec?.frequencyMhz
                val ch = rec?.channelNumber
                if (f != null && ch != null && ch > 0) "$f MHz (Ch $ch)" else f?.let { "$it MHz" } ?: "2.4 / 5 GHz"
            }
            EmitterTransport.BLE -> "2402–2480 MHz (Ch 37/38/39)"
            EmitterTransport.EXTERNAL_USB -> "External"
        }
        val capsSummary = when (transport) {
            EmitterTransport.WIFI -> rec?.wifiCapabilities ?: "ESS"
            EmitterTransport.BLE -> rec?.bleDecoded?.summaryLine ?: "No BLE AD payload"
            EmitterTransport.EXTERNAL_USB -> "External"
        }
        val primaryVendor = rec?.wifiVendorOuis?.firstOrNull()?.second
            ?: rec?.bleDecoded?.companyName

        val isInsidePerimeter = if (_uiState.value.perimeterEnabled && estDist != null) {
            estDist <= _uiState.value.perimeterRadiusMeters
        } else {
            null
        }
        val isWatched = WatchlistRepository.matchEntries(
            entries = cachedWatchlist,
            macAddress = mac,
            displayName = rec?.displayName ?: bg!!.displayName,
            fingerprintHash = fpHash
        ) != null

        return EmitterDossierData(
            fingerprintHash = fpHash,
            macAddress = mac,
            displayName = rec?.displayName ?: bg!!.displayName,
            transport = transport,
            isRttResponder = rec?.isRttResponder ?: bg!!.isRttResponder,
            latestRawRssiDbm = rec?.lastRssiDbm ?: bg!!.maxRssiDbm,
            latestKalmanRssiDbm = rec?.kalman?.currentEstimateDbm()?.toFloat() ?: bg!!.kalmanRssiDbm,
            packetRateHz = rateHz,
            meanDeltaMs = stats.meanDeltaMs,
            interArrivalJitterMs = stats.jitterSigmaMs,
            clockSkewPpm = stats.clockSkewPpm,
            rssiHistory = history,
            macHistory = macHistory,
            bleDecoded = rec?.bleDecoded,
            shapeStage = bg?.shapeStage,
            angularSpreadDeg = bg?.angularSpreadDeg,
            estimatedDistanceMeters = estDist,
            isPinned = bg?.isSavedInLog ?: false,
            isLocked = isLocked,
            scanResult = rec?.scanResult,
            primaryVendorLabel = primaryVendor,
            bearingDegTrue = bearingDegTrue,
            bearingConfidencePct = bearingConfPct,
            localizationModeLabel = locMode,
            baselineMeters = bg?.baselineMeters ?: 0f,
            enuEastMeters = enuEast,
            enuNorthMeters = enuNorth,
            wgs84Lat = wgs84?.first,
            wgs84Lon = wgs84?.second,
            isInsidePerimeter = isInsidePerimeter,
            activeGeofenceCount = if (_uiState.value.perimeterEnabled) 1 else 0,
            protocolProfile = protoProfile,
            frequencyChannelLabel = freqLabel,
            capabilitiesSummary = capsSummary,
            wifiIeTags = rec?.wifiIeTags ?: emptyList(),
            wifiVendorOuis = rec?.wifiVendorOuis ?: emptyList(),
            wpsDeviceInfo = rec?.wpsDeviceInfo,
            tailChaseScore = tailChaseScore,
            firstSeenEpochMs = rec?.firstSeenEpochMs ?: System.currentTimeMillis(),
            lastSeenEpochMs = rec?.lastSeenEpochMs ?: System.currentTimeMillis(),
            isWatched = isWatched,
            isMuted = fpHash in mutedFingerprints,
            totalPacketCount = rec?.totalPacketCount ?: history.size
        )
    }

    private fun computePacketRateHz(macOrFp: String, nowElapsedMs: Long): Float {
        synchronized(registryLock) {
            val rec = emitterRegistry[macOrFp]
                ?: emitterRegistry.values.firstOrNull { it.macAddress == macOrFp }
                ?: return 0f
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
        lockedFingerprint = emitter.fingerprintHash
        lockedScanResult = emitter.scanResult
        lastLockedPacketElapsedMs = SystemClock.elapsedRealtime()
        sessionMinDist = null
        sessionMaxRssi = emitter.rssiDbm

        val pose = imuEngine.pose.value
        lockedTargetHampelFilter.reset()
        bayesianGridFilter.resetToUniform(pose.xMeters, pose.yMeters)
        gridDirty = true

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
        lockedFingerprint = null
        lockedScanResult = null
        lastLockedPacketElapsedMs = 0L
        sessionMinDist = null
        sessionMaxRssi = null
        rttPollingJob?.cancel()
        rttPollingJob = null

        lockedTargetHampelFilter.reset()
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

    fun resetHeatmapOnly() {
        imuEngine.clearTrail()
        val pose = imuEngine.pose.value
        lockedTargetHampelFilter.reset()
        bayesianGridFilter.resetToUniform(pose.xMeters, pose.yMeters)
        sessionMinDist = null
        gridDirty = true

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
        _statusMessages.tryEmit("Heatmap, trail & triangulation coach reset (Step 1)")
    }

    fun purgeScans() {
        backgroundEmitterTracker.clear()
        synchronized(registryLock) {
            emitterRegistry.clear()
        }
        perimeterGuardEngine.reset()
        _uiState.update { state ->
            state.copy(
                discoveredEmitters = emptyList(),
                backgroundEmitters = emptyList(),
                droneAlertBanner = null,
                topDroneEmitterMac = null,
                watchlistHitBanner = null
            )
        }
        standaloneSource.triggerWifiScan()
        _statusMessages.tryEmit("Purged all background scans & restarted radio discovery")
    }

    fun resetGridAndPose() {
        resetHeatmapOnly()
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setFollowMeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(followMeEnabled = enabled) }
    }

    fun setRadarZoomWidth(widthMeters: Float) {
        val clamped = widthMeters.coerceIn(
            RadarViewportMath.MIN_ZOOM_WIDTH_M,
            RadarViewportMath.MAX_ZOOM_WIDTH_M
        )
        _uiState.update { it.copy(radarZoomWidthMeters = clamped) }
    }

    fun cycleRadarZoom() {
        val current = _uiState.value.radarZoomWidthMeters
        val next = when {
            current < 17.5f -> 25.0f
            current < 37.5f -> 50.0f
            current < 75.0f -> 100.0f
            current < 125.0f -> 150.0f
            else -> 10.0f
        }
        _uiState.update { it.copy(radarZoomWidthMeters = next) }
    }

    fun toggleMuteEmitter(fingerprintHash: String) {
        val nowMuted = if (fingerprintHash in mutedFingerprints) {
            mutedFingerprints.remove(fingerprintHash)
            false
        } else {
            mutedFingerprints.add(fingerprintHash)
            true
        }
        val active = _uiState.value.activeDossier
        _uiState.update { state ->
            state.copy(
                activeDossier = if (active?.fingerprintHash == fingerprintHash) {
                    active.copy(isMuted = nowMuted)
                } else {
                    active
                }
            )
        }
        _statusMessages.tryEmit(if (nowMuted) "Emitter muted from radar & alarms" else "Emitter unmuted")
    }

    fun toggleLabels() {
        _uiState.update { it.copy(showLabels = !it.showLabels) }
    }

    fun lockCellTower(tower: CellTowerInfo) {
        _uiState.update {
            it.copy(
                lockedCellTower = tower,
                activeTab = MainTab.RADAR
            )
        }
        _statusMessages.tryEmit("Tracking Cell Tower PCI ${tower.pci ?: "--"} on Radar")
    }

    fun unlockCellTower() {
        _uiState.update { it.copy(lockedCellTower = null) }
    }

    fun lockTopDrone() {
        val mac = _uiState.value.topDroneEmitterMac ?: return
        val emitter = _uiState.value.discoveredEmitters.firstOrNull { it.macAddress == mac } ?: return
        lockTarget(emitter)
        _uiState.update { it.copy(activeTab = MainTab.RADAR) }
    }

    fun setDroneSlantAltitude(altMeters: Float) {
        _uiState.update { it.copy(droneSlantAltitudeMeters = altMeters.coerceIn(0f, 120f)) }
    }

    fun dismissWatchlistBanner() {
        _uiState.update { it.copy(watchlistHitBanner = null) }
    }

    fun addWatchlistEntry(matchType: WatchlistMatchType, pattern: String, label: String) {
        if (pattern.isBlank()) return
        viewModelScope.launch {
            val updated = watchlistRepository.addWatchlistEntry(
                WatchlistEntry(
                    matchType = matchType,
                    pattern = pattern.trim(),
                    label = label.ifBlank { pattern.trim() }
                )
            )
            cachedWatchlist = updated
            _uiState.update { it.copy(watchlistEntries = updated) }
            _statusMessages.tryEmit("Added to Watchlist: ${label.ifBlank { pattern.trim() }}")
        }
    }

    fun watchFingerprintFromDossier(dossier: EmitterDossierData) {
        val existing = cachedWatchlist.firstOrNull {
            it.matchType == WatchlistMatchType.FINGERPRINT &&
                it.pattern.equals(dossier.fingerprintHash, ignoreCase = true)
        }
        if (existing != null) {
            deleteWatchlistEntry(existing.id)
            _uiState.update { state ->
                state.copy(
                    activeDossier = if (state.activeDossier?.fingerprintHash == dossier.fingerprintHash) {
                        state.activeDossier.copy(isWatched = false)
                    } else {
                        state.activeDossier
                    }
                )
            }
            _statusMessages.tryEmit("Removed ${dossier.displayName} from Watchlist")
        } else {
            addWatchlistEntry(
                matchType = WatchlistMatchType.FINGERPRINT,
                pattern = dossier.fingerprintHash,
                label = dossier.displayName
            )
            _uiState.update { state ->
                state.copy(
                    activeDossier = if (state.activeDossier?.fingerprintHash == dossier.fingerprintHash) {
                        state.activeDossier.copy(isWatched = true)
                    } else {
                        state.activeDossier
                    }
                )
            }
        }
    }

    fun deleteWatchlistEntry(id: Long) {
        viewModelScope.launch {
            val updated = watchlistRepository.deleteWatchlistEntry(id)
            cachedWatchlist = updated
            _uiState.update { it.copy(watchlistEntries = updated) }
        }
    }

    fun setPerimeterEnabled(enabled: Boolean) {
        perimeterGuardEngine.enabled = enabled
        _uiState.update { it.copy(perimeterEnabled = enabled) }
    }

    fun setPerimeterRadius(radiusMeters: Float) {
        val clamped = radiusMeters.coerceIn(
            PerimeterGuardEngine.MIN_RADIUS_METERS,
            PerimeterGuardEngine.MAX_RADIUS_METERS
        )
        perimeterGuardEngine.radiusMeters = clamped
        _uiState.update { it.copy(perimeterRadiusMeters = clamped) }
    }

    fun setPerimeterTriggerMode(mode: PerimeterTriggerMode) {
        perimeterGuardEngine.triggerMode = mode
        _uiState.update { it.copy(perimeterTriggerMode = mode) }
    }

    fun setPerimeterScope(scope: PerimeterScope) {
        perimeterGuardEngine.scope = scope
        _uiState.update { it.copy(perimeterScope = scope) }
    }

    fun clearPerimeterEvents() {
        viewModelScope.launch {
            watchlistRepository.clearPerimeterEvents()
            _uiState.update { it.copy(perimeterEvents = emptyList()) }
        }
    }

    private fun triggerDoubleHapticPulse() {
        val ctx = getApplication<Application>()
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() == true) {
            val timings = longArrayOf(0L, 90L, 80L, 120L)
            val amplitudes = intArrayOf(0, 220, 0, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        }
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

    fun toggleBackgroundPins() {
        val next = !_uiState.value.showBackgroundPins
        _uiState.update { it.copy(showBackgroundPins = next) }
        _statusMessages.tryEmit(
            if (next) "Background signal pins visible" else "Background signal pins hidden (declutter)"
        )
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

    fun toggleScreenOutwardMode() {
        val next = !_uiState.value.activePresets.screenOutwardMode
        imuEngine.setScreenOutwardMode(next)
        _uiState.update { state ->
            state.copy(
                activePresets = state.activePresets.copy(screenOutwardMode = next)
            )
        }
        _statusMessages.tryEmit(
            if (next) "Screen Outward (+180°) enabled for breast-pocket carry"
            else "Default Rear-Camera (-Z) forward axis active"
        )
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
        imuEngine.setScreenOutwardMode(false)
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
            lockedMac = mac,
            lockedFingerprint = state.lockedTargetFingerprint
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
        standaloneSource.shutdown()
        cellularMonitor.shutdown()
        gnssRfMonitor.shutdown()
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
