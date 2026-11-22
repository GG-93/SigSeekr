package com.example.data

import com.example.dsp.PerimeterEventRecord
import com.example.dsp.WatchlistEntry
import com.example.dsp.WatchlistMatchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Pure Kotlin matching + SQLite persistence for Custom Watchlist entries
 * (OUI prefixes, SSID/Name substrings, and MAC-rotation-resilient Fingerprints)
 * and Perimeter Guard crossing events.
 */
class WatchlistRepository(private val dbHelper: SessionLogDbHelper? = null) {

    companion object {
        fun matchEntries(
            entries: List<WatchlistEntry>,
            macAddress: String,
            displayName: String,
            fingerprintHash: String
        ): WatchlistEntry? {
            if (entries.isEmpty()) return null
            val cleanMac = macAddress.trim().uppercase(Locale.US)
            val cleanName = displayName.trim().lowercase(Locale.US)
            val cleanFp = fingerprintHash.trim().uppercase(Locale.US)

            for (entry in entries) {
                val pattern = entry.pattern.trim()
                if (pattern.isEmpty()) continue
                when (entry.matchType) {
                    WatchlistMatchType.OUI -> {
                        val normPattern = pattern.uppercase(Locale.US)
                        if (cleanMac.startsWith(normPattern)) return entry
                    }
                    WatchlistMatchType.SUBSTRING -> {
                        if (cleanName.contains(pattern.lowercase(Locale.US))) return entry
                    }
                    WatchlistMatchType.FINGERPRINT -> {
                        if (cleanFp == pattern.uppercase(Locale.US)) return entry
                    }
                }
            }
            return null
        }
    }

    suspend fun getAllWatchlist(): List<WatchlistEntry> = withContext(Dispatchers.IO) {
        dbHelper?.queryWatchlistEntries() ?: emptyList()
    }

    suspend fun addWatchlistEntry(entry: WatchlistEntry): List<WatchlistEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper ?: return@withContext emptyList()
        val existing = db.queryWatchlistEntries().any {
            it.matchType == entry.matchType && it.pattern.equals(entry.pattern.trim(), ignoreCase = true)
        }
        if (!existing && entry.pattern.isNotBlank()) {
            db.insertWatchlistEntry(entry)
        }
        db.queryWatchlistEntries()
    }

    suspend fun deleteWatchlistEntry(id: Long): List<WatchlistEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper ?: return@withContext emptyList()
        db.deleteWatchlistEntry(id)
        db.queryWatchlistEntries()
    }

    suspend fun getPerimeterEvents(): List<PerimeterEventRecord> = withContext(Dispatchers.IO) {
        dbHelper?.queryPerimeterEvents() ?: emptyList()
    }

    suspend fun addPerimeterEvent(event: PerimeterEventRecord): List<PerimeterEventRecord> = withContext(Dispatchers.IO) {
        val db = dbHelper ?: return@withContext emptyList()
        db.insertPerimeterEvent(event)
        db.queryPerimeterEvents()
    }

    suspend fun clearPerimeterEvents(): List<PerimeterEventRecord> = withContext(Dispatchers.IO) {
        val db = dbHelper ?: return@withContext emptyList()
        db.clearPerimeterEvents()
        emptyList()
    }
}
