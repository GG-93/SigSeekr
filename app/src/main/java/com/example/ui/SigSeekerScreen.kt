package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ExportFormat
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
import java.util.Locale

@Composable
fun SigSeekerScreen(
    viewModel: RadarViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestExportFile: (ExportFormat) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showScannerDialog by rememberSaveable { mutableStateOf(false) }
    var showSessionLogDialog by rememberSaveable { mutableStateOf(false) }
    var calibrationExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.statusMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground),
        containerColor = ObsidianBackground,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TacticalTopBar(
                state = state,
                onOpenScanner = { showScannerDialog = true },
                onToggleGeiger = { viewModel.toggleGeigerFeedback() },
                onOpenSessionLog = { showSessionLogDialog = true }
            )
        },
        bottomBar = {
            CalibrationDrawer(
                params = state.calibration,
                expanded = calibrationExpanded,
                onToggleExpanded = { calibrationExpanded = !calibrationExpanded },
                onShieldDropChange = viewModel::updateShieldDropDb,
                onTxPowerChange = viewModel::updateTxPowerP0,
                onPathLossChange = viewModel::updatePathLossExponent,
                onStepLengthChange = viewModel::updateStepLengthMeters,
                onRestoreDefaults = viewModel::restoreDefaultCalibration
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!state.hasPermissions) {
                PermissionGateCard(
                    missingPermissions = state.missingPermissions,
                    onGrantClick = onRequestPermissions,
                    onSettingsClick = onOpenAppSettings
                )
            }

            // Locked Target Banner / Selector Strip
            TargetStatusBar(
                state = state,
                onOpenScanner = { showScannerDialog = true }
            )

            // Centerpiece 50m x 50m Thermal Plasma Bayesian Radar Canvas
            RadarCanvas(
                snapshot = state.gridSnapshot,
                userPose = state.userPose,
                lockedTargetMac = state.lockedTargetMac,
                isTargetStale = state.lockedTargetIsStale,
                shieldDropDb = state.calibration.shieldDropDb
            )

            // Compact 6-Cell Live Telemetry Strip
            TelemetryGridStrip(state = state)

            // Tactical Field Action Controls
            TacticalActionRow(
                hasLockedTarget = state.lockedTargetMac != null,
                isHeadingZeroed = state.userPose.isHeadingZeroed,
                onResetGrid = viewModel::resetGridAndPose,
                onZeroHeading = {
                    if (state.userPose.isHeadingZeroed) {
                        viewModel.clearHeadingOffset()
                    } else {
                        viewModel.zeroHeading()
                    }
                },
                onResetTarget = viewModel::unlockTarget,
                onSaveSnapshot = viewModel::saveCurrentSnapshot
            )

            // Standalone Hardware Capability Footer
            HardwareReadinessRow(state = state)

            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    if (showScannerDialog) {
        ScannerDialog(
            emitters = state.discoveredEmitters,
            lockedTargetMac = state.lockedTargetMac,
            onSelectEmitter = { emitter ->
                viewModel.lockTarget(emitter)
                showScannerDialog = false
            },
            onRefreshWifiScan = viewModel::triggerManualWifiScan,
            onDismiss = { showScannerDialog = false }
        )
    }

    if (showSessionLogDialog) {
        SessionLogDialog(
            snapshots = state.savedSnapshots,
            onExport = onRequestExportFile,
            onDeleteSnapshot = viewModel::deleteSnapshot,
            onClearAll = viewModel::clearAllSnapshots,
            onDismiss = { showSessionLogDialog = false }
        )
    }
}

@Composable
private fun TacticalTopBar(
    state: RadarUiState,
    onOpenScanner: () -> Unit,
    onToggleGeiger: () -> Unit,
    onOpenSessionLog: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CharcoalSurface)
            .statusBarsPadding()
            .border(width = 0.5.dp, color = TacticalOutline)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Brand Identity
        Icon(
            imageVector = Icons.Filled.Radar,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = ReadoutInkPrimary
            )
            Text(
                text = if (state.lockedTargetMac != null) "SINGLE-MAC LOCK ACTIVE" else "OFFLINE RF RADAR",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = if (state.lockedTargetMac != null) SignalEmerald else ReadoutInkMuted
            )
        }

        // Geiger Mute / Active Toggle with live Hz badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (state.isGeigerEnabled) ThermalAmber.copy(alpha = 0.16f) else ElevatedSurface
                )
                .border(
                    1.dp,
                    if (state.isGeigerEnabled) ThermalAmber else TacticalOutline,
                    RoundedCornerShape(8.dp)
                )
                .minimumInteractiveComponentSize()
                .clickable(onClick = onToggleGeiger)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("geiger_toggle_button")
        ) {
            Icon(
                imageVector = if (state.isGeigerEnabled) {
                    Icons.Filled.NotificationsActive
                } else {
                    Icons.Filled.NotificationsOff
                },
                contentDescription = stringResource(R.string.cd_geiger_toggle),
                tint = if (state.isGeigerEnabled) ThermalAmber else ReadoutInkSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (state.isGeigerEnabled && state.geigerPulseRateHz > 0f) {
                    String.format(Locale.US, "%.1fHz", state.geigerPulseRateHz)
                } else if (state.isGeigerEnabled) {
                    "ON"
                } else {
                    "MUTE"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.isGeigerEnabled) ThermalAmber else ReadoutInkSecondary
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Offline Session Log Button
        Box {
            IconButton(
                onClick = onOpenSessionLog,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ElevatedSurface)
                    .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
                    .testTag("session_log_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = stringResource(R.string.cd_session_log),
                    tint = ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (state.savedSnapshots.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(SignalEmerald),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.savedSnapshots.size.coerceAtMost(99).toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = ObsidianBackground
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetStatusBar(
    state: RadarUiState,
    onOpenScanner: () -> Unit
) {
    val isLocked = state.lockedTargetMac != null
    val borderColor = when {
        !isLocked -> ElectricCyan.copy(alpha = 0.6f)
        state.lockedTargetIsStale -> TacticalCrimson
        state.lockedTargetIsRtt -> SignalEmerald
        else -> ElectricCyan
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .background(CharcoalSurface)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onOpenScanner)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("target_selector_button"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.MyLocation,
            contentDescription = stringResource(R.string.cd_target_selector),
            tint = borderColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isLocked && state.lockedTargetIsRtt) {
                    Box(
                        modifier = Modifier
                            .background(SignalEmerald.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .border(1.dp, SignalEmerald, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "[RTT]",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = SignalEmerald
                        )
                    }
                }
                Text(
                    text = state.lockedTargetName ?: "SELECT TARGET EMITTER",
                    style = MaterialTheme.typography.titleSmall,
                    color = ReadoutInkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (isLocked) {
                    "${state.lockedTargetMac} · ${state.lockedTargetTransport?.shortLabel ?: "RF"} · ${
                        String.format(Locale.US, "%.1f Hz", state.lockedTargetPacketRateHz)
                    }"
                } else {
                    "${state.discoveredEmitters.size} nearby emitters detected · Tap to lock MAC"
                },
                style = MaterialTheme.typography.labelSmall,
                color = ReadoutInkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ElevatedSurface)
                .border(1.dp, TacticalOutline, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isLocked) "CHANGE" else "SCAN",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
        }
    }
}

@Composable
private fun TelemetryGridStrip(state: RadarUiState) {
    val snap = state.gridSnapshot
    val hasEstimate = state.lockedTargetMac != null && snap.updateCount > 0L

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TelemetryTile(
                label = "EST DIST",
                value = if (hasEstimate) {
                    String.format(Locale.US, "%.2f m", snap.estimatedDistanceMeters)
                } else {
                    "-- m"
                },
                valueColor = if (hasEstimate && snap.estimatedDistanceMeters <= 1.5f) {
                    SignalEmerald
                } else {
                    ElectricCyan
                },
                modifier = Modifier.weight(1f)
            )
            TelemetryTile(
                label = if (state.lockedTargetLastRttMeters != null) "RTT / RSSI" else "LIVE RSSI",
                value = when {
                    state.lockedTargetLastRttMeters != null -> String.format(
                        Locale.US,
                        "%.1fm / %d",
                        state.lockedTargetLastRttMeters,
                        state.lockedTargetLastRssiDbm ?: 0
                    )
                    state.lockedTargetLastRssiDbm != null -> "${state.lockedTargetLastRssiDbm} dBm"
                    else -> "-- dBm"
                },
                valueColor = if (state.lockedTargetLastRttMeters != null) SignalEmerald else ThermalAmber,
                modifier = Modifier.weight(1f)
            )
            TelemetryTile(
                label = if (state.userPose.isHeadingZeroed) "HDG (0°)" else "HEADING",
                value = String.format(Locale.US, "%03d°", state.userPose.compassDegrees),
                valueColor = if (state.userPose.isHeadingZeroed) ThermalAmber else ReadoutInkPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TelemetryTile(
                label = "USER (X,Y)",
                value = String.format(
                    Locale.US,
                    "%.1f, %.1f",
                    state.userPose.xMeters,
                    state.userPose.yMeters
                ),
                valueColor = ReadoutInkPrimary,
                modifier = Modifier.weight(1f)
            )
            TelemetryTile(
                label = "STEPS / PKTS",
                value = "${state.userPose.stepCount} / ${snap.updateCount}",
                valueColor = ReadoutInkSecondary,
                modifier = Modifier.weight(1f)
            )
            TelemetryTile(
                label = "CONVERGENCE",
                value = if (hasEstimate) "${snap.peakConfidencePct}%" else "0%",
                valueColor = when {
                    snap.peakConfidencePct >= 70 -> SignalEmerald
                    snap.peakConfidencePct >= 35 -> ThermalAmber
                    else -> ReadoutInkSecondary
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TelemetryTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CharcoalSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = ReadoutInkMuted,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = TelemetryValueStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TacticalActionRow(
    hasLockedTarget: Boolean,
    isHeadingZeroed: Boolean,
    onResetGrid: () -> Unit,
    onZeroHeading: () -> Unit,
    onResetTarget: () -> Unit,
    onSaveSnapshot: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TacticalActionButton(
            label = "RESET GRID",
            icon = Icons.Filled.Refresh,
            accentColor = ElectricCyan,
            enabled = true,
            testTag = "reset_grid_button",
            contentDescription = stringResource(R.string.cd_reset_grid),
            onClick = onResetGrid,
            modifier = Modifier.weight(1f)
        )
        TacticalActionButton(
            label = if (isHeadingZeroed) "CLR HDG" else "ZERO HDG",
            icon = Icons.Filled.Explore,
            accentColor = if (isHeadingZeroed) ThermalAmber else ElectricCyan,
            enabled = true,
            testTag = "zero_heading_button",
            contentDescription = stringResource(R.string.cd_zero_heading),
            onClick = onZeroHeading,
            modifier = Modifier.weight(1f)
        )
        TacticalActionButton(
            label = "RESET TGT",
            icon = Icons.Filled.GpsOff,
            accentColor = TacticalCrimson,
            enabled = hasLockedTarget,
            testTag = "reset_target_button",
            contentDescription = stringResource(R.string.cd_reset_target),
            onClick = onResetTarget,
            modifier = Modifier.weight(1f)
        )
        TacticalActionButton(
            label = "SAVE SNAP",
            icon = Icons.Filled.BookmarkAdd,
            accentColor = SignalEmerald,
            enabled = hasLockedTarget,
            testTag = "save_snapshot_button",
            contentDescription = stringResource(R.string.cd_save_snapshot),
            onClick = onSaveSnapshot,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TacticalActionButton(
    label: String,
    icon: ImageVector,
    accentColor: Color,
    enabled: Boolean,
    testTag: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTint = if (enabled) accentColor else ReadoutInkMuted
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = CharcoalSurface,
            contentColor = activeTint,
            disabledContainerColor = ObsidianBackground,
            disabledContentColor = ReadoutInkMuted
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) accentColor.copy(alpha = 0.55f) else TacticalOutline
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = activeTint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = activeTint,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HardwareReadinessRow(state: RadarUiState) {
    val hw = state.hardwareStatus
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
        StatusDotChip("RTT", hw.hasWifiRtt)
        StatusDotChip("BLE", hw.hasBleScanner)
        StatusDotChip("IMU", hw.hasRotationVector)
        StatusDotChip("STEP", hw.hasStepDetector)
        StatusDotChip("USB-OPT", hw.usbBridgeAttached, optional = true)
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
            style = TelemetrySubValueStyle.copy(fontSize = 10.sp),
            color = if (active) ReadoutInkPrimary else ReadoutInkMuted
        )
    }
}

@Composable
private fun PermissionGateCard(
    missingPermissions: List<String>,
    onGrantClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ElevatedSurface)
            .border(1.dp, ThermalAmber.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                tint = ThermalAmber,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.permission_title),
                style = MaterialTheme.typography.titleSmall,
                color = ReadoutInkPrimary
            )
        }
        Text(
            text = stringResource(R.string.permission_rationale),
            style = MaterialTheme.typography.bodySmall,
            color = ReadoutInkSecondary
        )
        if (missingPermissions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                missingPermissions.forEach { perm ->
                    Text(
                        text = "• ${PermissionHelper.friendlyName(perm)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ThermalAmber
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = ObsidianBackground
                ),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("grant_permissions_button")
            ) {
                Text(
                    text = stringResource(R.string.permission_grant_button),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            OutlinedButton(
                onClick = onSettingsClick,
                border = BorderStroke(1.dp, TacticalOutline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ReadoutInkSecondary),
                modifier = Modifier
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
