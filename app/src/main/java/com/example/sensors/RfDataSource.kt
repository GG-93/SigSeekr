package com.example.sensors

import android.net.wifi.ScanResult
import com.example.dsp.RfMeasurement
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for RF telemetry sources.
 *
 * SigSeeker operates 100% standalone via [StandaloneRadioDataSource].
 * Optional external hardware adapters (such as [Esp32UsbBridge]) implement this
 * interface and fail silently when no external hardware is attached.
 */
interface RfDataSource {
    val sourceName: String
    val isAvailable: Boolean

    fun measurementsFlow(): Flow<RfMeasurement>

    /** Triggers a one-shot IEEE 802.11mc/az RTT ranging measurement if supported. */
    suspend fun requestRttRanging(scanResult: ScanResult): RfMeasurement? = null

    /** Requests an immediate Wi-Fi scan refresh if supported by the source. */
    fun triggerWifiScan(): Boolean = false
}
