package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SessionLogDbHelper
import com.example.data.SessionLogRepository
import com.example.dsp.SessionSnapshotRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun readAppNameFromResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SigSeeker", appName)
    }

    @Test
    fun sqliteSessionLog_persistsAndExportsJsonAndCsv() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = SessionLogDbHelper(context)
        dbHelper.clearAll()

        val record = SessionSnapshotRecord(
            timestampEpochMs = 1_760_000_000_000L,
            targetMac = "AA:BB:CC:DD:EE:FF",
            targetName = "Lab-AP-6E",
            estimatedX = 31.25,
            estimatedY = 28.75,
            minDistanceMeters = 1.42,
            maxRssiDbm = -48,
            localizationTier = "TIER 1 · RTT"
        )

        val id = dbHelper.insertSnapshot(record)
        assertTrue(id > 0L)

        val loaded = dbHelper.queryAllSnapshots()
        assertEquals(1, loaded.size)
        assertEquals("AA:BB:CC:DD:EE:FF", loaded[0].targetMac)
        assertEquals("Lab-AP-6E", loaded[0].targetName)
        assertEquals(-48, loaded[0].maxRssiDbm)

        val json = SessionLogRepository.formatAsJson(loaded)
        assertTrue(json.contains("\"target_mac\": \"AA:BB:CC:DD:EE:FF\""))
        assertTrue(json.contains("\"app\": \"SigSeeker\""))

        val csv = SessionLogRepository.formatAsCsv(loaded)
        assertTrue(csv.contains("target_mac,target_name"))
        assertTrue(csv.contains("AA:BB:CC:DD:EE:FF,Lab-AP-6E"))

        dbHelper.deleteSnapshot(id)
        assertEquals(0, dbHelper.queryAllSnapshots().size)
        dbHelper.close()
    }
}
