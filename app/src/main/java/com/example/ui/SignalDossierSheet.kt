package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.dsp.EmitterDossierData
import com.example.dsp.EmitterTransport
import com.example.dsp.RssiSamplePoint
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
import com.example.ui.theme.TacticalGridLine
import com.example.ui.theme.TacticalOutline
import com.example.ui.theme.TelemetrySubValueStyle
import com.example.ui.theme.TelemetryValueStyle
import com.example.ui.theme.ThermalAmber
import com.example.ui.theme.ThermalYellow
import java.util.Locale

/**
 * Strictly Passive RF Signal Dossier Sheet:
 * - Prominent "Lock 1m Radar Target" CTA at the top (never initiates active GATT connections).
 * - Live RSSI Sparkline plotting Raw RSSI vs. strictly negative Kalman dBm (-30 to -100 dBm).
 * - Packet rate (Hz), Inter-Arrival Jitter (ms), Clock Skew (ppm), Shape Stage & Fingerprint Hash.
 * - MAC Rotation History linked under the same fingerprintHash.
 * - Verbose Plain-English BLE AD Structure Breakdown displayed cleanly above the Raw Hex Dump.
 */
@Composable
fun SignalDossierSheet(
    dossier: EmitterDossierData,
    onLockTarget: (EmitterDossierData) -> Unit,
    onUnlockTarget: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .border(1.dp, TacticalOutline, RoundedCornerShape(16.dp))
                .testTag("signal_dossier_sheet"),
            shape = RoundedCornerShape(16.dp),
            color = CharcoalSurface
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianBackground)
                            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (dossier.transport) {
                                EmitterTransport.WIFI -> Icons.Filled.Wifi
                                EmitterTransport.BLE -> Icons.Filled.Bluetooth
                                EmitterTransport.EXTERNAL_USB -> Icons.Filled.Usb
                            },
                            contentDescription = dossier.transport.shortLabel,
                            tint = if (dossier.isRttResponder) SignalEmerald else ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dossier.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = ReadoutInkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${dossier.macAddress} · PASSIVE RF DOSSIER",
                            style = MaterialTheme.typography.labelSmall,
                            color = ReadoutInkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("dossier_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog),
                            tint = ReadoutInkSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Prominent Primary Action: Lock 1m Radar Target (or Unlock if active)
                Button(
                    onClick = {
                        if (dossier.isLocked) onUnlockTarget() else onLockTarget(dossier)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dossier.isLocked) TacticalCrimson else SignalEmerald,
                        contentColor = ObsidianBackground
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("dossier_lock_target_button")
                ) {
                    Icon(
                        imageVector = if (dossier.isLocked) Icons.Filled.LockOpen else Icons.Filled.GpsFixed,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (dossier.isLocked) {
                            "LOCKED ON RADAR · TAP TO UNLOCK"
                        } else {
                            "LOCK 1m RADAR TARGET"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = TacticalOutline)

                // Scrollable Passive Telemetry & Forensics Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Potential Unwanted Tracker Warning Pill (Apple Find My Separated / SmartTag / Tile)
                    val trackerTag = dossier.bleDecoded?.trackerTag
                    if (trackerTag != null) {
                        val isAlert = trackerTag.isPotentialUnwantedTracker
                        val tint = if (isAlert) TacticalCrimson else ElectricCyan
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tint.copy(alpha = 0.16f))
                                .border(1.dp, tint.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isAlert) "TRACKER SIGNATURE DETECTED" else "BLE CONTINUITY CLASSIFICATION",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint
                                )
                                Text(
                                    text = trackerTag.displayLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ReadoutInkPrimary
                                )
                            }
                        }
                    }

                    // Live Raw vs. Negative Kalman RSSI Sparkline
                    RssiSparklineCard(
                        history = dossier.rssiHistory,
                        latestRawDbm = dossier.latestRawRssiDbm,
                        latestKalmanDbm = dossier.latestKalmanRssiDbm
                    )

                    // Timing & Geometry Telemetry Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DossierStatTile(
                            label = "RATE",
                            value = String.format(Locale.US, "%.1f Hz", dossier.packetRateHz),
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        DossierStatTile(
                            label = "JITTER",
                            value = String.format(Locale.US, "%.0f ms", dossier.interArrivalJitterMs),
                            accent = ThermalAmber,
                            modifier = Modifier.weight(1f)
                        )
                        DossierStatTile(
                            label = "CLK SKEW",
                            value = String.format(Locale.US, "%+.0f ppm", dossier.clockSkewPpm),
                            accent = SignalEmerald,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DossierStatTile(
                            label = "6-STAGE SHAPE",
                            value = dossier.shapeStage?.let { "${it.label} (${it.spreadBandLabel})" } ?: "--",
                            accent = if (dossier.isPinned) ThermalYellow else ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        DossierStatTile(
                            label = "EST RANGE",
                            value = dossier.estimatedDistanceMeters?.let {
                                String.format(Locale.US, "%.1f m", it)
                            } ?: "--",
                            accent = SignalEmerald,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Fingerprint & MAC Rotation History Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElevatedSurface)
                            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Fingerprint,
                                contentDescription = null,
                                tint = ThermalAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "FINGERPRINT & MAC ROTATION HISTORY",
                                style = MaterialTheme.typography.labelSmall,
                                color = ThermalAmber
                            )
                        }
                        Text(
                            text = "ID: ${dossier.fingerprintHash}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ReadoutInkPrimary
                        )
                        if (dossier.macHistory.isEmpty()) {
                            Text(
                                text = "Current MAC: ${dossier.macAddress}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ReadoutInkSecondary
                            )
                        } else {
                            dossier.macHistory.forEachIndexed { idx, rec ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ObsidianBackground)
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (idx == 0) "${rec.macAddress} (ACTIVE)" else rec.macAddress,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (idx == 0) ElectricCyan else ReadoutInkSecondary
                                    )
                                    Text(
                                        text = "${rec.lastRssiDbm} dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ReadoutInkMuted
                                    )
                                }
                            }
                        }
                    }

                    // Verbose Plain-English BLE AD Structure Breakdown (ABOVE Raw Hex Dump)
                    val ble = dossier.bleDecoded
                    if (ble != null && ble.fields.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ElevatedSurface)
                                .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "DECODED BLE AD STRUCTURES (PASSIVE)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                            ble.fields.forEach { field ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ObsidianBackground)
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(ElectricCyan.copy(alpha = 0.18f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = field.adTypeHex,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ElectricCyan
                                            )
                                        }
                                        Text(
                                            text = field.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ReadoutInkPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = field.value,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ReadoutInkSecondary
                                    )
                                }
                            }
                        }

                        // Raw Monospaced Hex Dump BELOW Plain-English Breakdown
                        if (ble.rawHexDump.isNotBlank()) {
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
                                    text = "RAW ADVERTISING PAYLOAD HEX",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ReadoutInkMuted
                                )
                                Text(
                                    text = ble.rawHexDump,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ReadoutInkSecondary
                                )
                            }
                        }
                    } else if (dossier.transport == EmitterTransport.WIFI) {
                        // Wi-Fi Passive Beacon Metadata
                        val sr = dossier.scanResult
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ElevatedSurface)
                                .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "WI-FI BEACON METADATA (PASSIVE)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                            Text(
                                text = "BSSID Key: ${dossier.macAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ReadoutInkPrimary
                            )
                            if (sr != null) {
                                Text(
                                    text = "Center Freq: ${sr.frequency} MHz · RTT 802.11mc/az: ${if (dossier.isRttResponder) "SUPPORTED" else "NO"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ReadoutInkSecondary
                                )
                                if (!sr.capabilities.isNullOrBlank()) {
                                    Text(
                                        text = "Capabilities: ${sr.capabilities}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ReadoutInkMuted
                                    )
                                }
                            }
                        }
                    }

                    // Strict Passive Compliance Footer
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ObsidianBackground,
                        border = BorderStroke(1.dp, TacticalOutline)
                    ) {
                        Text(
                            text = "Strictly passive RF inspection: zero GATT connections, zero active probes, zero transmits.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadoutInkMuted,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RssiSparklineCard(
    history: List<RssiSamplePoint>,
    latestRawDbm: Int,
    latestKalmanDbm: Float
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "RSSI SPARKLINE (RAW vs KALMAN)",
                style = MaterialTheme.typography.labelSmall,
                color = ReadoutInkSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "RAW ${latestRawDbm} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = ThermalAmber
                )
                Text(
                    text = String.format(Locale.US, "KAL %.1f dBm", latestKalmanDbm),
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ObsidianBackground)
                .border(1.dp, TacticalGridLine, RoundedCornerShape(8.dp))
        ) {
            val w = size.width
            val h = size.height
            val minDbm = -100f
            val maxDbm = -30f
            val padY = 10f
            val usableH = (h - 2f * padY).coerceAtLeast(1f)

            fun dbmToY(dbm: Float): Float {
                val clamped = dbm.coerceIn(minDbm, maxDbm)
                val norm = (clamped - minDbm) / (maxDbm - minDbm)
                return h - padY - norm * usableH
            }

            // Horizontal dBm reference lines (-40, -60, -80)
            val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
            for (refDbm in listOf(-40f, -60f, -80f)) {
                val y = dbmToY(refDbm)
                drawLine(
                    color = TacticalGridLine,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                    pathEffect = dash
                )
            }

            if (history.size >= 2) {
                val rawPath = Path()
                val kalmanPath = Path()
                val stepX = w / (history.size - 1).coerceAtLeast(1)

                history.forEachIndexed { i, pt ->
                    val x = i * stepX
                    val yRaw = dbmToY(pt.rawDbm.toFloat())
                    val yKal = dbmToY(pt.kalmanDbm)
                    if (i == 0) {
                        rawPath.moveTo(x, yRaw)
                        kalmanPath.moveTo(x, yKal)
                    } else {
                        rawPath.lineTo(x, yRaw)
                        kalmanPath.lineTo(x, yKal)
                    }
                }

                drawPath(
                    path = rawPath,
                    color = ThermalAmber.copy(alpha = 0.65f),
                    style = Stroke(width = 1.8f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = kalmanPath,
                    color = ElectricCyan,
                    style = Stroke(width = 2.8f, cap = StrokeCap.Round)
                )
            } else if (history.size == 1) {
                val pt = history.first()
                drawCircle(ThermalAmber, radius = 4f, center = Offset(w * 0.5f, dbmToY(pt.rawDbm.toFloat())))
                drawCircle(ElectricCyan, radius = 5f, center = Offset(w * 0.5f, dbmToY(pt.kalmanDbm)))
            }
        }
    }
}

@Composable
private fun DossierStatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ElevatedSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
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
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Dedicated Plain-English Cellular Tower Dossier Sheet opened when tapping any
 * Serving or Neighbor cell row in the Scanner's Cell Towers tab.
 */
@Composable
fun CellTowerDossierSheet(
    tower: CellTowerInfo,
    cellularSnapshot: CellularSnapshot,
    onDismiss: () -> Unit
) {
    val genColor = when (tower.generation) {
        CellRadioGeneration.NR_5G -> ElectricCyan
        CellRadioGeneration.LTE_4G -> SignalEmerald
        CellRadioGeneration.WCDMA_3G -> ThermalAmber
        CellRadioGeneration.GSM_2G -> TacticalCrimson
    }
    val carrierName = CellularMonitor.lookupCarrierName(tower.plmn)
    val bandLabel = CellularMonitor.deriveBandLabel(tower.generation, tower.arfcn, tower.bands)
    val cellIdLabel = when (tower.generation) {
        CellRadioGeneration.NR_5G -> "NCI (5G NR Cell ID)"
        CellRadioGeneration.LTE_4G -> "ECI (4G LTE Cell ID)"
        CellRadioGeneration.WCDMA_3G -> "CID (3G Cell ID)"
        CellRadioGeneration.GSM_2G -> "CID (2G Cell ID)"
    }
    val pciLabel = when (tower.generation) {
        CellRadioGeneration.NR_5G, CellRadioGeneration.LTE_4G -> "PCI (Physical Cell ID)"
        CellRadioGeneration.WCDMA_3G -> "PSC (Primary Scrambling Code)"
        CellRadioGeneration.GSM_2G -> "BSIC (Base Station ID Code)"
    }
    val areaLabel = when (tower.generation) {
        CellRadioGeneration.NR_5G, CellRadioGeneration.LTE_4G -> "TAC (Tracking Area Code)"
        CellRadioGeneration.WCDMA_3G, CellRadioGeneration.GSM_2G -> "LAC (Location Area Code)"
    }

    val isTower2gRisk = tower.generation == CellRadioGeneration.GSM_2G ||
        (tower.isServing && cellularSnapshot.is2gDowngradeAlert)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .border(1.dp, TacticalOutline, RoundedCornerShape(16.dp))
                .testTag("cell_tower_dossier_sheet"),
            shape = RoundedCornerShape(16.dp),
            color = CharcoalSurface
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianBackground)
                            .border(1.dp, genColor.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CellTower,
                            contentDescription = tower.generation.shortLabel,
                            tint = genColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = carrierName ?: "PLMN ${tower.plmn ?: "Unknown Carrier"}",
                            style = MaterialTheme.typography.titleMedium,
                            color = ReadoutInkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${tower.generation.shortLabel} · ${if (tower.isServing) "SERVING CELL" else "NEIGHBOR CELL"} · PCI ${tower.pci ?: "--"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = genColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("cell_dossier_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog),
                            tint = ReadoutInkSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = TacticalOutline)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Stingray / 2G Downgrade Anomaly Card
                    val anomalyColor = when {
                        isTower2gRisk -> TacticalCrimson
                        !cellularSnapshot.hasPhoneStatePermission -> ThermalAmber
                        else -> SignalEmerald
                    }
                    val anomalyTitle = when {
                        isTower2gRisk -> "STINGRAY / 2G DOWNGRADE ANOMALY"
                        !cellularSnapshot.hasPhoneStatePermission -> "ANOMALY WATCH · PARTIAL (GRANT PHONE STATE)"
                        else -> "STINGRAY / DOWNGRADE STATUS: NOMINAL"
                    }
                    val anomalyDetail = when {
                        tower.generation == CellRadioGeneration.GSM_2G ->
                            cellularSnapshot.downgradeReason
                                ?: "This tower broadcasts legacy 2G GSM without mutual authentication (A5/0 or weak cipher risk). Fake base stations (IMSI catchers) often force devices onto loud 2G cells."
                        tower.isServing && cellularSnapshot.is2gDowngradeAlert ->
                            cellularSnapshot.downgradeReason
                                ?: "Active voice or data link downgraded to legacy 2G GSM."
                        !cellularSnapshot.hasPhoneStatePermission ->
                            "Tower is modern ${tower.generation.shortLabel} with mutual authentication. Grant optional Phone State in the Cell Towers tab to also monitor live Voice/Data RAT downgrades."
                        else ->
                            "Modern encrypted ${tower.generation.shortLabel} cell. Data RAT: ${cellularSnapshot.dataNetworkTypeName ?: "--"}, Voice RAT: ${cellularSnapshot.voiceNetworkTypeName ?: "--"} — no 2G downgrade detected."
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(anomalyColor.copy(alpha = 0.14f))
                            .border(1.dp, anomalyColor.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isTower2gRisk) Icons.Filled.Warning else Icons.Filled.Security,
                            contentDescription = null,
                            tint = anomalyColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = anomalyTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = anomalyColor
                            )
                            Text(
                                text = anomalyDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = ReadoutInkPrimary
                            )
                        }
                    }

                    // 2. Signal Strength & Quality Strip (RSRP, RSRQ, SINR)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DossierStatTile(
                            label = "RSRP / PWR",
                            value = tower.rsrpDbm?.let { "$it dBm" } ?: "--",
                            accent = if ((tower.rsrpDbm ?: -120) >= -90) SignalEmerald else ThermalAmber,
                            modifier = Modifier.weight(1f)
                        )
                        DossierStatTile(
                            label = "RSRQ",
                            value = tower.rsrqDb?.let { "$it dB" } ?: "--",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        DossierStatTile(
                            label = "SINR",
                            value = tower.sinrDb?.let { "$it dB" } ?: "--",
                            accent = if ((tower.sinrDb ?: -10) >= 10) SignalEmerald else ThermalAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // 3. Serving vs. Neighbor Role Explanation
                    CellExplanationCard(
                        title = if (tower.isServing) "ROLE: SERVING CELL (ACTIVE LINK)" else "ROLE: NEIGHBOR CELL (PASSIVE HANDOVER CANDIDATE)",
                        accent = if (tower.isServing) SignalEmerald else ElectricCyan,
                        body = if (tower.isServing) {
                            "Your modem is actively camped on and exchanging signaling/data with this sector. Because the tower synchronizes uplink timing with your phone, Serving cells provide the primary Timing Advance (TA) distance estimate and carrier network identifiers."
                        } else {
                            "Your modem passively measures this nearby sector's broadcast beacon so it can hand over seamlessly if you move. Neighbor cells often omit NCI/ECI and Timing Advance because your phone is not transmitting uplink bursts to them."
                        }
                    )

                    // 4. Plain-English Identity & RF Parameters Breakdown
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElevatedSurface)
                            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "TOWER IDENTITY & RADIO PARAMETERS",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )

                        CellParameterRow(
                            label = "Carrier (MCC-MNC)",
                            value = buildString {
                                append(tower.plmn ?: "Not broadcast")
                                if (carrierName != null) append(" · $carrierName")
                            },
                            explanation = "Mobile Country Code & Mobile Network Code (e.g., 302 = Canada: Rogers 720, Telus 220, Bell 610, Freedom 490)."
                        )
                        CellParameterRow(
                            label = "Radio Generation",
                            value = tower.generation.shortLabel,
                            explanation = when (tower.generation) {
                                CellRadioGeneration.NR_5G -> "5G New Radio — highest bandwidth and strongest encryption/SUCI privacy."
                                CellRadioGeneration.LTE_4G -> "4G LTE — modern encrypted cellular link with 78.1m Timing Advance steps."
                                CellRadioGeneration.WCDMA_3G -> "3G UMTS/HSPA — legacy network being phased out across North America."
                                CellRadioGeneration.GSM_2G -> "2G GSM — unencrypted/weakly encrypted legacy protocol; primary Stingray downgrade target."
                            }
                        )
                        CellParameterRow(
                            label = pciLabel,
                            value = tower.pci?.toString() ?: "Unavailable",
                            explanation = "Short local radio fingerprint (0–503 in LTE, 0–1007 in 5G NR) distinguishing individual tower sectors on the same frequency."
                        )
                        CellParameterRow(
                            label = cellIdLabel,
                            value = tower.cellIdentityId?.toString() ?: "Not reported (neighbor/restricted)",
                            explanation = "Unique tower + sector identifier within the carrier network (combines eNB/gNB base station ID and sector ID)."
                        )
                        CellParameterRow(
                            label = areaLabel,
                            value = tower.tacOrLac?.toString() ?: "Not reported",
                            explanation = "Geographic paging zone grouping nearby towers. Sudden unexpected TAC changes while stationary can signal a rogue cell."
                        )
                        CellParameterRow(
                            label = "ARFCN & Frequency Band",
                            value = "${tower.arfcn?.toString() ?: "--"} · $bandLabel",
                            explanation = "Absolute Radio-Frequency Channel Number defining the exact carrier frequency. Lower bands (600–850 MHz) travel farther; mid-bands (1700–3700 MHz) carry higher capacity."
                        )
                    }

                    // 5. Signal Quality & Timing Advance Distance Explanation
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElevatedSurface)
                            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "SIGNAL QUALITY & TIMING ADVANCE RANGE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ThermalAmber
                        )

                        CellParameterRow(
                            label = "RSRP Signal Strength",
                            value = tower.rsrpDbm?.let { "$it dBm" } ?: "Unavailable",
                            explanation = "${CellularMonitor.rsrpQualitySummary(tower.rsrpDbm)}. Measures raw reference signal power from this tower sector (closer to 0 dBm = stronger)."
                        )
                        CellParameterRow(
                            label = "RSRQ & SINR Quality",
                            value = "RSRQ ${tower.rsrqDb?.let { "$it dB" } ?: "--"} · SINR ${tower.sinrDb?.let { "$it dB" } ?: "--"}",
                            explanation = "RSRQ (−3 to −20 dB) reflects tower congestion/interference; SINR (>13 dB excellent, <0 dB noisy) measures signal clarity above RF noise."
                        )
                        val taDistFormatted = tower.timingAdvanceDistanceMeters?.let { meters ->
                            if (meters >= 1000f) {
                                String.format(Locale.US, "%.2f km (%.0f m)", meters / 1000f, meters)
                            } else {
                                String.format(Locale.US, "%.0f m", meters)
                            }
                        } ?: "Unavailable"
                        val taStepNote = when (tower.generation) {
                            CellRadioGeneration.LTE_4G -> "Each LTE TA unit equals ~78.1 m of one-way RF propagation distance."
                            CellRadioGeneration.GSM_2G -> "Each GSM TA unit equals ~553.8 m of one-way RF propagation distance."
                            else -> "Android only exposes hardware Timing Advance indices on active LTE and GSM serving links."
                        }
                        CellParameterRow(
                            label = "Timing Advance (TA) Distance",
                            value = if (tower.timingAdvance != null) "TA ${tower.timingAdvance} → ~$taDistFormatted" else taDistFormatted,
                            explanation = "$taStepNote Reported only when actively synchronized with the serving tower."
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CellExplanationCard(
    title: String,
    accent: Color,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = ReadoutInkSecondary
        )
    }
}

@Composable
private fun CellParameterRow(
    label: String,
    value: String,
    explanation: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ObsidianBackground)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ReadoutInkMuted
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = ReadoutInkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = ReadoutInkSecondary
        )
    }
}
