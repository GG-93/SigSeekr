package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.dsp.DiscoveredEmitter
import com.example.dsp.EmitterTransport
import com.example.sensors.CellRadioGeneration
import com.example.sensors.CellTowerInfo
import com.example.sensors.CellularMonitor
import com.example.sensors.CellularSnapshot
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
import com.example.ui.theme.ThermalAmber
import kotlinx.coroutines.delay
import java.util.Locale

enum class ScannerFilterTab(val label: String) {
    ALL("All"),
    RTT("RTT"),
    BLE("BLE"),
    WIFI("Wi-Fi"),
    CELL_TOWERS("Cell Towers"),
    MAC_ROTATED("MAC-Rotated")
}

/**
 * High-Precision Tactical Scanner List Dialog:
 * - Auto-Freeze List: Manual Freeze/Live toggle + automatic 2.5-second sort-freeze
 *   whenever the operator touches or scrolls the list (prevents jumping targets).
 * - 6 Filter Tabs: All, RTT, BLE, Wi-Fi, Cell Towers, MAC-Rotated.
 * - Cell Towers tab: Displays 5G NR / 4G LTE / 3G / 2G PCI, RSRP, SINR, Timing Advance
 *   distance, 2G Stingray downgrade alert banner, and an on-demand READ_PHONE_STATE
 *   permission prompt chip when not yet granted.
 * - Tapping any emitter opens its passive SignalDossierSheet; the inline LOCK button
 *   locks it directly onto the 1m Bayesian radar grid.
 */
@Composable
fun ScannerDialog(
    emitters: List<DiscoveredEmitter>,
    cellularSnapshot: CellularSnapshot,
    lockedTargetMac: String?,
    onInspectEmitter: (DiscoveredEmitter) -> Unit,
    onLockEmitter: (DiscoveredEmitter) -> Unit,
    onRefreshWifiScan: () -> Unit,
    onRefreshCellular: () -> Unit,
    onRequestPhoneStatePermission: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(ScannerFilterTab.ALL) }
    var selectedCellTower by remember { mutableStateOf<CellTowerInfo?>(null) }
    var manualFrozen by remember { mutableStateOf(false) }
    var touchFrozen by remember { mutableStateOf(false) }
    var lastInteractionToken by remember { mutableLongStateOf(0L) }
    var frozenSnapshot by remember { mutableStateOf(emitters) }
    val listState = rememberLazyListState()

    val isEffectivelyFrozen = manualFrozen || touchFrozen || listState.isScrollInProgress

    // Keep live snapshot updated ONLY while unfrozen
    LaunchedEffect(emitters, isEffectivelyFrozen) {
        if (!isEffectivelyFrozen) {
            frozenSnapshot = emitters
        }
    }

    // 2.5-second auto-freeze cooldown after touch/scroll stops
    LaunchedEffect(lastInteractionToken, listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            touchFrozen = true
        } else if (touchFrozen) {
            delay(2_500L)
            touchFrozen = false
        }
    }

    // Refresh cellular info when switching to Cell Towers tab
    LaunchedEffect(selectedTab) {
        if (selectedTab == ScannerFilterTab.CELL_TOWERS) {
            onRefreshCellular()
        }
    }

    val displayedEmitters = remember(frozenSnapshot, selectedTab) {
        when (selectedTab) {
            ScannerFilterTab.ALL -> frozenSnapshot
            ScannerFilterTab.RTT -> frozenSnapshot.filter { it.isRttResponder }
            ScannerFilterTab.BLE -> frozenSnapshot.filter { it.transport == EmitterTransport.BLE }
            ScannerFilterTab.WIFI -> frozenSnapshot.filter { it.transport == EmitterTransport.WIFI }
            ScannerFilterTab.MAC_ROTATED -> frozenSnapshot.filter { it.isMacRotated }
            ScannerFilterTab.CELL_TOWERS -> emptyList()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.86f)
                .border(1.dp, TacticalOutline, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = CharcoalSurface
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Radar,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "RF TARGET SCANNER",
                            style = MaterialTheme.typography.titleMedium,
                            color = ReadoutInkPrimary
                        )
                        Text(
                            text = if (selectedTab == ScannerFilterTab.CELL_TOWERS) {
                                "${cellularSnapshot.towers.size} cell towers · Timing Advance & 2G watch"
                            } else {
                                "RTT prioritized · Tap row for Dossier or LOCK"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadoutInkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Manual Freeze / Live Chip
                    if (selectedTab != ScannerFilterTab.CELL_TOWERS) {
                        val freezeAccent = if (isEffectivelyFrozen) ThermalAmber else SignalEmerald
                        Surface(
                            onClick = {
                                manualFrozen = !manualFrozen
                                if (!manualFrozen) {
                                    touchFrozen = false
                                    frozenSnapshot = emitters
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = freezeAccent.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, freezeAccent.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("scanner_freeze_toggle")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isEffectivelyFrozen) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = freezeAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isEffectivelyFrozen) "FROZEN" else "LIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = freezeAccent
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            if (selectedTab == ScannerFilterTab.CELL_TOWERS) {
                                onRefreshCellular()
                            } else {
                                onRefreshWifiScan()
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("scanner_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_scan),
                            tint = ElectricCyan
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("scanner_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog),
                            tint = ReadoutInkSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 6-Tab Horizontally Scrollable Filter Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ScannerFilterTab.entries.forEach { tab ->
                        val count = when (tab) {
                            ScannerFilterTab.ALL -> frozenSnapshot.size
                            ScannerFilterTab.RTT -> frozenSnapshot.count { it.isRttResponder }
                            ScannerFilterTab.BLE -> frozenSnapshot.count { it.transport == EmitterTransport.BLE }
                            ScannerFilterTab.WIFI -> frozenSnapshot.count { it.transport == EmitterTransport.WIFI }
                            ScannerFilterTab.CELL_TOWERS -> cellularSnapshot.towers.size
                            ScannerFilterTab.MAC_ROTATED -> frozenSnapshot.count { it.isMacRotated }
                        }
                        val isSelected = selectedTab == tab
                        val accent = when {
                            tab == ScannerFilterTab.CELL_TOWERS && cellularSnapshot.is2gDowngradeAlert -> TacticalCrimson
                            tab == ScannerFilterTab.RTT -> SignalEmerald
                            tab == ScannerFilterTab.MAC_ROTATED -> ThermalAmber
                            else -> ElectricCyan
                        }
                        Surface(
                            onClick = { selectedTab = tab },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accent.copy(alpha = 0.22f) else ElevatedSurface,
                            border = BorderStroke(1.dp, if (isSelected) accent else TacticalOutline),
                            modifier = Modifier.testTag("scanner_tab_${tab.name.lowercase(Locale.US)}")
                        ) {
                            Text(
                                text = "${tab.label} ($count)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) accent else ReadoutInkSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = TacticalOutline)
                Spacer(modifier = Modifier.height(8.dp))

                // Tab Content
                if (selectedTab == ScannerFilterTab.CELL_TOWERS) {
                    CellularTowersPanel(
                        snapshot = cellularSnapshot,
                        onSelectTower = { tower -> selectedCellTower = tower },
                        onRequestPhoneStatePermission = onRequestPhoneStatePermission,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else if (displayedEmitters.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Radar,
                                contentDescription = null,
                                tint = ReadoutInkMuted,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "Scanning 2.4 / 5 GHz & BLE Spectrum…",
                                style = MaterialTheme.typography.titleSmall,
                                color = ReadoutInkPrimary
                            )
                            Text(
                                text = "No emitters match the current filter yet. Tap Refresh or switch to All.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ReadoutInkSecondary
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.any { it.pressed }) {
                                            touchFrozen = true
                                            lastInteractionToken = System.nanoTime()
                                        }
                                    }
                                }
                            }
                            .testTag("scanner_emitter_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = displayedEmitters,
                            key = { it.macAddress }
                        ) { emitter ->
                            EmitterListRow(
                                emitter = emitter,
                                isLocked = emitter.macAddress == lockedTargetMac,
                                onInspect = { onInspectEmitter(emitter) },
                                onLock = { onLockEmitter(emitter) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Dedicated Plain-English Cellular Tower Dossier Sheet
    val inspectedTower = selectedCellTower
    if (inspectedTower != null) {
        CellTowerDossierSheet(
            tower = inspectedTower,
            cellularSnapshot = cellularSnapshot,
            onDismiss = { selectedCellTower = null }
        )
    }
}

@Composable
private fun EmitterListRow(
    emitter: DiscoveredEmitter,
    isLocked: Boolean,
    onInspect: () -> Unit,
    onLock: () -> Unit
) {
    val borderColor = when {
        isLocked -> SignalEmerald
        emitter.bleDecoded?.trackerTag?.isPotentialUnwantedTracker == true -> TacticalCrimson.copy(alpha = 0.8f)
        emitter.isRttResponder -> SignalEmerald.copy(alpha = 0.55f)
        else -> TacticalOutline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isLocked) SignalEmerald.copy(alpha = 0.12f) else ElevatedSurface)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onInspect)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag("emitter_row_${emitter.macAddress}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Transport icon
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ObsidianBackground)
                .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (emitter.transport) {
                    EmitterTransport.WIFI -> Icons.Filled.Wifi
                    EmitterTransport.BLE -> Icons.Filled.Bluetooth
                    EmitterTransport.EXTERNAL_USB -> Icons.Filled.Usb
                },
                contentDescription = emitter.transport.shortLabel,
                tint = if (emitter.isRttResponder) SignalEmerald else ElectricCyan,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Identity + Badges + MAC
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (emitter.isRttResponder) {
                    MiniScannerBadge("[RTT]", SignalEmerald)
                }
                if (emitter.isMacRotated) {
                    MiniScannerBadge("ROT×${emitter.observedMacCount}", ThermalAmber)
                }
                if (emitter.bleDecoded?.trackerTag?.isPotentialUnwantedTracker == true) {
                    MiniScannerBadge("TRACKER", TacticalCrimson)
                }
                Text(
                    text = emitter.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = ReadoutInkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            val subtitle = buildString {
                append(emitter.macAddress)
                emitter.bleDecoded?.summaryLine?.let { sum ->
                    if (sum.isNotBlank() && sum != "No BLE AD payload") {
                        append(" · ")
                        append(sum)
                    }
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ReadoutInkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // RSSI + Packet Rate (Hz)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${emitter.rssiDbm} dBm",
                style = TelemetrySubValueStyle,
                color = when {
                    emitter.rssiDbm >= -55 -> SignalEmerald
                    emitter.rssiDbm >= -75 -> ThermalAmber
                    else -> ReadoutInkSecondary
                }
            )
            Text(
                text = String.format(Locale.US, "%.1f Hz", emitter.packetRateHz),
                style = MaterialTheme.typography.labelSmall,
                color = if (emitter.packetRateHz >= 2.0f) ElectricCyan else ReadoutInkMuted
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Direct Lock Action Button
        Surface(
            onClick = onLock,
            shape = RoundedCornerShape(8.dp),
            color = if (isLocked) SignalEmerald.copy(alpha = 0.25f) else ElectricCyan.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, if (isLocked) SignalEmerald else ElectricCyan.copy(alpha = 0.6f)),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("lock_emitter_${emitter.macAddress}")
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.GpsFixed,
                    contentDescription = "Lock target ${emitter.displayName}",
                    tint = if (isLocked) SignalEmerald else ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniScannerBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.20f))
            .border(1.dp, color.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun CellularTowersPanel(
    snapshot: CellularSnapshot,
    onSelectTower: (CellTowerInfo) -> Unit,
    onRequestPhoneStatePermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 2G Stingray / IMSI-Catcher Downgrade Alert Banner
        if (snapshot.is2gDowngradeAlert) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TacticalCrimson.copy(alpha = 0.18f))
                    .border(1.dp, TacticalCrimson, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "2G downgrade alert",
                    tint = TacticalCrimson,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "2G STINGRAY / DOWNGRADE ALERT",
                        style = MaterialTheme.typography.labelLarge,
                        color = TacticalCrimson
                    )
                    Text(
                        text = snapshot.downgradeReason ?: "Serving cell dropped to legacy 2G GSM",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadoutInkPrimary
                    )
                }
            }
        }

        // Strict On-Demand READ_PHONE_STATE Permission Banner (never in startup gate)
        if (!snapshot.hasPhoneStatePermission) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ThermalAmber.copy(alpha = 0.12f))
                    .border(1.dp, ThermalAmber.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = ThermalAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Basic CellInfo (PCI, RSRP, TA) active via Location",
                        style = MaterialTheme.typography.labelSmall,
                        color = ThermalAmber
                    )
                }
                Button(
                    onClick = onRequestPhoneStatePermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ThermalAmber,
                        contentColor = ObsidianBackground
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("grant_phone_state_button")
                ) {
                    Text(
                        text = "Grant Phone State for 2G Downgrade Alert",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        } else {
            // Active voice/data RAT summary chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianBackground)
                    .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "DATA: ${snapshot.dataNetworkTypeName ?: "--"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Text(
                    text = "VOICE: ${snapshot.voiceNetworkTypeName ?: "--"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (snapshot.is2gDowngradeAlert) TacticalCrimson else SignalEmerald
                )
            }
        }

        if (snapshot.towers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CellTower,
                        contentDescription = null,
                        tint = ReadoutInkMuted,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "No Cellular Towers Reported",
                        style = MaterialTheme.typography.titleSmall,
                        color = ReadoutInkPrimary
                    )
                    Text(
                        text = "Airplane mode, missing SIM, or restricted radio cache. Tap Refresh when cellular modem is active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadoutInkSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("cellular_tower_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = snapshot.towers,
                    key = { idx, it -> "${idx}_${it.generation.name}_${it.isServing}_${it.pci}_${it.arfcn}" }
                ) { idx, tower ->
                    CellTowerCard(
                        tower = tower,
                        onClick = { onSelectTower(tower) },
                        testTag = "cell_tower_row_$idx"
                    )
                }
            }
        }
    }
}

@Composable
private fun CellTowerCard(
    tower: CellTowerInfo,
    onClick: () -> Unit,
    testTag: String
) {
    val genColor = when (tower.generation) {
        CellRadioGeneration.NR_5G -> ElectricCyan
        CellRadioGeneration.LTE_4G -> SignalEmerald
        CellRadioGeneration.WCDMA_3G -> ThermalAmber
        CellRadioGeneration.GSM_2G -> TacticalCrimson
    }
    val carrierLabel = CellularMonitor.lookupCarrierName(tower.plmn)
        ?: "PLMN ${tower.plmn ?: "--"}"
    val bandLabel = CellularMonitor.deriveBandLabel(tower.generation, tower.arfcn, tower.bands)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface)
            .border(
                1.dp,
                if (tower.isServing) genColor.copy(alpha = 0.75f) else TacticalOutline,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniScannerBadge(tower.generation.shortLabel, genColor)
            if (tower.isServing) {
                MiniScannerBadge("SERVING", SignalEmerald)
            } else {
                MiniScannerBadge("NEIGHBOR", ReadoutInkSecondary)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = carrierLabel,
                style = MaterialTheme.typography.labelSmall,
                color = ReadoutInkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(ObsidianBackground)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CellMetric("PCI", tower.pci?.toString() ?: "--", ReadoutInkPrimary)
            CellMetric(
                "RSRP",
                tower.rsrpDbm?.let { "$it dBm" } ?: "--",
                if ((tower.rsrpDbm ?: -120) >= -85) SignalEmerald else ThermalAmber
            )
            CellMetric("SINR", tower.sinrDb?.let { "$it dB" } ?: "--", ElectricCyan)
            CellMetric(
                "TA DIST",
                tower.timingAdvanceDistanceMeters?.let {
                    String.format(Locale.US, "%.0fm", it)
                } ?: "--",
                ThermalAmber
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Band: $bandLabel · ARFCN ${tower.arfcn ?: "--"}",
                style = MaterialTheme.typography.labelSmall,
                color = ReadoutInkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "DOSSIER ▸",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
        }
    }
}

@Composable
private fun CellMetric(label: String, value: String, valueColor: Color) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = ReadoutInkMuted)
        Text(text = value, style = TelemetrySubValueStyle, color = valueColor)
    }
}
