package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ExportFormat
import com.example.dsp.DiscoveredEmitter
import com.example.dsp.PerimeterGuardEngine
import com.example.dsp.PerimeterScope
import com.example.dsp.PerimeterTriggerMode
import com.example.dsp.WatchlistMatchType
import com.example.sensors.CellTowerInfo
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
import com.example.ui.theme.ThermalAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tab 2: Auto-Freezing Live Scanner + Expandable Custom Watchlist Manager.
 */
@Composable
fun SignalsAndWatchlistTab(
    uiState: RadarUiState,
    onInspectEmitter: (DiscoveredEmitter) -> Unit,
    onLockEmitter: (DiscoveredEmitter) -> Unit,
    onRefreshWifiScan: () -> Unit,
    onRefreshCellular: () -> Unit,
    onRequestPhoneStatePermission: () -> Unit,
    onTrackTowerOnRadar: (CellTowerInfo) -> Unit,
    onAddWatchlistEntry: (WatchlistMatchType, String, String) -> Unit,
    onDeleteWatchlistEntry: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var watchlistExpanded by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(WatchlistMatchType.OUI) }
    var patternInput by remember { mutableStateOf("") }
    var labelInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Expandable Custom Watchlist Manager
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CharcoalSurface)
                .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { watchlistExpanded = !watchlistExpanded }
                    .testTag("watchlist_manager_toggle"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = null,
                    tint = ThermalAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "CUSTOM WATCHLIST (${uiState.watchlistEntries.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = ReadoutInkPrimary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (watchlistExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = ReadoutInkSecondary
                )
            }

            if (watchlistExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(WatchlistMatchType.OUI, WatchlistMatchType.SUBSTRING).forEach { type ->
                        val active = selectedType == type
                        Surface(
                            onClick = { selectedType = type },
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) ElectricCyan.copy(alpha = 0.2f) else ElevatedSurface,
                            border = BorderStroke(1.dp, if (active) ElectricCyan else TacticalOutline)
                        ) {
                            Text(
                                text = type.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) ElectricCyan else ReadoutInkSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = patternInput,
                        onValueChange = { patternInput = it },
                        placeholder = {
                            Text(
                                text = if (selectedType == WatchlistMatchType.OUI) "OUI (e.g. 60:60:1F)" else "SSID / Name",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = TacticalOutline,
                            focusedTextColor = ReadoutInkPrimary,
                            unfocusedTextColor = ReadoutInkPrimary
                        ),
                        modifier = Modifier.weight(1.2f)
                    )
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        placeholder = { Text("Label", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = TacticalOutline,
                            focusedTextColor = ReadoutInkPrimary,
                            unfocusedTextColor = ReadoutInkPrimary
                        ),
                        modifier = Modifier.weight(0.9f)
                    )
                    IconButton(
                        onClick = {
                            if (patternInput.isNotBlank()) {
                                onAddWatchlistEntry(selectedType, patternInput, labelInput)
                                patternInput = ""
                                labelInput = ""
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SignalEmerald)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add Watchlist", tint = ObsidianBackground)
                    }
                }

                if (uiState.watchlistEntries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        uiState.watchlistEntries.take(6).forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ObsidianBackground)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "[${entry.matchType.name}] ${entry.label} (${entry.pattern})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ThermalAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onDeleteWatchlistEntry(entry.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Remove",
                                        tint = TacticalCrimson,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Embedded Auto-Freezing Live Scanner
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, TacticalOutline, RoundedCornerShape(14.dp)),
            shape = RoundedCornerShape(14.dp),
            color = CharcoalSurface
        ) {
            ScannerTabContent(
                emitters = uiState.discoveredEmitters,
                cellularSnapshot = uiState.cellularSnapshot,
                lockedTargetMac = uiState.lockedTargetMac,
                onInspectEmitter = onInspectEmitter,
                onLockEmitter = onLockEmitter,
                onRefreshWifiScan = onRefreshWifiScan,
                onRefreshCellular = onRefreshCellular,
                onRequestPhoneStatePermission = onRequestPhoneStatePermission,
                onTrackTowerOnRadar = onTrackTowerOnRadar,
                onDismiss = null,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/**
 * Tab 3: Perimeter Enter/Leave Guard, Saved SQLite PINNED Snapshots, Offline Map Cache & Export.
 */
@Composable
fun PerimeterAndLogTab(
    uiState: RadarUiState,
    onSetPerimeterEnabled: (Boolean) -> Unit,
    onSetPerimeterRadius: (Float) -> Unit,
    onSetPerimeterTriggerMode: (PerimeterTriggerMode) -> Unit,
    onSetPerimeterScope: (PerimeterScope) -> Unit,
    onClearPerimeterEvents: () -> Unit,
    onCacheMapArea: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onDeleteSnapshot: (Long) -> Unit,
    onClearAllSnapshots: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Perimeter Enter/Leave Guard Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CharcoalSurface)
                .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = if (uiState.perimeterEnabled) ThermalAmber else ReadoutInkSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PERIMETER ENTER / LEAVE GUARD",
                        style = MaterialTheme.typography.titleSmall,
                        color = ReadoutInkPrimary
                    )
                    Text(
                        text = "3-packet hysteresis · ${uiState.perimeterRadiusMeters.roundToInt()}m virtual bubble",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReadoutInkSecondary
                    )
                }
                Switch(
                    checked = uiState.perimeterEnabled,
                    onCheckedChange = onSetPerimeterEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ObsidianBackground,
                        checkedTrackColor = ThermalAmber
                    ),
                    modifier = Modifier.testTag("perimeter_guard_switch")
                )
            }

            Slider(
                value = uiState.perimeterRadiusMeters,
                onValueChange = onSetPerimeterRadius,
                valueRange = PerimeterGuardEngine.MIN_RADIUS_METERS..PerimeterGuardEngine.MAX_RADIUS_METERS,
                colors = SliderDefaults.colors(
                    thumbColor = ThermalAmber,
                    activeTrackColor = ThermalAmber
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PerimeterTriggerMode.entries.forEach { mode ->
                    val selected = uiState.perimeterTriggerMode == mode
                    Surface(
                        onClick = { onSetPerimeterTriggerMode(mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) ElectricCyan.copy(alpha = 0.2f) else ElevatedSurface,
                        border = BorderStroke(1.dp, if (selected) ElectricCyan else TacticalOutline)
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) ElectricCyan else ReadoutInkSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PerimeterScope.entries.forEach { scope ->
                    val selected = uiState.perimeterScope == scope
                    Surface(
                        onClick = { onSetPerimeterScope(scope) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) ThermalAmber.copy(alpha = 0.2f) else ElevatedSurface,
                        border = BorderStroke(1.dp, if (selected) ThermalAmber else TacticalOutline)
                    ) {
                        Text(
                            text = scope.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) ThermalAmber else ReadoutInkSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (uiState.perimeterEvents.isNotEmpty()) {
                HorizontalDivider(color = TacticalOutline)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CROSSING LOG (${uiState.perimeterEvents.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReadoutInkSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "CLEAR",
                        style = MaterialTheme.typography.labelSmall,
                        color = TacticalCrimson,
                        modifier = Modifier
                            .clickable(onClick = onClearPerimeterEvents)
                            .padding(4.dp)
                    )
                }
                uiState.perimeterEvents.take(6).forEach { ev ->
                    val evColor = if (ev.eventType == "ENTER") TacticalCrimson else ThermalAmber
                    Text(
                        text = "${timeFormatter.format(Date(ev.timestampEpochMs))} · ${ev.eventType} · ${ev.emitterName} (${String.format(Locale.US, "%.1fm", ev.distanceMeters)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = evColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 2. Offline Map Cache Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CharcoalSurface)
                .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "OFFLINE MAP TILE CACHE",
                    style = MaterialTheme.typography.titleSmall,
                    color = ReadoutInkPrimary
                )
                val mb = uiState.tileCacheState.cacheSizeBytes / (1024.0 * 1024.0)
                Text(
                    text = "${uiState.tileCacheState.cachedTileCount} CartoDB tiles (${String.format(Locale.US, "%.1f MB", mb)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = ReadoutInkSecondary
                )
            }
            Button(
                onClick = onCacheMapArea,
                enabled = !uiState.tileCacheState.isDownloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = ObsidianBackground
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (uiState.tileCacheState.isDownloading) "CACHING..." else "CACHE AREA",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // 3. Saved SQLite PINNED Snapshots & Export
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CharcoalSurface)
                .border(1.dp, TacticalOutline, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SAVED SQLITE SNAPSHOTS (${uiState.savedSnapshots.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = ReadoutInkPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (uiState.savedSnapshots.isNotEmpty()) {
                    IconButton(onClick = onClearAllSnapshots, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear All", tint = TacticalCrimson)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onExport(ExportFormat.JSON) },
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("EXPORT JSON", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = { onExport(ExportFormat.CSV) },
                    border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalEmerald),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("EXPORT CSV", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (uiState.savedSnapshots.isEmpty()) {
                Text(
                    text = "No pinned snapshots yet. Lock a target on Radar and tap SAVE & PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ReadoutInkMuted
                )
            } else {
                uiState.savedSnapshots.forEach { snap ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ObsidianBackground)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${snap.targetName} · ${String.format(Locale.US, "%.1fm", snap.minDistanceMeters)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = SignalEmerald,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${snap.targetMac} · ${snap.maxRssiDbm} dBm · ${snap.localizationTier}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ReadoutInkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onDeleteSnapshot(snap.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = TacticalCrimson, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
