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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LockOpen
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
