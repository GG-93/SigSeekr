package com.example.sensors

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.example.dsp.EmitterTransport
import com.example.dsp.RfMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.nio.charset.StandardCharsets

/**
 * Strictly isolated, optional USB serial bridge for external ESP32 / SDR telemetry.
 *
 * INVARIANT: Fails silently with an empty Flow if no compatible USB device is
 * attached or USB permission has not been granted. Never blocks or crashes
 * standalone phone operation.
 */
class Esp32UsbBridge(
    private val context: Context
) : RfDataSource {

    companion object {
        // Common USB-UART bridge Vendor IDs (Silicon Labs CP210x, WCH CH340, Espressif CDC, FTDI)
        private val SUPPORTED_VENDOR_IDS = setOf(0x10C4, 0x1A86, 0x303A, 0x0403)
    }

    private val usbManager: UsbManager? =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    override val sourceName: String = "Optional ESP32 USB Serial Bridge"

    override val isAvailable: Boolean
        get() = findCompatibleDevice() != null

    private fun findCompatibleDevice(): UsbDevice? {
        val manager = usbManager ?: return null
        return try {
            manager.deviceList.values.firstOrNull { device ->
                device.vendorId in SUPPORTED_VENDOR_IDS && manager.hasPermission(device)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads optional newline-delimited CSV telemetry (`MAC,NAME,RSSI[,RTT_M]`)
     * from an attached USB bulk IN endpoint if present and permitted; otherwise
     * returns an immediately completing empty flow with zero side effects.
     */
    override fun measurementsFlow(): Flow<RfMeasurement> {
        val manager = usbManager ?: return emptyFlow()
        val device = findCompatibleDevice() ?: return emptyFlow()

        return flow {
            val connection = try {
                manager.openDevice(device)
            } catch (_: Exception) {
                null
            } ?: return@flow

            var claimedInterface: android.hardware.usb.UsbInterface? = null
            try {
                val usbInterface = (0 until device.interfaceCount)
                    .map { device.getInterface(it) }
                    .firstOrNull { iface ->
                        (0 until iface.endpointCount).any { epIdx ->
                            val ep = iface.getEndpoint(epIdx)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                ep.direction == UsbConstants.USB_DIR_IN
                        }
                    } ?: return@flow

                if (!connection.claimInterface(usbInterface, true)) return@flow
                claimedInterface = usbInterface
                val bulkIn = (0 until usbInterface.endpointCount)
                    .map { usbInterface.getEndpoint(it) }
                    .first { ep ->
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_IN
                    }

                val buffer = ByteArray(256)
                val lineBuilder = StringBuilder()

                while (currentCoroutineContext().isActive) {
                    val bytesRead = connection.bulkTransfer(bulkIn, buffer, buffer.size, 250)
                    if (bytesRead < 0) continue
                    if (bytesRead > 0) {
                        val chunk = String(buffer, 0, bytesRead, StandardCharsets.US_ASCII)
                        for (ch in chunk) {
                            if (ch == '\n' || ch == '\r') {
                                if (lineBuilder.isNotEmpty()) {
                                    parseTelemetryLine(lineBuilder.toString())?.let { emit(it) }
                                    lineBuilder.clear()
                                }
                            } else if (lineBuilder.length < 256) {
                                lineBuilder.append(ch)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Fail silently on disconnect or I/O error
            } finally {
                try {
                    claimedInterface?.let { connection.releaseInterface(it) }
                    connection.close()
                } catch (_: Exception) {
                    // Ignore close error
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun parseTelemetryLine(line: String): RfMeasurement? {
        val parts = line.trim().split(',')
        if (parts.size < 3) return null
        val mac = parts[0].trim().uppercase()
        if (mac.length < 11) return null
        val name = parts[1].trim().ifBlank { "USB Emitter" }
        val rssi = parts[2].trim().toIntOrNull() ?: return null
        val rttMeters = parts.getOrNull(3)?.trim()?.toDoubleOrNull()?.takeIf { it >= 0.0 }
        return RfMeasurement(
            macAddress = mac,
            displayName = name,
            transport = EmitterTransport.EXTERNAL_USB,
            rssiDbm = rssi,
            isRttResponder = rttMeters != null,
            rttDistanceMeters = rttMeters,
            rttStdDevMeters = if (rttMeters != null) 0.8 else null,
            timestampElapsedMs = SystemClock.elapsedRealtime(),
            scanResult = null
        )
    }
}
