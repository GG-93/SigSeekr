package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.dsp.DiscoveredEmitter
import com.example.dsp.EmitterTransport
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ReadoutInkMuted
import com.example.ui.theme.ReadoutInkPrimary
import com.example.ui.theme.ReadoutInkSecondary
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.TelemetrySubValueStyle
import com.example.ui.theme.TelemetryValueStyle
import com.example.ui.theme.ThermalAmber
import java.util.Locale

private enum class ScannerFilter(val label: String) {
    ALL("ALL"),
    RTT("RTT"),
    WIFI("Wi-Fi"),
    BLE("BLE")
}

/**
 * Single-Target Emitter Scanner Dialog.
 *
 * - Strictly sorts RTT responders (`isWifiRttResponder == true`) to the top with a visible `[RTT]` badge,
 *   followed by strongest live RSSI.
 * - Shows live packet broadcast rate in `Hz` alongside MAC, Name/SSID, and RSSI.
 * - Tapping any row locks the Bayesian grid exclusively to that MAC until "Reset Target".
 */
@Composable
fun ScannerDialog(
    emitters: List<DiscoveredEmitter>,
    lockedTargetMac: String?,
    onSelectEmitter: (DiscoveredEmitter) -> Unit,
    onRefreshWifiScan: () -> Unit,
    onDismiss: () -> Unit
) {
    var activeFilter by remember { mutableStateOf(ScannerFilter.ALL) }

    val filteredEmitters = remember(emitters, activeFilter) {
        when (activeFilter) {
            ScannerFilter.ALL -> emitters
            ScannerFilter.RTT -> emitters.filter { it.isRttResponder }
            ScannerFilter.WIFI -> emitters.filter { it.transport == EmitterTransport.WIFI }
            ScannerFilter.BLE -> emitters.filter { it.transport == EmitterTransport.BLE }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.84f)
                .border(1.dp, TacticalOutline, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = CharcoalSurface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sensors,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "RF EMITTER SCANNER",
                            style = MaterialTheme.typography.titleMedium,
                            color = ReadoutInkPrimary
                        )
                        Text(
                            text = "${filteredEmitters.size} active · RTT pinned first · Tap to lock MAC",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadoutInkSecondary
                        )
                    }
                    IconButton(
                        onClick = onRefreshWifiScan,
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

                Spacer(modifier = Modifier.height(10.dp))

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScannerFilter.entries.forEach { filter ->
                        val selected = activeFilter == filter
                        FilterChip(
                            selected = selected,
                            onClick = { activeFilter = filter },
                            label = {
                                Text(
                                    text = filter.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = ObsidianBackground,
                                labelColor = ReadoutInkSecondary,
                                selectedContainerColor = if (filter == ScannerFilter.RTT) {
                                    SignalEmerald.copy(alpha = 0.2f)
                                } else {
                                    ElectricCyan.copy(alpha = 0.18f)
                                },
                                selectedLabelColor = if (filter == ScannerFilter.RTT) {
                                    SignalEmerald
                                } else {
                                    ElectricCyan
                                }
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = TacticalOutline,
                                selectedBorderColor = if (filter == ScannerFilter.RTT) SignalEmerald else ElectricCyan
                            ),
                            modifier = Modifier.testTag("scanner_filter_${filter.name.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = TacticalOutline)
                Spacer(modifier = Modifier.height(8.dp))

                if (filteredEmitters.isEmpty()) {
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
                                imageVector = Icons.Filled.Sensors,
                                contentDescription = null,
                                tint = ReadoutInkMuted,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "Listening for Wi-Fi APs & BLE Beacons…",
                                style = MaterialTheme.typography.titleSmall,
                                color = ReadoutInkPrimary
                            )
                            Text(
                                text = "Ensure Wi-Fi, Bluetooth, and Location Services are enabled on the handset.",
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
                            .testTag("scanner_dialog_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredEmitters,
                            key = { it.macAddress }
                        ) { emitter ->
                            EmitterRow(
                                emitter = emitter,
                                isLocked = emitter.macAddress == lockedTargetMac,
                                onClick = { onSelectEmitter(emitter) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmitterRow(
    emitter: DiscoveredEmitter,
    isLocked: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when {
        isLocked -> ElectricCyan
        emitter.isRttResponder -> SignalEmerald.copy(alpha = 0.55f)
        else -> TacticalOutline
    }
    val bgColor = if (isLocked) ElectricCyan.copy(alpha = 0.10f) else ElevatedSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
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
                imageVector = if (emitter.transport == EmitterTransport.WIFI) {
                    Icons.Filled.Wifi
                } else {
                    Icons.Filled.Bluetooth
                },
                contentDescription = emitter.transport.shortLabel,
                tint = if (emitter.isRttResponder) SignalEmerald else ElectricCyan,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Name, [RTT] badge, MAC & transport chip
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (emitter.isRttResponder) {
                    Box(
                        modifier = Modifier
                            .background(SignalEmerald.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .border(1.dp, SignalEmerald, RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "[RTT]",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = SignalEmerald
                        )
                    }
                }
                Text(
                    text = emitter.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = ReadoutInkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isLocked) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = "Currently locked target",
                        tint = ElectricCyan,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = emitter.macAddress,
                    style = TelemetrySubValueStyle,
                    color = ReadoutInkSecondary
                )
                Text(
                    text = emitter.transport.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = ReadoutInkMuted
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Live RSSI + Packet Rate Hz
        Column(horizontalAlignment = Alignment.End) {
            val rssiColor = when {
                emitter.rssiDbm >= -55 -> SignalEmerald
                emitter.rssiDbm >= -72 -> ThermalAmber
                else -> ReadoutInkSecondary
            }
            Text(
                text = "${emitter.rssiDbm} dBm",
                style = TelemetryValueStyle,
                color = rssiColor
            )
            Text(
                text = String.format(Locale.US, "%.1f Hz", emitter.packetRateHz),
                style = MaterialTheme.typography.labelSmall,
                color = if (emitter.packetRateHz >= 2.0f) ElectricCyan else ReadoutInkMuted
            )
        }
    }
}
