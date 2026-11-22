package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ExportFormat
import com.example.dsp.DroneDetectorEngine
import com.example.dsp.HardwareStatus
import com.example.dsp.LocalizationTier
import com.example.dsp.MagneticFieldStatus
import com.example.dsp.PowerMode
import com.example.dsp.ShieldPreset
import com.example.dsp.SweepCoachState
import com.example.dsp.SweepStage
import com.example.dsp.UserPose
import com.example.maps.TileCacheState
import com.example.sensors.GnssInterferenceLevel
import com.example.sensors.GnssRfStatus
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ReadoutInkMuted
import com.example.ui.theme.ReadoutInkPrimary
import com.example.ui.theme.ReadoutInkSecondary
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TacticalCrimson
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.TelemetrySubValueStyle
import com.example.ui.theme.TelemetryValueStyle
import com.example.ui.theme.ThermalAmber
import com.example.ui.theme.ThermalYellow
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun RadarScreen(
    viewModel: RadarViewModel,
    onRequestPermissions: () -> Unit,
    onRequestPhoneStatePermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestExportFile: (ExportFormat) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showScannerDialog by remember { mutableStateOf(false) }
    var showSessionLogDialog by remember { mutableStateOf(false) }
    var showFieldGuideDialog by remember { mutableStateOf(false) }
    var calibrationExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.statusMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        containerColor = ObsidianBackground,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (uiState.hasPermissions && uiState.hasCompletedFirstRunWalkthrough) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (uiState.activeTab == MainTab.RADAR) {
                        CalibrationDrawer(
                            calibration = uiState.calibration,
                            activePresets = uiState.activePresets,
                            expanded = calibrationExpanded,
                            onToggleExpanded = { calibrationExpanded = !calibrationExpanded },
                            onApplyShieldPreset = viewModel::applyShieldPreset,
                            onToggleScreenOutward = viewModel::toggleScreenOutwardMode,
                            onApplyEmitterClassPreset = viewModel::applyEmitterClassPreset,
                            onApplyEnvironmentPreset = viewModel::applyEnvironmentPreset,
                            onShieldDropChange = viewModel::updateShieldDropDb,
                            onTxPowerChange = viewModel::updateTxPowerP0,
                            onPathLossChange = viewModel::updatePathLossExponent,
                            onStepLengthChange = viewModel::updateStepLengthMeters,
                            onRestoreDefaults = viewModel::restoreDefaultCalibration
                        )
                    }
                    NavigationBar(
                        containerColor = CharcoalSurface,
                        contentColor = ReadoutInkPrimary
                    ) {
                        NavigationBarItem(
                            selected = uiState.activeTab == MainTab.RADAR,
                            onClick = { viewModel.selectTab(MainTab.RADAR) },
                            icon = { Icon(Icons.Filled.Radar, contentDescription = null) },
                            label = { Text("Radar", maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                unselectedIconColor = ReadoutInkSecondary,
                                unselectedTextColor = ReadoutInkSecondary,
                                indicatorColor = ElectricCyan.copy(alpha = 0.14f)
                            ),
                            modifier = Modifier.testTag("nav_tab_radar")
                        )
                        NavigationBarItem(
                            selected = uiState.activeTab == MainTab.SIGNALS,
                            onClick = { viewModel.selectTab(MainTab.SIGNALS) },
                            icon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                            label = { Text("Signals (${uiState.discoveredEmitters.size})", maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                unselectedIconColor = ReadoutInkSecondary,
                                unselectedTextColor = ReadoutInkSecondary,
                                indicatorColor = ElectricCyan.copy(alpha = 0.14f)
                            ),
                            modifier = Modifier.testTag("nav_tab_signals")
                        )
                        NavigationBarItem(
                            selected = uiState.activeTab == MainTab.PERIMETER_LOG,
                            onClick = { viewModel.selectTab(MainTab.PERIMETER_LOG) },
                            icon = { Icon(Icons.Filled.Security, contentDescription = null) },
                            label = { Text("Perimeter", maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ThermalAmber,
                                selectedTextColor = ThermalAmber,
                                unselectedIconColor = ReadoutInkSecondary,
                                unselectedTextColor = ReadoutInkSecondary,
                                indicatorColor = ThermalAmber.copy(alpha = 0.14f)
                            ),
                            modifier = Modifier.testTag("nav_tab_perimeter")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (!uiState.hasPermissions) {
            PermissionGateView(
                missingPermissions = uiState.missingPermissions,
                onRequestPermissions = onRequestPermissions,
                onOpenAppSettings = onOpenAppSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else if (!uiState.hasCompletedFirstRunWalkthrough) {
            FirstRunWalkthroughView(
                onComplete = viewModel::completeFirstRunWalkthrough,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            when (uiState.activeTab) {
                MainTab.RADAR -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .widthIn(max = 600.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 1. Top Tactical HUD (Row 1: Read-only hardware pills + Row 2: Single scrollable action chip bar)
                            TopTacticalHud(
                                uiState = uiState,
                                onToggleBackgroundPins = viewModel::toggleBackgroundPins,
                                onToggleLabels = viewModel::toggleLabels,
                                onToggleFollowMe = { viewModel.setFollowMeEnabled(!uiState.followMeEnabled) },
                                onCycleZoom = viewModel::cycleRadarZoom,
                                onCycleCarryingMode = viewModel::cycleQuickCarryingMode,
                                onToggleScreenOutward = viewModel::toggleScreenOutwardMode,
                                onToggleMapOverlay = viewModel::toggleMapOverlay,
                                onCacheArea = { viewModel.cacheOfflineMapArea() },
                                onTogglePowerMode = viewModel::togglePowerMode,
                                onToggleGeiger = viewModel::toggleGeigerFeedback,
                                onOpenFieldGuide = { showFieldGuideDialog = true },
                                onResetGnssBaseline = viewModel::resetGnssBaseline
                            )

                            // Always-on Crimson DRONE DETECTED Banner
                            val droneBanner = uiState.droneAlertBanner
                            if (droneBanner != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(TacticalCrimson.copy(alpha = 0.22f))
                                        .border(1.dp, TacticalCrimson, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("drone_detected_banner"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Flight,
                                        contentDescription = null,
                                        tint = TacticalCrimson,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = droneBanner,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ReadoutInkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (uiState.lockedTargetMac != null) {
                                            val groundDist = DroneDetectorEngine.slantToGroundDistance(
                                                uiState.gridSnapshot.estimatedDistanceMeters.toDouble(),
                                                uiState.droneSlantAltitudeMeters.toDouble()
                                            )
                                            Text(
                                                text = String.format(
                                                    Locale.US,
                                                    "3D Slant %.1fm -> Ground %.1fm (Alt %.0fm)",
                                                    uiState.gridSnapshot.estimatedDistanceMeters,
                                                    groundDist,
                                                    uiState.droneSlantAltitudeMeters
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ThermalAmber
                                            )
                                        }
                                    }
                                    Surface(
                                        onClick = viewModel::lockTopDrone,
                                        shape = RoundedCornerShape(8.dp),
                                        color = TacticalCrimson
                                    ) {
                                        Text(
                                            text = "TRACK DRONE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ReadoutInkPrimary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            // Watchlist Hit Banner
                            val wlBanner = uiState.watchlistHitBanner
                            if (wlBanner != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ThermalAmber.copy(alpha = 0.20f))
                                        .border(1.dp, ThermalAmber, RoundedCornerShape(10.dp))
                                        .clickable { viewModel.dismissWatchlistBanner() }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("watchlist_hit_banner"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Filled.Warning, contentDescription = null, tint = ThermalAmber, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = wlBanner,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ReadoutInkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // 2. Target Lock Pill + Guided Two-Spin Coach Banner
                            if (uiState.lockedTargetMac != null) {
                                TargetSelectorAndCoachBanner(
                                    uiState = uiState,
                                    onOpenScanner = { viewModel.selectTab(MainTab.SIGNALS) },
                                    onUnlockTarget = viewModel::unlockTarget
                                )
                            }

                            // Optional Cell Tower SAR/TA Lock Strip
                            val towerLock = uiState.lockedCellTower
                            if (towerLock != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SignalEmerald.copy(alpha = 0.14f))
                                        .border(1.dp, SignalEmerald.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "CELL TRACK: ${towerLock.generation.shortLabel} PCI ${towerLock.pci ?: "--"} · TA ${towerLock.timingAdvanceDistanceMeters?.roundToInt() ?: "--"}m",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SignalEmerald,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "CLEAR",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TacticalCrimson,
                                        modifier = Modifier
                                            .clickable(onClick = viewModel::unlockCellTower)
                                            .padding(4.dp)
                                    )
                                }
                            }

                            // 3. Full-Height Bayesian Thermal Plasma Radar Canvas
                            RadarCanvas(
                                gridSnapshot = uiState.gridSnapshot,
                                userPose = uiState.userPose,
                                hasLockedTarget = uiState.lockedTargetMac != null,
                                lockedTargetMac = uiState.lockedTargetMac,
                                lockedTargetFingerprint = uiState.lockedTargetFingerprint,
                                lockedTargetLabel = uiState.lockedTargetName,
                                backgroundEmitters = uiState.backgroundEmitters,
                                showBackgroundPins = uiState.showBackgroundPins,
                                showLabels = uiState.showLabels,
                                followMeEnabled = uiState.followMeEnabled,
                                zoomWidthMeters = uiState.radarZoomWidthMeters,
                                lockedCellTower = uiState.lockedCellTower,
                                perimeterEnabled = uiState.perimeterEnabled,
                                perimeterRadiusMeters = uiState.perimeterRadiusMeters,
                                sweepCoachState = uiState.sweepCoachState,
                                tileCacheState = uiState.tileCacheState,
                                mapOverlayEnabled = uiState.mapOverlayEnabled,
                                offlineMapTileManager = viewModel.offlineMapTileManager,
                                onDisableFollowMe = { viewModel.setFollowMeEnabled(false) },
                                onEnableFollowMe = { viewModel.setFollowMeEnabled(true) },
                                onZoomWidthChange = viewModel::setRadarZoomWidth,
                                onTapBackgroundEmitter = { emitter ->
                                    viewModel.openDossierForBackgroundEmitter(emitter)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )

                            // 4. Compact Telemetry Readout Strip
                            TelemetryGridStrip(uiState = uiState)

                            // 5. Tactical Action Row (ZERO HDG, RST HEATMAP, PURGE SCANS, SAVE & PIN)
                            TacticalActionRow(
                                hasLockedTarget = uiState.lockedTargetMac != null,
                                isHeadingZeroed = uiState.userPose.isHeadingZeroed,
                                onZeroHeading = {
                                    if (uiState.userPose.isHeadingZeroed) {
                                        viewModel.clearHeadingOffset()
                                    } else {
                                        viewModel.zeroHeading()
                                    }
                                },
                                onResetHeatmap = viewModel::resetHeatmapOnly,
                                onPurgeScans = viewModel::purgeScans,
                                onSaveSnapshot = viewModel::saveCurrentSnapshot
                            )
                        }
                    }
                }

                MainTab.SIGNALS -> {
                    SignalsAndWatchlistTab(
                        uiState = uiState,
                        onInspectEmitter = { emitter ->
                            viewModel.openDossierForDiscoveredEmitter(emitter)
                        },
                        onLockEmitter = { emitter ->
                            viewModel.lockTarget(emitter)
                            viewModel.selectTab(MainTab.RADAR)
                        },
                        onRefreshWifiScan = viewModel::triggerManualWifiScan,
                        onRefreshCellular = viewModel::refreshCellularMonitor,
                        onRequestPhoneStatePermission = onRequestPhoneStatePermission,
                        onTrackTowerOnRadar = viewModel::lockCellTower,
                        onAddWatchlistEntry = viewModel::addWatchlistEntry,
                        onDeleteWatchlistEntry = viewModel::deleteWatchlistEntry,
                        modifier = Modifier.padding(innerPadding)
                    )
                }

                MainTab.PERIMETER_LOG -> {
                    PerimeterAndLogTab(
                        uiState = uiState,
                        onSetPerimeterEnabled = viewModel::setPerimeterEnabled,
                        onSetPerimeterRadius = viewModel::setPerimeterRadius,
                        onSetPerimeterTriggerMode = viewModel::setPerimeterTriggerMode,
                        onSetPerimeterScope = viewModel::setPerimeterScope,
                        onClearPerimeterEvents = viewModel::clearPerimeterEvents,
                        onCacheMapArea = { viewModel.cacheOfflineMapArea() },
                        onExport = onRequestExportFile,
                        onDeleteSnapshot = viewModel::deleteSnapshot,
                        onClearAllSnapshots = viewModel::clearAllSnapshots,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    // Cold-Launch Pre-Sweep Setup Gate (radios paused until user selects posture/environment & taps START SWEEP)
    if (uiState.hasPermissions && uiState.hasCompletedFirstRunWalkthrough && !uiState.isSweepStarted) {
        SweepSetupCheckDialog(
            onStartSweep = { holdMode, env ->
                viewModel.startSweepWithSetup(holdMode, env)
            },
            onReplayWalkthrough = viewModel::reopenFirstRunWalkthrough
        )
    }

    // Scanner Dialog (Auto-Freeze + 6 Tabs including Cell Towers on-demand READ_PHONE_STATE)
    if (showScannerDialog) {
        ScannerDialog(
            emitters = uiState.discoveredEmitters,
            cellularSnapshot = uiState.cellularSnapshot,
            lockedTargetMac = uiState.lockedTargetMac,
            onInspectEmitter = { emitter ->
                showScannerDialog = false
                viewModel.openDossierForDiscoveredEmitter(emitter)
            },
            onLockEmitter = { emitter ->
                viewModel.lockTarget(emitter)
                showScannerDialog = false
            },
            onRefreshWifiScan = viewModel::triggerManualWifiScan,
            onRefreshCellular = viewModel::refreshCellularMonitor,
            onRequestPhoneStatePermission = onRequestPhoneStatePermission,
            onDismiss = { showScannerDialog = false }
        )
    }

    // Passive Signal Dossier Sheet (opened by tapping any Radar Shape or Scanner row)
    val activeDossier = uiState.activeDossier
    if (activeDossier != null) {
        SignalDossierSheet(
            dossier = activeDossier,
            onLockTarget = { dossier ->
                viewModel.lockTargetFromDossier(dossier)
                viewModel.selectTab(MainTab.RADAR)
            },
            onUnlockTarget = {
                viewModel.unlockTarget()
                viewModel.closeDossier()
            },
            onWatchFingerprint = viewModel::watchFingerprintFromDossier,
            onToggleMute = { dossier -> viewModel.toggleMuteEmitter(dossier.fingerprintHash) },
            onDismiss = viewModel::closeDossier
        )
    }

    // 4-Tab Offline Tactical Field Guide
    if (showFieldGuideDialog) {
        FieldGuideSheet(
            onDismiss = { showFieldGuideDialog = false }
        )
    }

    // Offline SQLite Session Log Dialog (JSON / CSV export)
    if (showSessionLogDialog) {
        SessionLogDialog(
            snapshots = uiState.savedSnapshots,
            onExport = { format -> onRequestExportFile(format) },
            onDeleteSnapshot = viewModel::deleteSnapshot,
            onClearAll = viewModel::clearAllSnapshots,
            onDismiss = { showSessionLogDialog = false }
        )
    }
}

@Composable
private fun TopTacticalHud(
    uiState: RadarUiState,
    onToggleBackgroundPins: () -> Unit,
    onToggleLabels: () -> Unit,
    onToggleFollowMe: () -> Unit,
    onCycleZoom: () -> Unit,
    onCycleCarryingMode: () -> Unit,
    onToggleScreenOutward: () -> Unit,
    onToggleMapOverlay: () -> Unit,
    onCacheArea: () -> Unit,
    onTogglePowerMode: () -> Unit,
    onToggleGeiger: () -> Unit,
    onOpenFieldGuide: () -> Unit,
    onResetGnssBaseline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CharcoalSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1: Compass Rose + Digital Heading + Read-Only Hardware Pills (GPS, MAG, GNSS RF)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniCompassRose(
                userPose = uiState.userPose,
                modifier = Modifier.size(38.dp)
            )
            Column(modifier = Modifier.padding(end = 4.dp)) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%03d° %s",
                        uiState.userPose.compassDegrees,
                        uiState.userPose.cardinalLabel
                    ),
                    style = TelemetryValueStyle,
                    color = ElectricCyan,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = if (uiState.activePresets.screenOutwardMode) {
                        "Pocket +180°"
                    } else if (uiState.userPose.isHeadingZeroed) {
                        "Zeroed Hdg"
                    } else {
                        "Mag North"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.activePresets.screenOutwardMode) ThermalAmber else ReadoutInkSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Read-Only GPS Pill (Emerald <= 25m, Amber indoors/missing)
                val gps = uiState.tileCacheState.anchorGps
                val gpsGood = gps != null && gps.accuracyMeters <= 25.0f
                val gpsLabel = if (gps != null) {
                    String.format(Locale.US, "GPS %.0fm", gps.accuracyMeters)
                } else {
                    "GPS INDOOR"
                }
                HudStatusPill(
                    icon = Icons.Filled.GpsFixed,
                    label = gpsLabel,
                    tint = if (gpsGood) SignalEmerald else ThermalAmber,
                    testTag = "hud_gps_pill",
                    onClick = null
                )

                // Read-Only MAG Health Pill
                MagneticHealthPill(status = uiState.magneticStatus)

                // Read-Only GNSS RF Environment Pill
                GnssRfHealthPill(
                    status = uiState.gnssRfStatus,
                    onClick = onResetGnssBaseline
                )
            }
        }

        // Row 2: Single Horizontally Scrollable Action Chip Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Body / Omni Mode
            val tiltGuardForcingZero = uiState.calibration.shieldDropDb > 0f &&
                uiState.userPose.hasOrientationReading &&
                uiState.userPose.isShieldTiltGuarded
            val baseCarryingLabel = uiState.activePresets.shieldPreset
                ?.contextualShortLabel(uiState.activePresets.screenOutwardMode)
                ?: String.format(Locale.US, "Shield %.0fdB", uiState.calibration.shieldDropDb)
            val carryingLabel = if (tiltGuardForcingZero) "$baseCarryingLabel (0dB)" else baseCarryingLabel
            val carryingColor = when {
                tiltGuardForcingZero -> ThermalAmber
                uiState.activePresets.shieldPreset == ShieldPreset.OMNI_HANDHELD -> ReadoutInkPrimary
                uiState.activePresets.shieldPreset == ShieldPreset.POCKET_BODY -> ElectricCyan
                uiState.activePresets.shieldPreset == ShieldPreset.FLAT_ALUMINUM -> ThermalAmber
                uiState.activePresets.shieldPreset == ShieldPreset.CORNER_REFLECTOR_90 -> ThermalYellow
                else -> ElectricCyan
            }
            HudStatusPill(
                icon = if (tiltGuardForcingZero) Icons.Filled.Warning else Icons.Filled.Shield,
                label = carryingLabel,
                tint = carryingColor,
                testTag = "hud_carrying_mode_pill",
                onClick = onCycleCarryingMode
            )

            // 2. Screen Outward (+180 deg)
            val outwardOn = uiState.activePresets.screenOutwardMode
            HudStatusPill(
                icon = Icons.Filled.Explore,
                label = if (outwardOn) "Screen Outward (+180°) ON" else "Screen Outward (+180°)",
                tint = if (outwardOn) ThermalAmber else ReadoutInkSecondary,
                testTag = "hud_screen_outward_pill",
                onClick = onToggleScreenOutward
            )

            // 3. Follow Me ON/OFF
            HudStatusPill(
                icon = Icons.Filled.MyLocation,
                label = if (uiState.followMeEnabled) "Follow Me: ON" else "Follow Me: OFF",
                tint = if (uiState.followMeEnabled) SignalEmerald else ThermalAmber,
                testTag = "hud_follow_me_pill",
                onClick = onToggleFollowMe
            )

            // 4. Zoom Cycle Pill (25m / 50m / 100m)
            HudStatusPill(
                icon = Icons.Filled.ZoomIn,
                label = "Zoom: ${uiState.radarZoomWidthMeters.roundToInt()}m",
                tint = ElectricCyan,
                testTag = "hud_zoom_pill",
                onClick = onCycleZoom
            )

            // 5. Bg Pins ON/OFF
            val pinsOn = uiState.showBackgroundPins
            HudStatusPill(
                icon = if (pinsOn) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                label = if (pinsOn) "Bg Pins: ON" else "Bg Pins: OFF",
                tint = if (pinsOn) ElectricCyan else ThermalAmber,
                testTag = "hud_bg_pins_pill",
                onClick = onToggleBackgroundPins
            )

            // 6. Labels ON/OFF
            HudStatusPill(
                icon = Icons.Filled.Label,
                label = if (uiState.showLabels) "Labels: ON" else "Labels: OFF",
                tint = if (uiState.showLabels) ElectricCyan else ReadoutInkSecondary,
                testTag = "hud_labels_pill",
                onClick = onToggleLabels
            )

            // 7. Map ON/OFF
            HudStatusPill(
                icon = Icons.Filled.Layers,
                label = if (uiState.mapOverlayEnabled) "Map: ON" else "Map: OFF",
                tint = if (uiState.mapOverlayEnabled) ElectricCyan else ReadoutInkSecondary,
                testTag = "hud_map_toggle_pill",
                onClick = onToggleMapOverlay
            )

            // 8. Cache Area
            HudStatusPill(
                icon = Icons.Filled.CloudDownload,
                label = if (uiState.tileCacheState.isDownloading) "Caching..." else "Cache Area",
                tint = SignalEmerald,
                testTag = "hud_cache_area_pill",
                onClick = onCacheArea
            )

            // 9. TURBO / ECO
            val isTurbo = uiState.powerMode == PowerMode.TURBO
            HudStatusPill(
                icon = if (isTurbo) Icons.Filled.Bolt else Icons.Filled.BatterySaver,
                label = uiState.powerMode.label,
                tint = if (isTurbo) ThermalAmber else SignalEmerald,
                testTag = "hud_power_mode_pill",
                onClick = onTogglePowerMode
            )

            // 10. Geiger Mute / Hz
            HudStatusPill(
                icon = if (uiState.isGeigerEnabled) {
                    Icons.AutoMirrored.Filled.VolumeUp
                } else {
                    Icons.AutoMirrored.Filled.VolumeOff
                },
                label = if (uiState.isGeigerEnabled) {
                    String.format(Locale.US, "Geiger %.1fHz", uiState.geigerPulseRateHz)
                } else {
                    "Geiger Mute"
                },
                tint = if (uiState.isGeigerEnabled) ElectricCyan else ReadoutInkSecondary,
                testTag = "geiger_toggle_button",
                onClick = onToggleGeiger
            )

            // 11. ? Guide
            HudStatusPill(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                label = "? Guide",
                tint = ElectricCyan,
                testTag = "field_guide_button",
                onClick = onOpenFieldGuide
            )
        }
    }
}

@Composable
private fun MiniCompassRose(
    userPose: UserPose,
    modifier: Modifier = Modifier
) {
    val headingRad = userPose.headingRad
    val cardinalPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(148, 163, 184)
            textSize = 16f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val northPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(0, 229, 255)
            textSize = 17f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    Canvas(
        modifier = modifier
            .clip(CircleShape)
            .background(ObsidianBackground)
            .border(1.dp, TacticalOutline, CircleShape)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.42f
        // Outer ring & crisp N/E/S/W cardinal ticks
        drawCircle(TacticalOutline, radius = r, center = Offset(cx, cy), style = Stroke(1.2f))
        drawLine(ElectricCyan.copy(alpha = 0.8f), Offset(cx, cy - r), Offset(cx, cy - r + 4f), 2.0f)
        drawLine(TacticalOutline, Offset(cx + r, cy), Offset(cx + r - 4f, cy), 1.4f)
        drawLine(TacticalOutline, Offset(cx, cy + r), Offset(cx, cy + r - 4f), 1.4f)
        drawLine(TacticalOutline, Offset(cx - r, cy), Offset(cx - r + 4f, cy), 1.4f)

        val nc = drawContext.canvas.nativeCanvas
        nc.drawText("N", cx, cy - r + 13f, northPaint)
        nc.drawText("E", cx + r - 7f, cy + 5f, cardinalPaint)
        nc.drawText("S", cx, cy + r - 3f, cardinalPaint)
        nc.drawText("W", cx - r + 7f, cy + 5f, cardinalPaint)

        // Rotating heading needle
        val tip = Offset(
            x = cx + (r * 0.72f * cos(headingRad)).toFloat(),
            y = cy - (r * 0.72f * sin(headingRad)).toFloat()
        )
        val tail = Offset(
            x = cx - (r * 0.40f * cos(headingRad)).toFloat(),
            y = cy + (r * 0.40f * sin(headingRad)).toFloat()
        )
        drawLine(ThermalAmber, Offset(cx, cy), tail, 2.2f, StrokeCap.Round)
        drawLine(ElectricCyan, Offset(cx, cy), tip, 2.8f, StrokeCap.Round)
        drawCircle(ReadoutInkPrimary, radius = 2.5f, center = Offset(cx, cy))
    }
}

@Composable
private fun MagneticHealthPill(status: MagneticFieldStatus) {
    val color = when {
        !status.hasSensorReading -> ReadoutInkMuted
        status.needsFigure8Calibration || status.isMagneticWarped -> ThermalAmber
        else -> SignalEmerald
    }
    val text = when {
        !status.hasSensorReading -> "MAG: --"
        status.needsFigure8Calibration -> "WAVE FIGURE-8"
        status.isMagneticWarped && status.baselineFluxMicroTesla != null ->
            String.format(Locale.US, "MAG DRIFT (+%.0fµT)", status.driftDeltaMicroTesla)
        status.isMagneticWarped ->
            String.format(Locale.US, "MAG DRIFT %.0fµT", status.fluxMicroTesla)
        else ->
            String.format(Locale.US, "MAG: %.0fµT", status.fluxMicroTesla)
    }
    HudStatusPill(
        icon = if (status.isMagneticWarped || status.needsFigure8Calibration) Icons.Filled.Warning else Icons.Filled.Explore,
        label = text,
        tint = color,
        testTag = "hud_mag_health_pill",
        onClick = null
    )
}

@Composable
private fun GnssRfHealthPill(
    status: GnssRfStatus,
    onClick: () -> Unit
) {
    val (label, color) = when (status.interferenceLevel) {
        GnssInterferenceLevel.UNKNOWN -> "RF ENV: STANDBY" to ReadoutInkSecondary
        GnssInterferenceLevel.NOMINAL -> "RF ENV: NOMINAL" to SignalEmerald
        GnssInterferenceLevel.ELEVATED -> "RF ENV: ELEVATED" to ThermalAmber
        GnssInterferenceLevel.JAMMING_SUSPECTED -> "RF ENV: JAMMING" to TacticalCrimson
        GnssInterferenceLevel.SPOOFING_SUSPECTED -> "RF ENV: SPOOFING" to TacticalCrimson
    }
    HudStatusPill(
        icon = Icons.Filled.Security,
        label = label,
        tint = color,
        testTag = "hud_gnss_rf_pill",
        onClick = onClick
    )
}

@Composable
private fun HudStatusPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    testTag: String,
    onClick: (() -> Unit)?
) {
    val baseMod = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(tint.copy(alpha = 0.13f))
        .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 8.dp, vertical = 5.dp)
        .testTag(testTag)

    Row(
        modifier = baseMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

@Composable
private fun TargetSelectorAndCoachBanner(
    uiState: RadarUiState,
    onOpenScanner: () -> Unit,
    onUnlockTarget: () -> Unit
) {
    val hasTarget = uiState.lockedTargetMac != null
    val coach: SweepCoachState = uiState.sweepCoachState

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CharcoalSurface)
            .border(
                1.dp,
                when {
                    !hasTarget -> TacticalOutline
                    coach.stage == SweepStage.LOCKED -> SignalEmerald
                    uiState.lockedTargetIsStale -> ThermalAmber
                    else -> ElectricCyan.copy(alpha = 0.7f)
                },
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Target Selection Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenScanner)
                .testTag("target_selector_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (hasTarget) Icons.Filled.GpsFixed else Icons.Filled.Radar,
                contentDescription = stringResource(R.string.cd_target_selector),
                tint = if (hasTarget) SignalEmerald else ElectricCyan,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasTarget) {
                        val badge = if (uiState.lockedTargetIsRtt) "[RTT] " else ""
                        "$badge${uiState.lockedTargetName ?: "Locked Emitter"}"
                    } else {
                        "SELECT TARGET FROM SCANNER (OR TAP RADAR SHAPE)"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasTarget) ReadoutInkPrimary else ElectricCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (hasTarget) {
                        val staleTag = if (uiState.lockedTargetIsStale) " · STALE" else ""
                        "${uiState.lockedTargetMac} · ${String.format(Locale.US, "%.1f Hz", uiState.lockedTargetPacketRateHz)}$staleTag"
                    } else {
                        "${uiState.discoveredEmitters.size} active RF emitters · ${uiState.cellularSnapshot.towers.size} cell towers"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.lockedTargetIsStale) ThermalAmber else ReadoutInkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (hasTarget) {
                Surface(
                    onClick = onUnlockTarget,
                    shape = RoundedCornerShape(8.dp),
                    color = TacticalCrimson.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, TacticalCrimson.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reset_target_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LockOpen,
                            contentDescription = stringResource(R.string.cd_reset_target),
                            tint = TacticalCrimson,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "RESET",
                            style = MaterialTheme.typography.labelSmall,
                            color = TacticalCrimson
                        )
                    }
                }
            }
        }

        // Guided Two-Spin Triangulation Coach Step Banner
        if (hasTarget) {
            val coachColor = when (coach.stage) {
                SweepStage.SPIN_1, SweepStage.SPIN_2 -> ElectricCyan
                SweepStage.WALK_PERPENDICULAR -> ThermalAmber
                SweepStage.LOCKED -> SignalEmerald
                SweepStage.IDLE -> ReadoutInkSecondary
            }
            val bannerTitle = when (coach.stage) {
                SweepStage.SPIN_1 -> "STEP 1: SPIN 360° (${(coach.progressFraction * 100).toInt()}%)"
                SweepStage.WALK_PERPENDICULAR -> String.format(
                    Locale.US,
                    "STEP 2: WALK 5m SIDEWAYS (%.1f / %.1fm)",
                    coach.walkDistanceMeters,
                    coach.targetWalkMeters
                )
                SweepStage.SPIN_2 -> "STEP 3: SPIN 360° AT ANCHOR B (${(coach.progressFraction * 100).toInt()}%)"
                SweepStage.LOCKED -> String.format(
                    Locale.US,
                    "LOCKED ≤1.5m · CEP95 %.2fm",
                    coach.currentCep95Meters
                )
                SweepStage.IDLE -> "STANDBY"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianBackground)
                    .border(1.dp, coachColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .testTag("sweep_coach_banner"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = bannerTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = coachColor
                    )
                    Text(
                        text = uiState.gridSnapshot.activeTier.displayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = ReadoutInkMuted
                    )
                }
                Text(
                    text = coach.instruction,
                    style = MaterialTheme.typography.bodySmall,
                    color = ReadoutInkSecondary
                )
                LinearProgressIndicator(
                    progress = { coach.progressFraction.coerceIn(0f, 1f) },
                    color = coachColor,
                    trackColor = TacticalOutline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun OfflineMapControlsStrip(
    mapOverlayEnabled: Boolean,
    tileCacheState: TileCacheState,
    onToggleMapOverlay: () -> Unit,
    onCacheArea: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onToggleMapOverlay,
            shape = RoundedCornerShape(8.dp),
            color = if (mapOverlayEnabled) SignalEmerald.copy(alpha = 0.16f) else CharcoalSurface,
            border = BorderStroke(
                1.dp,
                if (mapOverlayEnabled) SignalEmerald.copy(alpha = 0.65f) else TacticalOutline
            ),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("map_overlay_toggle_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Layers,
                    contentDescription = stringResource(R.string.cd_map_toggle),
                    tint = if (mapOverlayEnabled) SignalEmerald else ReadoutInkSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (mapOverlayEnabled) "MAP: ON" else "MAP: OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mapOverlayEnabled) SignalEmerald else ReadoutInkSecondary
                )
            }
        }

        Surface(
            onClick = onCacheArea,
            enabled = !tileCacheState.isDownloading,
            shape = RoundedCornerShape(8.dp),
            color = CharcoalSurface,
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("cache_map_area_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = stringResource(R.string.cd_cache_map),
                    tint = ElectricCyan,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = when {
                        tileCacheState.isDownloading ->
                            "CACHING ${tileCacheState.downloadedTiles}/${tileCacheState.totalTilesRequested} TILES…"
                        tileCacheState.cachedTileCount > 0 ->
                            "CACHE AREA (${tileCacheState.cachedTileCount} OFFLINE)"
                        else -> "CACHE AREA (DARK OSM z16–18)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TelemetryGridStrip(uiState: RadarUiState) {
    val snap = uiState.gridSnapshot
    val hasEstimate = uiState.lockedTargetMac != null && snap.updateCount > 0L

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TelemetryMetricCard(
            label = "EST DIST",
            value = if (hasEstimate) String.format(Locale.US, "%.2f m", snap.estimatedDistanceMeters) else "--",
            subValue = uiState.lockedTargetLastRttMeters?.let {
                String.format(Locale.US, "RTT %.2fm", it)
            } ?: "CEP ${String.format(Locale.US, "%.1fm", snap.cep95Meters)}",
            accentColor = if (hasEstimate && snap.estimatedDistanceMeters <= 1.5f) SignalEmerald else ElectricCyan,
            modifier = Modifier.weight(1f)
        )

        TelemetryMetricCard(
            label = "LIVE / KAL",
            value = uiState.lockedTargetLastRssiDbm?.let { "$it dBm" } ?: "--",
            subValue = uiState.lockedTargetKalmanRssiDbm?.let {
                String.format(Locale.US, "KAL %.0f · %.1fHz", it, uiState.lockedTargetPacketRateHz)
            } ?: "0.0 Hz",
            accentColor = ThermalAmber,
            modifier = Modifier.weight(1f)
        )

        TelemetryMetricCard(
            label = "POSE / STEP",
            value = "${uiState.userPose.compassDegrees}° ${uiState.userPose.cardinalLabel}",
            subValue = "${uiState.userPose.stepCount} steps",
            accentColor = ReadoutInkPrimary,
            modifier = Modifier.weight(1f)
        )

        TelemetryMetricCard(
            label = "CONFIDENCE",
            value = if (hasEstimate) "${snap.peakConfidencePct}%" else "--",
            subValue = "${snap.updateCount} upd",
            accentColor = if (snap.activeTier == LocalizationTier.RTT_TIER1) SignalEmerald else ThermalYellow,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TelemetryMetricCard(
    label: String,
    value: String,
    subValue: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CharcoalSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ReadoutInkMuted,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = TelemetrySubValueStyle,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subValue,
            style = MaterialTheme.typography.labelSmall,
            color = ReadoutInkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PrimaryScanSignalsButton(
    activeEmitterCount: Int,
    cellTowerCount: Int,
    hasLockedTarget: Boolean,
    onOpenScanner: () -> Unit
) {
    Button(
        onClick = onOpenScanner,
        colors = ButtonDefaults.buttonColors(
            containerColor = ElectricCyan,
            contentColor = ObsidianBackground
        ),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("primary_scan_signals_button")
    ) {
        Icon(
            imageVector = Icons.Filled.Radar,
            contentDescription = stringResource(R.string.cd_target_selector),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (hasLockedTarget) {
                "SWITCH TARGET · SCAN SIGNALS ($activeEmitterCount)"
            } else {
                "SCAN SIGNALS ($activeEmitterCount)"
            },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (cellTowerCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ObsidianBackground.copy(alpha = 0.22f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$cellTowerCount CELL",
                    style = MaterialTheme.typography.labelSmall,
                    color = ObsidianBackground
                )
            }
        }
    }
}

@Composable
private fun TacticalActionRow(
    hasLockedTarget: Boolean,
    isHeadingZeroed: Boolean,
    onZeroHeading: () -> Unit,
    onResetHeatmap: () -> Unit,
    onPurgeScans: () -> Unit,
    onSaveSnapshot: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(
            onClick = onZeroHeading,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                1.dp,
                if (isHeadingZeroed) ElectricCyan else TacticalOutline
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isHeadingZeroed) ElectricCyan.copy(alpha = 0.14f) else CharcoalSurface,
                contentColor = if (isHeadingZeroed) ElectricCyan else ReadoutInkPrimary
            ),
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("zero_heading_button")
        ) {
            Text(
                text = if (isHeadingZeroed) "CLR HDG" else "ZERO HDG",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }

        OutlinedButton(
            onClick = onResetHeatmap,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, TacticalOutline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = CharcoalSurface,
                contentColor = ReadoutInkPrimary
            ),
            modifier = Modifier
                .weight(1.1f)
                .minimumInteractiveComponentSize()
                .testTag("reset_heatmap_button")
        ) {
            Text(
                text = "RST HEATMAP",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }

        OutlinedButton(
            onClick = onPurgeScans,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ThermalAmber.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = CharcoalSurface,
                contentColor = ThermalAmber
            ),
            modifier = Modifier
                .weight(1.1f)
                .minimumInteractiveComponentSize()
                .testTag("purge_scans_button")
        ) {
            Text(
                text = "PURGE SCANS",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }

        Button(
            onClick = onSaveSnapshot,
            enabled = hasLockedTarget,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SignalEmerald,
                contentColor = ObsidianBackground,
                disabledContainerColor = ElevatedSurface,
                disabledContentColor = ReadoutInkMuted
            ),
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("save_snapshot_button")
        ) {
            Text(
                text = "SAVE & PIN",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HardwareReadinessRow(hardware: HardwareStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CharcoalSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDotChip("RTT", hardware.hasWifiRtt)
        StatusDotChip("BLE", hardware.hasBleScanner)
        StatusDotChip("IMU", hardware.hasRotationVector)
        StatusDotChip("STEP", hardware.hasStepDetector)
        StatusDotChip("MAG", hardware.hasMagnetometer)
        StatusDotChip("USB", hardware.usbBridgeAttached, optional = true)
    }
}

@Composable
private fun StatusDotChip(
    label: String,
    active: Boolean,
    optional: Boolean = false
) {
    val dotColor = when {
        active -> SignalEmerald
        optional -> ReadoutInkMuted
        else -> ThermalAmber
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) ReadoutInkPrimary else ReadoutInkMuted
        )
    }
}

@Composable
private fun PermissionGateView(
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CharcoalSurface)
                .border(1.dp, TacticalOutline, RoundedCornerShape(16.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Radar,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = stringResource(R.string.permission_title),
                style = MaterialTheme.typography.titleLarge,
                color = ReadoutInkPrimary
            )
            Text(
                text = stringResource(R.string.permission_rationale),
                style = MaterialTheme.typography.bodyMedium,
                color = ReadoutInkSecondary
            )

            if (missingPermissions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianBackground)
                        .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "REQUIRED CORE PERMISSIONS (${missingPermissions.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = ThermalAmber
                    )
                    missingPermissions.forEach { perm ->
                        Text(
                            text = "• ${perm.substringAfterLast('.')}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ReadoutInkPrimary
                        )
                    }
                }
            }

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = ObsidianBackground
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("grant_permissions_button")
            ) {
                Text(
                    text = stringResource(R.string.permission_grant_button),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedButton(
                onClick = onOpenAppSettings,
                border = BorderStroke(1.dp, TacticalOutline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ReadoutInkSecondary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("open_settings_button")
            ) {
                Text(
                    text = stringResource(R.string.permission_settings_button),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
