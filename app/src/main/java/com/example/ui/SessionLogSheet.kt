package com.example.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.ExportFormat
import com.example.dsp.SessionSnapshotRecord
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline SQLite Session Log Dialog with JSON and CSV export via
 * Android's Storage Access Framework (Intent.ACTION_CREATE_DOCUMENT).
 */
@Composable
fun SessionLogDialog(
    snapshots: List<SessionSnapshotRecord>,
    onExport: (ExportFormat) -> Unit,
    onDeleteSnapshot: (Long) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val timeFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.82f)
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
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "OFFLINE SESSION LOG",
                            style = MaterialTheme.typography.titleMedium,
                            color = ReadoutInkPrimary
                        )
                        Text(
                            text = "${snapshots.size} SQLite snapshots stored locally",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadoutInkSecondary
                        )
                    }
                    if (snapshots.isNotEmpty()) {
                        IconButton(
                            onClick = onClearAll,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("session_log_clear_all")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = "Clear all saved snapshots",
                                tint = TacticalCrimson
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("session_log_close")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog),
                            tint = ReadoutInkSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Offline Export Controls (JSON / CSV via ACTION_CREATE_DOCUMENT)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onExport(ExportFormat.JSON) },
                        enabled = snapshots.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ElectricCyan,
                            disabledContentColor = ReadoutInkMuted
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (snapshots.isNotEmpty()) ElectricCyan.copy(alpha = 0.6f) else TacticalOutline
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("export_json_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "EXPORT JSON",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    OutlinedButton(
                        onClick = { onExport(ExportFormat.CSV) },
                        enabled = snapshots.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SignalEmerald,
                            disabledContentColor = ReadoutInkMuted
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (snapshots.isNotEmpty()) SignalEmerald.copy(alpha = 0.6f) else TacticalOutline
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("export_csv_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "EXPORT CSV",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = TacticalOutline)
                Spacer(modifier = Modifier.height(8.dp))

                if (snapshots.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = ReadoutInkMuted,
                                modifier = Modifier.size(38.dp)
                            )
                            Text(
                                text = "No Saved Localization Snapshots",
                                style = MaterialTheme.typography.titleSmall,
                                color = ReadoutInkPrimary
                            )
                            Text(
                                text = "Lock a target on the radar and tap SAVE & PIN to record timestamped coordinates, min distance, and peak RSSI offline.",
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
                            .testTag("session_log_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = snapshots,
                            key = { it.id }
                        ) { record ->
                            SnapshotRecordCard(
                                record = record,
                                formattedTime = timeFormatter.format(Date(record.timestampEpochMs)),
                                onDelete = { onDeleteSnapshot(record.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotRecordCard(
    record: SessionSnapshotRecord,
    formattedTime: String,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ElevatedSurface)
            .border(1.dp, TacticalOutline, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.targetName,
                    style = MaterialTheme.typography.titleSmall,
                    color = ReadoutInkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${record.targetMac} · $formattedTime",
                    style = MaterialTheme.typography.labelSmall,
                    color = ReadoutInkSecondary
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("delete_snapshot_${record.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete_snapshot),
                    tint = ReadoutInkMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(ObsidianBackground)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricMiniCell(
                label = "EST (X,Y)",
                value = String.format(Locale.US, "%.1f, %.1fm", record.estimatedX, record.estimatedY),
                valueColor = ElectricCyan
            )
            MetricMiniCell(
                label = "MIN DIST",
                value = String.format(Locale.US, "%.2f m", record.minDistanceMeters),
                valueColor = SignalEmerald
            )
            MetricMiniCell(
                label = "MAX RSSI",
                value = "${record.maxRssiDbm} dBm",
                valueColor = ThermalAmber
            )
        }
    }
}

@Composable
private fun MetricMiniCell(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ReadoutInkMuted
        )
        Text(
            text = value,
            style = TelemetrySubValueStyle,
            color = valueColor
        )
    }
}
