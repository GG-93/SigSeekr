package com.example.dsp

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Generates deterministic `fingerprintHash` keys to collapse MAC-randomized BLE
 * devices without falsely merging distinct hardware or identical Wi-Fi AP models.
 *
 * Rules:
 * 1. Wi-Fi (`ScanResult`): Always keyed by exact uppercase BSSID (`WIFI:<BSSID>`).
 * 2. BLE Public / Static MACs: Keyed directly by MAC (`BLE_MAC:<MAC>`).
 * 3. BLE Randomized MACs (RPA / NRPA): SHA-256 signature over
 *    `[Company ID + ordered AD Type sequence + sorted Service UUIDs +
 *      static Manufacturer header bytes + Advertised TX Power 0x0A]`,
 *    strictly excluding rolling/encrypted payload bytes.
 */
object DeviceFingerprintEngine {

    // Matches android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC / ADDRESS_TYPE_RANDOM
    const val ADDRESS_TYPE_PUBLIC = 0
    const val ADDRESS_TYPE_RANDOM = 1

    fun fingerprintWifi(bssid: String): String {
        return "WIFI:${bssid.trim().uppercase(Locale.US)}"
    }

    fun fingerprintBle(
        macAddress: String,
        addressType: Int?,
        decoded: BleDecodedPacket
    ): String {
        val cleanMac = macAddress.trim().uppercase(Locale.US)
        val hasRandomizedMac = isRandomizedBleMac(cleanMac, addressType)
        if (!hasRandomizedMac) {
            return "BLE_MAC:$cleanMac"
        }

        // Prevent barebones generic BLE beacons from collapsing together:
        // Require non-trivial payload entropy (>= 6 payload bytes beyond AD flags 0x01).
        val nonFlagsAdCount = decoded.adTypes.count { it != 0x01 }
        val rawByteCount = decoded.rawHexDump.replace(" ", "").length / 2
        val approxNonFlagsBytes = if (0x01 in decoded.adTypes) (rawByteCount - 3).coerceAtLeast(0) else rawByteCount
        val hasHighEntropyPayload = nonFlagsAdCount > 0 && approxNonFlagsBytes >= 6 && (
            decoded.staticManufacturerHeaderHex != null ||
                decoded.serviceUuids.isNotEmpty() ||
                !decoded.localName.isNullOrBlank()
            )

        if (!hasHighEntropyPayload) {
            return "BLE_MAC:$cleanMac"
        }

        val canonical = buildString {
            append("CID=").append(decoded.companyId?.let { String.format(Locale.US, "%04X", it) } ?: "NONE")
            append("|ADT=").append(decoded.adTypes.joinToString(",") { String.format(Locale.US, "%02X", it) })
            append("|UUID=").append(decoded.serviceUuids.sorted().joinToString(","))
            append("|HDR=").append(decoded.staticManufacturerHeaderHex ?: "NONE")
            append("|TXP=").append(decoded.advertisedTxPowerDbm?.toString() ?: "NONE")
            // Preserve non-rotating advertised device name when present alongside randomized MAC
            if (!decoded.localName.isNullOrBlank()) {
                append("|NAME=").append(decoded.localName.trim())
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hexPrefix = buildString(12) {
            for (i in 0 until 6) {
                append(String.format(Locale.US, "%02X", digest[i].toInt() and 0xFF))
            }
        }
        return "BLE_FP:$hexPrefix"
    }

    /**
     * Determines whether a BLE MAC address is a rotating Random Private Address
     * (Resolvable Private Address `01xxxxxx` or Non-Resolvable Private Address `00xxxxxx`).
     * Public MACs (`ADDRESS_TYPE_PUBLIC`) and Static Random addresses (`11xxxxxx`) return false.
     */
    fun isRandomizedBleMac(macAddress: String, addressType: Int?): Boolean {
        if (addressType == ADDRESS_TYPE_PUBLIC) return false
        val firstByteHex = macAddress.substringBefore(':').trim()
        val msb = firstByteHex.toIntOrNull(16) ?: return false
        val topTwoBits = msb and 0xC0

        // Bluetooth Core Spec Vol 6 Part B §1.3.2:
        // 0xC0 (11xxxxxx) = Static Device Address (fixed per power cycle -> keep MAC key)
        // 0x40 (01xxxxxx) = Resolvable Private Address (rotates periodically -> fingerprint!)
        // 0x00 (00xxxxxx) = Non-Resolvable Private Address (rotates -> fingerprint!)
        if (addressType == ADDRESS_TYPE_RANDOM) {
            return topTwoBits == 0x40 || topTwoBits == 0x00
        }

        // When addressType is unavailable (API < 35): check locally administered bit (bit 1)
        // combined with RPA (01) / NRPA (00) classification.
        val isLocallyAdministered = (msb and 0x02) != 0
        return isLocallyAdministered && (topTwoBits == 0x40 || topTwoBits == 0x00)
    }

    /** Formats any internal fingerprintHash or MAC into a compact `FP-XXXXXXXX` SIGINT pill ID. */
    fun displayFingerprintId(fingerprintHash: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fingerprintHash.toByteArray(StandardCharsets.UTF_8))
        return buildString(11) {
            append("FP-")
            for (i in 0 until 4) {
                append(String.format(Locale.US, "%02X", digest[i].toInt() and 0xFF))
            }
        }
    }
}
