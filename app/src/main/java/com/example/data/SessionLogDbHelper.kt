package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.dsp.SessionSnapshotRecord

/**
 * Pure Android SQLiteOpenHelper storage for offline scan session snapshots.
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
        private const val DATABASE_VERSION = 1

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
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SNAPSHOTS (
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SNAPSHOTS")
        onCreate(db)
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
}
