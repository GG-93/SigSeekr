package com.example.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.dsp.BleAdDecoder
import com.example.dsp.DeviceFingerprintEngine
import com.example.dsp.EmitterTransport
import com.example.dsp.RfMeasurement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Compatibility helper matching IEEE 802.11mc (API 23+) and 802.11az NTB (API 35+)
 * Wi-Fi RTT responder flags on Android ScanResult.
 */
val ScanResult.isWifiRttResponder: Boolean
    get() {
        val supports11mc = is80211mcResponder
        val supports11az = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            is80211azNtbResponder
        } else {
            false
        }
        return supports11mc || supports11az
    }

/**
 * 100% standalone RF telemetry source powered strictly by the phone's internal
 * Wi-Fi radio, WifiRttManager (API 28+), and BluetoothLeScanner.
 * Enriches every packet with verbose [BleAdDecoder] parsing and
 * [DeviceFingerprintEngine] MAC-randomization fingerprinting.
 */
class StandaloneRadioDataSource(
    private val context: Context
) : RfDataSource {

    private val appContext = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val wifiRttManager: WifiRttManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        ) {
            appContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        } else {
            null
        }

    private val rttExecutor = Executors.newSingleThreadExecutor()
    private val bleScanLock = Any()
    private val bleSharedFlow = MutableSharedFlow<RfMeasurement>(extraBufferCapacity = 256)

    @Volatile
    private var persistentBleScanner: android.bluetooth.le.BluetoothLeScanner? = null

    @Volatile
    private var nextBleRetryAllowedMs: Long = 0L

    private val persistentBleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) {
            emitBleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<BleScanResult>) {
            for (result in results) {
                emitBleResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("SigSeekrBLE", "BLE ScanCallback.onScanFailed(errorCode=$errorCode)")
            synchronized(bleScanLock) {
                persistentBleScanner = null
                val backoffMs = if (errorCode == 6) 30_000L else 3_000L // 6 = SCAN_FAILED_SCANNING_TOO_FREQUENTLY
                nextBleRetryAllowedMs = SystemClock.elapsedRealtime() + backoffMs
            }
        }

        private fun emitBleResult(result: BleScanResult) {
            val mac = result.device?.address?.uppercase() ?: return
            val rawBytes = result.scanRecord?.bytes
            val decoded = BleAdDecoder.parse(rawBytes)
            val addressType = readBleAddressTypeSafely(result)
            val fpHash = DeviceFingerprintEngine.fingerprintBle(mac, addressType, decoded)

            val advertisedName = decoded.localName
                ?: result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            val deviceName = advertisedName
                ?: readBleDeviceNameSafely(result)
                ?: decoded.trackerTag?.displayLabel
                ?: decoded.companyName
                ?: "BLE Emitter"

            bleSharedFlow.tryEmit(
                RfMeasurement(
                    macAddress = mac,
                    displayName = deviceName,
                    transport = EmitterTransport.BLE,
                    rssiDbm = result.rssi,
                    isRttResponder = false,
                    rttDistanceMeters = null,
                    rttStdDevMeters = null,
                    timestampElapsedMs = SystemClock.elapsedRealtime(),
                    scanResult = null,
                    fingerprintHash = fpHash,
                    bleAddressType = addressType,
                    bleDecoded = decoded
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensurePersistentBleScanStarted() {
        val now = SystemClock.elapsedRealtime()
        synchronized(bleScanLock) {
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                persistentBleScanner = null
                return
            }
            if (persistentBleScanner != null) return
            if (now < nextBleRetryAllowedMs) return
            if (!hasBleScanPermission()) return

            val scanner = try {
                adapter.bluetoothLeScanner
            } catch (_: SecurityException) {
                null
            } ?: return

            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .setReportDelay(0L)
                    .build()
                scanner.startScan(emptyList(), settings, persistentBleCallback)
                persistentBleScanner = scanner
                Log.i("SigSeekrBLE", "Persistent unfiltered BLE Scan started (LOW_LATENCY)")
            } catch (e: SecurityException) {
                Log.w("SigSeekrBLE", "BLE startScan SecurityException: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w("SigSeekrBLE", "BLE startScan IllegalStateException: ${e.message}")
            }
        }
    }

    override val sourceName: String = "Standalone Internal Radios"

    override val isAvailable: Boolean
        get() = wifiManager != null || bluetoothManager?.adapter != null

    val hasWifiRttCapability: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            wifiRttManager != null &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)

    val hasBleCapability: Boolean
        get() = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            bluetoothManager?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override fun triggerWifiScan(): Boolean {
        val wm = wifiManager ?: return false
        if (!hasWifiScanPermission()) return false
        return try {
            @Suppress("DEPRECATION")
            wm.startScan()
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun measurementsFlow(): Flow<RfMeasurement> = callbackFlow {
        val scope = CoroutineScope(coroutineContext + Dispatchers.Default)

        // 1. Collect from single persistent BLE scanner session (never stops/restarts on flow re-collection)
        val bleForwardJob = scope.launch {
            bleSharedFlow.collect { measurement ->
                trySend(measurement)
            }
        }
        ensurePersistentBleScanStarted()

        // 2. Wi-Fi ScanResult BroadcastReceiver + periodic trigger
        val emitLatestWifiResults: () -> Unit = {
            val wm = wifiManager
            if (wm != null && hasWifiScanPermission()) {
                val results = try {
                    wm.scanResults
                } catch (_: SecurityException) {
                    emptyList()
                }
                val nowElapsed = SystemClock.elapsedRealtime()
                for (sr in results) {
                    val bssid = sr.BSSID?.uppercase() ?: continue
                    if (bssid.isBlank() || bssid == "00:00:00:00:00:00") continue
                    val ssid = extractSsid(sr)
                    val ieMeta = extractWifiIeMetadata(sr)
                    trySend(
                        RfMeasurement(
                            macAddress = bssid,
                            displayName = ssid,
                            transport = EmitterTransport.WIFI,
                            rssiDbm = sr.level,
                            isRttResponder = sr.isWifiRttResponder,
                            rttDistanceMeters = null,
                            rttStdDevMeters = null,
                            timestampElapsedMs = nowElapsed,
                            scanResult = sr,
                            fingerprintHash = DeviceFingerprintEngine.fingerprintWifi(bssid),
                            bleAddressType = null,
                            bleDecoded = null,
                            wifiCapabilities = sr.capabilities,
                            wifiVendorOuiHexes = ieMeta.vendorOuiHexes,
                            frequencyMhz = sr.frequency,
                            channelNumber = frequencyToChannel(sr.frequency),
                            channelWidthMhz = channelWidthToMhz(sr.channelWidth),
                            wifiStandardLabel = extractWifiStandardLabel(sr),
                            wifiIeTags = ieMeta.ieTags,
                            wifiVendorOuis = ieMeta.vendorOuis,
                            wpsDeviceInfo = ieMeta.wpsInfo
                        )
                    )
                }
            }
        }

        val wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    emitLatestWifiResults()
                }
            }
        }

        val receiverRegistered = try {
            ContextCompat.registerReceiver(
                appContext,
                wifiReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            true
        } catch (_: Exception) {
            false
        }

        emitLatestWifiResults()
        triggerWifiScan()

        val wifiPollJob = scope.launch {
            while (isActive) {
                delay(3_500L)
                ensurePersistentBleScanStarted()
                triggerWifiScan()
                emitLatestWifiResults()
            }
        }

        awaitClose {
            bleForwardJob.cancel()
            wifiPollJob.cancel()
            if (receiverRegistered) {
                try {
                    appContext.unregisterReceiver(wifiReceiver)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered
                }
            }
        }
    }

    /**
     * Tier 1 Hardware Ranging via WifiRttManager (IEEE 802.11mc / 802.11az).
     * Returns a populated [RfMeasurement] with `rttDistanceMeters = distanceMm / 1000.0`,
     * or null if hardware RTT is unavailable or ranging failed.
     */
    @SuppressLint("MissingPermission")
    override suspend fun requestRttRanging(scanResult: ScanResult): RfMeasurement? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val rtt = wifiRttManager ?: return null
        if (!rtt.isAvailable || !hasRttPermission()) return null
        if (!scanResult.isWifiRttResponder) return null

        val request = try {
            RangingRequest.Builder()
                .addAccessPoint(scanResult)
                .build()
        } catch (_: IllegalArgumentException) {
            return null
        }

        return withTimeoutOrNull(1_500L) {
            suspendCancellableCoroutine { cont ->
                try {
                    rtt.startRanging(
                        request,
                        rttExecutor,
                        object : RangingResultCallback() {
                            override fun onRangingFailure(code: Int) {
                                if (cont.isActive) cont.resume(null)
                            }

                            override fun onRangingResults(results: List<RangingResult>) {
                                if (!cont.isActive) return
                                val success = results.firstOrNull {
                                    it.status == RangingResult.STATUS_SUCCESS
                                }
                                if (success == null) {
                                    cont.resume(null)
                                    return
                                }
                                val distanceMeters = (success.distanceMm / 1000.0).coerceAtLeast(0.0)
                                val stdDevMeters = (success.distanceStdDevMm / 1000.0).coerceAtLeast(0.1)
                                val mac = success.macAddress?.toString()?.uppercase()
                                    ?: scanResult.BSSID?.uppercase()
                                    ?: ""
                                cont.resume(
                                    RfMeasurement(
                                        macAddress = mac,
                                        displayName = extractSsid(scanResult),
                                        transport = EmitterTransport.WIFI,
                                        rssiDbm = success.rssi,
                                        isRttResponder = true,
                                        rttDistanceMeters = distanceMeters,
                                        rttStdDevMeters = stdDevMeters,
                                        timestampElapsedMs = SystemClock.elapsedRealtime(),
                                        scanResult = scanResult,
                                        fingerprintHash = DeviceFingerprintEngine.fingerprintWifi(mac)
                                    )
                                )
                            }
                        }
                    )
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBleAddressTypeSafely(result: BleScanResult): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return try {
            result.device?.addressType
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBleDeviceNameSafely(result: BleScanResult): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return null
        }
        return try {
            result.device?.name?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun extractSsid(sr: ScanResult): String {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sr.wifiSsid?.toString()
        } else {
            @Suppress("DEPRECATION")
            sr.SSID
        }
        val cleaned = raw?.trim()?.trim('"').orEmpty()
        return cleaned.ifBlank { "Hidden AP" }
    }

    private data class WifiIeMetadata(
        val ieTags: List<Int>,
        val vendorOuiHexes: List<String>,
        val vendorOuis: List<Pair<String, String>>,
        val wpsInfo: String?
    )

    private fun extractWifiIeMetadata(scanResult: ScanResult): WifiIeMetadata {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return WifiIeMetadata(emptyList(), emptyList(), emptyList(), null)
        }
        return try {
            val ies = scanResult.informationElements
            val tags = ArrayList<Int>(ies.size)
            val ouiHexes = mutableListOf<String>()
            val ouiPairs = mutableListOf<Pair<String, String>>()
            var wpsInfo: String? = null

            for (ie in ies) {
                tags.add(ie.id)
                if (ie.id == 221) { // Vendor Specific IE
                    val buf = ie.bytes
                    if (buf.remaining() >= 3) {
                        val b0 = buf.get().toInt() and 0xFF
                        val b1 = buf.get().toInt() and 0xFF
                        val b2 = buf.get().toInt() and 0xFF
                        val oui = String.format(java.util.Locale.US, "%02X:%02X:%02X", b0, b1, b2)
                        if (oui == "FA:0B:BC" && buf.remaining() >= 1) {
                            val subType = buf.get().toInt() and 0xFF
                            if (subType == 0x0D) {
                                ouiHexes.add("FA:0B:BC:0D")
                            }
                        } else {
                            ouiHexes.add(oui)
                        }
                        val vendorName = lookupVendorOuiName(oui)
                        if (ouiPairs.none { it.first == oui }) {
                            ouiPairs.add(oui to vendorName)
                        }
                        if (oui == "00:50:F2" && buf.remaining() >= 1) {
                            val subtype = buf.get().toInt() and 0xFF
                            if (subtype == 0x04) {
                                wpsInfo = "WPS Broadcast (00:50:F2:04)"
                            }
                        }
                    }
                }
            }
            WifiIeMetadata(tags, ouiHexes, ouiPairs, wpsInfo)
        } catch (_: Exception) {
            WifiIeMetadata(emptyList(), emptyList(), emptyList(), null)
        }
    }

    private fun lookupVendorOuiName(oui: String): String = when (oui) {
        "00:50:F2" -> "Microsoft / WFA"
        "00:90:4C", "00:10:18" -> "Broadcom"
        "8C:FD:F0", "00:03:7F" -> "Qualcomm / Atheros"
        "00:0C:43" -> "MediaTek / Ralink"
        "50:6F:9A" -> "Wi-Fi Alliance (WFA)"
        "FA:0B:BC" -> "ASTM OpenDroneID"
        "26:37:12" -> "DJI DroneID"
        "00:17:F2", "00:1B:63" -> "Apple"
        else -> "Vendor ($oui)"
    }

    private fun frequencyToChannel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5170..5825 -> (freqMhz - 5000) / 5
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        else -> 0
    }

    private fun channelWidthToMhz(widthConst: Int): Int = when (widthConst) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ -> 160
        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
        else -> 20
    }

    private fun extractWifiStandardLabel(sr: ScanResult): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "802.11"
        return when (sr.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "802.11n (Wi-Fi 4)"
            ScanResult.WIFI_STANDARD_11AC -> "802.11ac (Wi-Fi 5)"
            ScanResult.WIFI_STANDARD_11AX -> "802.11ax (Wi-Fi 6)"
            ScanResult.WIFI_STANDARD_11BE -> "802.11be (Wi-Fi 7)"
            else -> "802.11"
        }
    }

    private fun hasBleScanPermission(): Boolean {
        val fineLoc = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btScan = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            return btScan && fineLoc
        }
        return fineLoc
    }

    private fun hasWifiScanPermission(): Boolean {
        val fineLoc = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val wifiState = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED
        return fineLoc && wifiState
    }

    private fun hasRttPermission(): Boolean {
        val fineLoc = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearbyWifi = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            return fineLoc && nearbyWifi
        }
        return fineLoc
    }

    @SuppressLint("MissingPermission")
    fun shutdown() {
        synchronized(bleScanLock) {
            val scanner = persistentBleScanner
            persistentBleScanner = null
            if (scanner != null && hasBleScanPermission()) {
                try {
                    scanner.stopScan(persistentBleCallback)
                } catch (_: Exception) {
                }
            }
        }
        rttExecutor.shutdownNow()
    }
}
