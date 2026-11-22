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

        // 1. Continuous low-latency BLE advertisement scanner (with lazy retry if BT toggled on later)
        var activeBleScanner: android.bluetooth.le.BluetoothLeScanner? = null

        val bleCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: BleScanResult) {
                emitBleResult(result)
            }

            override fun onBatchScanResults(results: MutableList<BleScanResult>) {
                for (result in results) {
                    emitBleResult(result)
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

                trySend(
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

        val ensureBleScanStarted: () -> Unit = {
            if (activeBleScanner == null && hasBleScanPermission()) {
                val scanner = try {
                    val adapter = bluetoothManager?.adapter
                    if (adapter != null && adapter.isEnabled) adapter.bluetoothLeScanner else null
                } catch (_: SecurityException) {
                    null
                }
                if (scanner != null) {
                    try {
                        val settings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                            .setReportDelay(0L)
                            .build()
                        scanner.startScan(null, settings, bleCallback)
                        activeBleScanner = scanner
                    } catch (_: SecurityException) {
                        // Gracefully degrade if user revokes BLE permission mid-session
                    } catch (_: IllegalStateException) {
                        // Bluetooth radio toggled off
                    }
                }
            } else if (activeBleScanner != null && bluetoothManager?.adapter?.isEnabled != true) {
                activeBleScanner = null
            }
        }

        ensureBleScanStarted()

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
                            bleDecoded = null
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
                ensureBleScanStarted()
                triggerWifiScan()
                emitLatestWifiResults()
            }
        }

        awaitClose {
            wifiPollJob.cancel()
            if (receiverRegistered) {
                try {
                    appContext.unregisterReceiver(wifiReceiver)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered
                }
            }
            val scannerToStop = activeBleScanner
            activeBleScanner = null
            if (scannerToStop != null && hasBleScanPermission()) {
                try {
                    scannerToStop.stopScan(bleCallback)
                } catch (_: Exception) {
                    // Bluetooth radio may have turned off
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

    fun shutdown() {
        rttExecutor.shutdownNow()
    }
}
