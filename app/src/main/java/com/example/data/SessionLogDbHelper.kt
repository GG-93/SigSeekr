package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.dsp.PerimeterEventRecord
import com.example.dsp.SessionSnapshotRecord
import com.example.dsp.WatchlistEntry
import com.example.dsp.WatchlistMatchType

/**
 * Pure Android SQLiteOpenHelper storage for offline scan session snapshots,
 * custom watchlist entries, and perimeter crossing events.
 * Zero Room/KSP/KAPT annotation processors required.
 */
class SessionLogDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "sigseeker_sessions.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_SNAPSHOTS = "scan_snapshots"
        private const val COL_ID = "_id"
        private const val COL_TIMESTAMP = "timestamp_ms"
        private const val COL_MAC = "target_mac"
        private const val COL_NAME = "target_name"
        private const val COL_EST_X = "estimated_x"
        private const val COL_EST_Y = "estimated_y"
        private const val COL_MIN_DIST = "min_distance_m"
        private const val COL_MAX_RSSI = "max_rssi_dbm"
        private const val COL_TIER = "localization_tier"

        private const val TABLE_WATCHLIST = "watchlist_entries"
        private const val COL_WL_TYPE = "match_type"
        private const val COL_WL_PATTERN = "pattern"
        private const val COL_WL_LABEL = "label"
        private const val COL_WL_CREATED = "created_at_ms"

        private const val TABLE_PERIMETER = "perimeter_events"
        private const val COL_PM_KEY = "emitter_key"
        private const val COL_PM_NAME = "emitter_name"
        private const val COL_PM_EVENT = "event_type"
        private const val COL_PM_DIST = "distance_m"
        private const val COL_PM_RSSI = "rssi_dbm"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SNAPSHOTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_MAC TEXT NOT NULL,
                $COL_NAME TEXT NOT NULL,
                $COL_EST_X REAL NOT NULL,
                $COL_EST_Y REAL NOT NULL,
                $COL_MIN_DIST REAL NOT NULL,
                $COL_MAX_RSSI INTEGER NOT NULL,
                $COL_TIER TEXT NOT NULL
            )
            """.trimIndent()
        )
        createAuxTables(db)
    }

    private fun createAuxTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_WATCHLIST (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_WL_TYPE TEXT NOT NULL,
                $COL_WL_PATTERN TEXT NOT NULL,
                $COL_WL_LABEL TEXT NOT NULL,
                $COL_WL_CREATED INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PERIMETER (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_PM_KEY TEXT NOT NULL,
                $COL_PM_NAME TEXT NOT NULL,
                $COL_PM_EVENT TEXT NOT NULL,
                $COL_PM_DIST REAL NOT NULL,
                $COL_PM_RSSI INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createAuxTables(db)
        }
    }

    fun insertSnapshot(record: SessionSnapshotRecord): Long {
        val values = ContentValues().apply {
            put(COL_TIMESTAMP, record.timestampEpochMs)
            put(COL_MAC, record.targetMac)
            put(COL_NAME, record.targetName)
            put(COL_EST_X, record.estimatedX)
            put(COL_EST_Y, record.estimatedY)
            put(COL_MIN_DIST, record.minDistanceMeters)
            put(COL_MAX_RSSI, record.maxRssiDbm)
            put(COL_TIER, record.localizationTier)
        }
        return writableDatabase.insert(TABLE_SNAPSHOTS, null, values)
    }

    fun queryAllSnapshots(): List<SessionSnapshotRecord> {
        val results = mutableListOf<SessionSnapshotRecord>()
        readableDatabase.query(
            TABLE_SNAPSHOTS,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC"
        ).use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(COL_ID)
            val idxTs = cursor.getColumnIndexOrThrow(COL_TIMESTAMP)
            val idxMac = cursor.getColumnIndexOrThrow(COL_MAC)
            val idxName = cursor.getColumnIndexOrThrow(COL_NAME)
            val idxX = cursor.getColumnIndexOrThrow(COL_EST_X)
            val idxY = cursor.getColumnIndexOrThrow(COL_EST_Y)
            val idxDist = cursor.getColumnIndexOrThrow(COL_MIN_DIST)
            val idxRssi = cursor.getColumnIndexOrThrow(COL_MAX_RSSI)
            val idxTier = cursor.getColumnIndexOrThrow(COL_TIER)

            while (cursor.moveToNext()) {
                results.add(
                    SessionSnapshotRecord(
                        id = cursor.getLong(idxId),
                        timestampEpochMs = cursor.getLong(idxTs),
                        targetMac = cursor.getString(idxMac),
                        targetName = cursor.getString(idxName),
                        estimatedX = cursor.getDouble(idxX),
                        estimatedY = cursor.getDouble(idxY),
                        minDistanceMeters = cursor.getDouble(idxDist),
                        maxRssiDbm = cursor.getInt(idxRssi),
                        localizationTier = cursor.getString(idxTier)
                    )
                )
            }
        }
        return results
    }

    fun deleteSnapshot(id: Long): Int {
        return writableDatabase.delete(TABLE_SNAPSHOTS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun clearAll(): Int {
        return writableDatabase.delete(TABLE_SNAPSHOTS, null, null)
    }

    fun insertWatchlistEntry(entry: WatchlistEntry): Long {
        val values = ContentValues().apply {
            put(COL_WL_TYPE, entry.matchType.name)
            put(COL_WL_PATTERN, entry.pattern.trim())
            put(COL_WL_LABEL, entry.label.trim())
            put(COL_WL_CREATED, entry.createdAtMs)
        }
        return writableDatabase.insert(TABLE_WATCHLIST, null, values)
    }

    fun queryWatchlistEntries(): List<WatchlistEntry> {
        val results = mutableListOf<WatchlistEntry>()
        readableDatabase.query(
            TABLE_WATCHLIST,
            null,
            null,
            null,
            null,
            null,
            "$COL_WL_CREATED DESC"
        ).use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(COL_ID)
            val idxType = cursor.getColumnIndexOrThrow(COL_WL_TYPE)
            val idxPattern = cursor.getColumnIndexOrThrow(COL_WL_PATTERN)
            val idxLabel = cursor.getColumnIndexOrThrow(COL_WL_LABEL)
            val idxCreated = cursor.getColumnIndexOrThrow(COL_WL_CREATED)
            while (cursor.moveToNext()) {
                val matchType = runCatching {
                    WatchlistMatchType.valueOf(cursor.getString(idxType))
                }.getOrDefault(WatchlistMatchType.SUBSTRING)
                results.add(
                    WatchlistEntry(
                        id = cursor.getLong(idxId),
                        matchType = matchType,
                        pattern = cursor.getString(idxPattern),
                        label = cursor.getString(idxLabel),
                        createdAtMs = cursor.getLong(idxCreated)
                    )
                )
            }
        }
        return results
    }

    fun deleteWatchlistEntry(id: Long): Int {
        return writableDatabase.delete(TABLE_WATCHLIST, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun insertPerimeterEvent(event: PerimeterEventRecord): Long {
        val values = ContentValues().apply {
            put(COL_TIMESTAMP, event.timestampEpochMs)
            put(COL_PM_KEY, event.emitterKey)
            put(COL_PM_NAME, event.emitterName)
            put(COL_PM_EVENT, event.eventType)
            put(COL_PM_DIST, event.distanceMeters)
            put(COL_PM_RSSI, event.rssiDbm)
        }
        return writableDatabase.insert(TABLE_PERIMETER, null, values)
    }

    fun queryPerimeterEvents(): List<PerimeterEventRecord> {
        val results = mutableListOf<PerimeterEventRecord>()
        readableDatabase.query(
            TABLE_PERIMETER,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            "100"
        ).use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(COL_ID)
            val idxTs = cursor.getColumnIndexOrThrow(COL_TIMESTAMP)
            val idxKey = cursor.getColumnIndexOrThrow(COL_PM_KEY)
            val idxName = cursor.getColumnIndexOrThrow(COL_PM_NAME)
            val idxEvent = cursor.getColumnIndexOrThrow(COL_PM_EVENT)
            val idxDist = cursor.getColumnIndexOrThrow(COL_PM_DIST)
            val idxRssi = cursor.getColumnIndexOrThrow(COL_PM_RSSI)
            while (cursor.moveToNext()) {
                results.add(
                    PerimeterEventRecord(
                        id = cursor.getLong(idxId),
                        timestampEpochMs = cursor.getLong(idxTs),
                        emitterKey = cursor.getString(idxKey),
                        emitterName = cursor.getString(idxName),
                        eventType = cursor.getString(idxEvent),
                        distanceMeters = cursor.getDouble(idxDist),
                        rssiDbm = cursor.getInt(idxRssi)
                    )
                )
            }
        }
        return results
    }

    fun clearPerimeterEvents(): Int {
        return writableDatabase.delete(TABLE_PERIMETER, null, null)
    }
}
