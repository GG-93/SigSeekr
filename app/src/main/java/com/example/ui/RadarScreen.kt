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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ExportFormat
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
            if (uiState.hasPermissions) {
                CalibrationDrawer(
                    calibration = uiState.calibration,
                    activePresets = uiState.activePresets,
                    expanded = calibrationExpanded,
                    onToggleExpanded = { calibrationExpanded = !calibrationExpanded },
                    onApplyShieldPreset = viewModel::applyShieldPreset,
                    onApplyEmitterClassPreset = viewModel::applyEmitterClassPreset,
                    onApplyEnvironmentPreset = viewModel::applyEnvironmentPreset,
                    onShieldDropChange = viewModel::updateShieldDropDb,
                    onTxPowerChange = viewModel::updateTxPowerP0,
                    onPathLossChange = viewModel::updatePathLossExponent,
                    onStepLengthChange = viewModel::updateStepLengthMeters,
                    onRestoreDefaults = viewModel::restoreDefaultCalibration
                )
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
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Top Tactical HUD (Compass Rose, MAG µT, GNSS RF, Carrying Mode, TURBO/ECO, Geiger, ?, History)
                    TopTacticalHud(
                        uiState = uiState,
                        onCycleCarryingMode = viewModel::cycleQuickCarryingMode,
                        onTogglePowerMode = viewModel::togglePowerMode,
                        onToggleGeiger = viewModel::toggleGeigerFeedback,
                        onOpenFieldGuide = { showFieldGuideDialog = true },
                        onOpenSessionLog = { showSessionLogDialog = true },
                        onResetGnssBaseline = viewModel::resetGnssBaseline
                    )

                    // 2. Target Lock Pill + Guided Two-Spin Coach Banner
                    TargetSelectorAndCoachBanner(
                        uiState = uiState,
                        onOpenScanner = { showScannerDialog = true },
                        onUnlockTarget = viewModel::unlockTarget
                    )

                    // 3. Offline Map Controls Bar (Map ON/OFF + Cache Area)
                    OfflineMapControlsStrip(
                        mapOverlayEnabled = uiState.mapOverlayEnabled,
                        tileCacheState = uiState.tileCacheState,
                        onToggleMapOverlay = viewModel::toggleMapOverlay,
                        onCacheArea = { viewModel.cacheOfflineMapArea() }
                    )

                    // 4. Primary 100x100 Bayesian Thermal Plasma Radar Canvas + OSM + 6-Stage Shapes + Triangulation
                    RadarCanvas(
                        gridSnapshot = uiState.gridSnapshot,
                        userPose = uiState.userPose,
                        hasLockedTarget = uiState.lockedTargetMac != null,
                        lockedTargetMac = uiState.lockedTargetMac,
                        lockedTargetLabel = uiState.lockedTargetName,
                        backgroundEmitters = uiState.backgroundEmitters,
                        sweepCoachState = uiState.sweepCoachState,
                        tileCacheState = uiState.tileCacheState,
                        mapOverlayEnabled = uiState.mapOverlayEnabled,
                        offlineMapTileManager = viewModel.offlineMapTileManager,
                        onTapBackgroundEmitter = { emitter ->
                            viewModel.openDossierForBackgroundEmitter(emitter)
                        }
                    )

                    // 5. Monospaced Telemetry Readout Strip
                    TelemetryGridStrip(uiState = uiState)

                    // 6. Tactical Action Row (Zero Heading, Reset Grid, Save & Pin Snapshot)
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
                        onResetGrid = viewModel::resetGridAndPose,
                        onSaveSnapshot = viewModel::saveCurrentSnapshot
                    )

                    // 7. Standalone Hardware Readiness Row
                    HardwareReadinessRow(hardware = uiState.hardwareStatus)

                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
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
            onLockTarget = viewModel::lockTargetFromDossier,
            onUnlockTarget = {
                viewModel.unlockTarget()
                viewModel.closeDossier()
            },
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
    onCycleCarryingMode: () -> Unit,
    onTogglePowerMode: () -> Unit,
    onToggleGeiger: () -> Unit,
    onOpenFieldGuide: () -> Unit,
    onOpenSessionLog: () -> Unit,
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
        // Row 1: Compass Rose + Digital Heading + Geiger + ? Guide + Session Log
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniCompassRose(
                userPose = uiState.userPose,
                modifier = Modifier.size(34.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "%03d° %s",
                            uiState.userPose.compassDegrees,
                            uiState.userPose.cardinalLabel
                        ),
                        style = TelemetryValueStyle,
                        color = ElectricCyan
                    )
                    Text(
                        text = "SIGSEEKER",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReadoutInkMuted
                    )
                }
                Text(
                    text = if (uiState.userPose.isHeadingZeroed) "Heading offset active" else "Magnetic North lock",
                    style = MaterialTheme.typography.labelSmall,
                    color = ReadoutInkSecondary
                )
            }

            // Geiger Feedback Toggle with live pulse rate
            Surface(
                onClick = onToggleGeiger,
                shape = RoundedCornerShape(8.dp),
                color = if (uiState.isGeigerEnabled) ElectricCyan.copy(alpha = 0.20f) else ElevatedSurface,
                border = BorderStroke(
                    1.dp,
                    if (uiState.isGeigerEnabled) ElectricCyan else TacticalOutline
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("geiger_toggle_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isGeigerEnabled) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = stringResource(R.string.cd_geiger_toggle),
                        tint = if (uiState.isGeigerEnabled) ElectricCyan else ReadoutInkSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (uiState.isGeigerEnabled) {
                            String.format(Locale.US, "%.1fHz", uiState.geigerPulseRateHz)
                        } else {
                            "MUTE"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uiState.isGeigerEnabled) ElectricCyan else ReadoutInkSecondary
                    )
                }
            }

            // Field Guide '?' Button
            IconButton(
                onClick = onOpenFieldGuide,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ElevatedSurface)
                    .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
                    .testTag("field_guide_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = stringResource(R.string.cd_field_guide),
                    tint = ElectricCyan,
                    modifier = Modifier.size(19.dp)
                )
            }

            // Offline Session Log Button
            IconButton(
                onClick = onOpenSessionLog,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ElevatedSurface)
                    .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
                    .testTag("session_log_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = stringResource(R.string.cd_session_log),
                    tint = ReadoutInkPrimary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        // Row 2: Horizontally Scrollable Tactical Status & Quick-Switch Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Magnetic Health Pill (MAG: 53µT vs MAG WARP)
            MagneticHealthPill(status = uiState.magneticStatus)

            // GNSS RF Environment Pill (NOMINAL / ELEVATED / JAMMING / SPOOFING)
            GnssRfHealthPill(
                status = uiState.gnssRfStatus,
                onClick = onResetGnssBaseline
            )

            // Quick Carrying Mode Cycle Pill (Omni 0dB -> Pocket 16dB -> Corner 27dB)
            val carryingLabel = uiState.activePresets.shieldPreset?.shortLabel
                ?: String.format(Locale.US, "Shield %.0fdB", uiState.calibration.shieldDropDb)
            val carryingColor = when (uiState.activePresets.shieldPreset) {
                ShieldPreset.OMNI_HANDHELD -> ReadoutInkPrimary
                ShieldPreset.POCKET_BODY -> ElectricCyan
                ShieldPreset.FLAT_ALUMINUM -> ThermalAmber
                ShieldPreset.CORNER_REFLECTOR_90 -> ThermalYellow
                null -> ElectricCyan
            }
            HudStatusPill(
                icon = Icons.Filled.Shield,
                label = carryingLabel,
                tint = carryingColor,
                testTag = "hud_carrying_mode_pill",
                onClick = onCycleCarryingMode
            )

            // TURBO / ECO Battery Toggle Pill
            val isTurbo = uiState.powerMode == PowerMode.TURBO
            HudStatusPill(
                icon = if (isTurbo) Icons.Filled.Bolt else Icons.Filled.BatterySaver,
                label = uiState.powerMode.label,
                tint = if (isTurbo) ThermalAmber else SignalEmerald,
                testTag = "hud_power_mode_pill",
                onClick = onTogglePowerMode
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
    Canvas(
        modifier = modifier
            .clip(CircleShape)
            .background(ObsidianBackground)
            .border(1.dp, TacticalOutline, CircleShape)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.42f
        // Outer ring & cardinal ticks
        drawCircle(TacticalOutline, radius = r, center = Offset(cx, cy), style = Stroke(1.2f))
        drawLine(ElectricCyan.copy(alpha = 0.6f), Offset(cx, cy - r), Offset(cx, cy - r + 4f), 1.8f)
        // Rotating heading needle
        val tip = Offset(
            x = cx + (r * 0.85f * cos(headingRad)).toFloat(),
            y = cy - (r * 0.85f * sin(headingRad)).toFloat()
        )
        val tail = Offset(
            x = cx - (r * 0.45f * cos(headingRad)).toFloat(),
            y = cy + (r * 0.45f * sin(headingRad)).toFloat()
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
        status.isMagneticWarped -> ThermalAmber
        else -> SignalEmerald
    }
    val text = when {
        !status.hasSensorReading -> "MAG: --"
        status.isMagneticWarped -> String.format(Locale.US, "MAG WARP %.0fµT", status.fluxMicroTesla)
        else -> String.format(Locale.US, "MAG: %.0fµT", status.fluxMicroTesla)
    }
    HudStatusPill(
        icon = if (status.isMagneticWarped) Icons.Filled.Warning else Icons.Filled.Explore,
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
private fun TacticalActionRow(
    hasLockedTarget: Boolean,
    isHeadingZeroed: Boolean,
    onZeroHeading: () -> Unit,
    onResetGrid: () -> Unit,
    onSaveSnapshot: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onZeroHeading,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
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
            Icon(
                imageVector = Icons.Filled.Explore,
                contentDescription = stringResource(R.string.cd_zero_heading),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isHeadingZeroed) "CLR HDG" else "ZERO HDG",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }

        OutlinedButton(
            onClick = onResetGrid,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, TacticalOutline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = CharcoalSurface,
                contentColor = ReadoutInkPrimary
            ),
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("reset_grid_button")
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.cd_reset_grid),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "RST GRID",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }

        Button(
            onClick = onSaveSnapshot,
            enabled = hasLockedTarget,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
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
            Icon(
                imageVector = Icons.Filled.BookmarkAdd,
                contentDescription = stringResource(R.string.cd_save_snapshot),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
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
