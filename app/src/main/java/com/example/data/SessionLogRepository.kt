package com.example.data

import android.content.Context
import android.net.Uri
import com.example.dsp.SessionSnapshotRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ExportFormat(val mimeType: String, val fileExtension: String) {
    JSON("application/json", "json"),
    CSV("text/csv", "csv")
}

/**
 * Offline repository wrapping [SessionLogDbHelper] with JSON and CSV
 * serialization for Android's Storage Access Framework (ACTION_CREATE_DOCUMENT).
 */
class SessionLogRepository(
    context: Context
) {
    private val appContext = context.applicationContext
    private val dbHelper = SessionLogDbHelper(appContext)

    private val _snapshots = MutableStateFlow<List<SessionSnapshotRecord>>(emptyList())
    val snapshots: StateFlow<List<SessionSnapshotRecord>> = _snapshots.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _snapshots.value = dbHelper.queryAllSnapshots()
    }

    suspend fun saveSnapshot(record: SessionSnapshotRecord): Long = withContext(Dispatchers.IO) {
        val insertedId = dbHelper.insertSnapshot(record)
        _snapshots.value = dbHelper.queryAllSnapshots()
        insertedId
    }

    suspend fun deleteSnapshot(id: Long) = withContext(Dispatchers.IO) {
        dbHelper.deleteSnapshot(id)
        _snapshots.value = dbHelper.queryAllSnapshots()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dbHelper.clearAll()
        _snapshots.value = emptyList()
    }

    suspend fun exportToUri(uri: Uri, format: ExportFormat): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val records = dbHelper.queryAllSnapshots()
            val payload = when (format) {
                ExportFormat.JSON -> formatAsJson(records)
                ExportFormat.CSV -> formatAsCsv(records)
            }
            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("Unable to open destination file stream")
            records.size
        }
    }

    companion object {
        private fun isoTimestamp(epochMs: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date(epochMs))
        }

        fun formatAsJson(records: List<SessionSnapshotRecord>): String {
            val root = JSONObject()
            root.put("app", "SigSeeker")
            root.put("exported_at_utc", isoTimestamp(System.currentTimeMillis()))
            root.put("snapshot_count", records.size)
            val arr = JSONArray()
            for (r in records) {
                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("timestamp_epoch_ms", r.timestampEpochMs)
                    put("timestamp_utc", isoTimestamp(r.timestampEpochMs))
                    put("target_mac", r.targetMac)
                    put("target_name", r.targetName)
                    put("estimated_x_m", r.estimatedX)
                    put("estimated_y_m", r.estimatedY)
                    put("min_distance_m", r.minDistanceMeters)
                    put("max_rssi_dbm", r.maxRssiDbm)
                    put("localization_tier", r.localizationTier)
                }
                arr.put(obj)
            }
            root.put("snapshots", arr)
            return root.toString(2)
        }

        fun formatAsCsv(records: List<SessionSnapshotRecord>): String {
            val sb = StringBuilder()
            sb.appendLine("id,timestamp_utc,target_mac,target_name,estimated_x_m,estimated_y_m,min_distance_m,max_rssi_dbm,localization_tier")
            for (r in records) {
                sb.append(r.id).append(',')
                sb.append(csvEscape(isoTimestamp(r.timestampEpochMs))).append(',')
                sb.append(csvEscape(r.targetMac)).append(',')
                sb.append(csvEscape(r.targetName)).append(',')
                sb.append(String.format(Locale.US, "%.2f", r.estimatedX)).append(',')
                sb.append(String.format(Locale.US, "%.2f", r.estimatedY)).append(',')
                sb.append(String.format(Locale.US, "%.2f", r.minDistanceMeters)).append(',')
                sb.append(r.maxRssiDbm).append(',')
                sb.appendLine(csvEscape(r.localizationTier))
            }
            return sb.toString()
        }

        private fun csvEscape(raw: String): String {
            val needsQuotes = raw.contains(',') || raw.contains('"') || raw.contains('\n') || raw.contains('\r')
            return if (needsQuotes) {
                "\"${raw.replace("\"", "\"\"")}\""
            } else {
                raw
            }
        }
    }
}
