package com.example.dsp

import java.nio.charset.StandardCharsets
import java.util.Locale

enum class BleTrackerTag(val displayLabel: String, val isPotentialUnwantedTracker: Boolean = false) {
    APPLE_FIND_MY_SEPARATED("Apple Find My (Separated Tracker)", isPotentialUnwantedTracker = true),
    APPLE_FIND_MY_NEARBY("Apple Find My (Owner Nearby)", isPotentialUnwantedTracker = false),
    APPLE_CONTINUITY("Apple Continuity"),
    APPLE_AIRPODS("Apple AirPods / Beats"),
    APPLE_IBEACON("Apple iBeacon"),
    SAMSUNG_SMARTTAG("Samsung SmartTag / SmartThings Find", isPotentialUnwantedTracker = true),
    TILE_TRACKER("Tile Tracker", isPotentialUnwantedTracker = true),
    GOOGLE_FIND_HUB("Google Find My Device", isPotentialUnwantedTracker = true),
    FAST_PAIR("Google Fast Pair"),
    EDDYSTONE("Eddystone Beacon")
}

data class BleDecodedField(
    val adTypeHex: String,
    val label: String,
    val value: String
)

data class BleDecodedPacket(
    val rawHexDump: String,
    val adTypes: List<Int>,
    val fields: List<BleDecodedField>,
    val flagsSummary: String?,
    val localName: String?,
    val advertisedTxPowerDbm: Int?,
    val serviceUuids: List<String>,
    val companyId: Int?,
    val companyName: String?,
    /**
     * Static manufacturer header prefix (excluding rolling encrypted payload bytes)
     * used by DeviceFingerprintEngine for randomized BLE MAC correlation.
     */
    val staticManufacturerHeaderHex: String?,
    val trackerTag: BleTrackerTag?,
    val summaryLine: String
) {
    companion object {
        val EMPTY = BleDecodedPacket(
            rawHexDump = "",
            adTypes = emptyList(),
            fields = emptyList(),
            flagsSummary = null,
            localName = null,
            advertisedTxPowerDbm = null,
            serviceUuids = emptyList(),
            companyId = null,
            companyName = null,
            staticManufacturerHeaderHex = null,
            trackerTag = null,
            summaryLine = "No BLE AD payload"
        )
    }
}

/**
 * 1D Scalar Kalman Filter for RSSI smoothing that strictly guarantees negative
 * dBm outputs in [-127.0, -1.0] so uninitialized or anomalous states can never
 * yield positive dBm values.
 */
class RssiKalmanFilter(
    private val processNoiseQ: Double = 1.2,
    private val measurementNoiseR: Double = 9.0
) {
    private var initialized = false
    private var estimateDbm: Double = -85.0
    private var errorCovarianceP: Double = 4.0

    fun update(rawRssiDbm: Double): Double {
        val sanitized = sanitizeNegativeDbm(rawRssiDbm)
        if (!initialized) {
            estimateDbm = sanitized
            errorCovarianceP = 4.0
            initialized = true
            return estimateDbm
        }
        // Predict
        val predictedP = errorCovarianceP + processNoiseQ
        // Update
        val kalmanGain = predictedP / (predictedP + measurementNoiseR)
        val updated = estimateDbm + kalmanGain * (sanitized - estimateDbm)
        estimateDbm = sanitizeNegativeDbm(updated)
        errorCovarianceP = ((1.0 - kalmanGain) * predictedP).coerceAtLeast(1e-4)
        return estimateDbm
    }

    fun currentEstimateDbm(): Double = sanitizeNegativeDbm(estimateDbm)

    fun reset() {
        initialized = false
        estimateDbm = -85.0
        errorCovarianceP = 4.0
    }

    companion object {
        fun sanitizeNegativeDbm(value: Double): Double {
            if (!value.isFinite()) return -100.0
            return value.coerceIn(-127.0, -1.0)
        }
    }
}

/**
 * Verbose plain-English BLE Advertising Data (AD) TLV decoder supporting:
 * - 0x01 Flags
 * - 0x08 / 0x09 Shortened & Complete Local Name
 * - 0x0A TX Power Level (signed Int8 dBm)
 * - 0x02/0x03/0x06/0x07/0x16 Bluetooth SIG Service UUIDs & Service Data
 * - 0xFF Manufacturer Specific Data (Apple Continuity & Find My Separated/Nearby,
 *   Samsung SmartTag, Tile, Microsoft, Google, Nordic, Espressif)
 * - Complete raw hex dump
 */
object BleAdDecoder {

    private const val COMPANY_APPLE = 0x004C
    private const val COMPANY_MICROSOFT = 0x0006
    private const val COMPANY_NORDIC = 0x0059
    private const val COMPANY_SAMSUNG = 0x0075
    private const val COMPANY_GOOGLE = 0x00E0
    private const val COMPANY_ESPRESSIF = 0x02E5
    private const val COMPANY_TILE = 0x067C

    private val SIG_UUID_NAMES = mapOf(
        "1800" to "Generic Access",
        "1801" to "Generic Attribute",
        "180A" to "Device Information",
        "180D" to "Heart Rate",
        "180F" to "Battery Service",
        "1812" to "Human Interface Device (HID)",
        "181C" to "User Data",
        "FD44" to "Apple Find My Accessory",
        "FD5A" to "Samsung SmartTag",
        "FD6F" to "Exposure Notification",
        "FE2C" to "Google Fast Pair",
        "FEAA" to "Eddystone Beacon",
        "FEAF" to "Google Find My Device",
        "FEED" to "Tile Tracker",
        "FEE7" to "Tencent / WeChat"
    )

    private val COMPANY_NAMES = mapOf(
        COMPANY_APPLE to "Apple, Inc.",
        COMPANY_MICROSOFT to "Microsoft",
        COMPANY_NORDIC to "Nordic Semiconductor",
        COMPANY_SAMSUNG to "Samsung Electronics",
        COMPANY_GOOGLE to "Google LLC",
        COMPANY_ESPRESSIF to "Espressif Systems",
        COMPANY_TILE to "Tile, Inc."
    )

    fun parse(rawBytes: ByteArray?): BleDecodedPacket {
        if (rawBytes == null || rawBytes.isEmpty()) return BleDecodedPacket.EMPTY

        val effectiveLength = trimTrailingPadding(rawBytes)
        if (effectiveLength == 0) return BleDecodedPacket.EMPTY

        val rawHexDump = bytesToHex(rawBytes, 0, effectiveLength)
        val adTypes = mutableListOf<Int>()
        val fields = mutableListOf<BleDecodedField>()
        val serviceUuids = mutableListOf<String>()

        var flagsSummary: String? = null
        var localName: String? = null
        var txPowerDbm: Int? = null
        var companyId: Int? = null
        var companyName: String? = null
        var staticMfgHeaderHex: String? = null
        var trackerTag: BleTrackerTag? = null

        var index = 0
        while (index < effectiveLength) {
            val length = rawBytes[index].toInt() and 0xFF
            if (length == 0) break
            val typeIndex = index + 1
            if (typeIndex >= effectiveLength) break
            val adType = rawBytes[typeIndex].toInt() and 0xFF
            val dataStart = index + 2
            val dataEnd = (index + 1 + length).coerceAtMost(effectiveLength)
            val dataLen = (dataEnd - dataStart).coerceAtLeast(0)
            val typeHex = String.format(Locale.US, "0x%02X", adType)

            adTypes.add(adType)

            when (adType) {
                0x01 -> {
                    if (dataLen >= 1) {
                        val flags = rawBytes[dataStart].toInt() and 0xFF
                        val desc = decodeFlags(flags)
                        flagsSummary = desc
                        fields.add(BleDecodedField(typeHex, "Flags", desc))
                    }
                }

                0x02, 0x03 -> {
                    val uuids = parse16BitUuids(rawBytes, dataStart, dataEnd)
                    serviceUuids.addAll(uuids)
                    val formatted = uuids.joinToString(", ") { uuid ->
                        val known = SIG_UUID_NAMES[uuid]
                        if (known != null) "0x$uuid ($known)" else "0x$uuid"
                    }
                    fields.add(
                        BleDecodedField(
                            typeHex,
                            if (adType == 0x03) "Complete 16-bit Service UUIDs" else "Incomplete 16-bit Service UUIDs",
                            formatted
                        )
                    )
                    if ("FEED" in uuids) trackerTag = BleTrackerTag.TILE_TRACKER
                    if ("FD5A" in uuids) trackerTag = BleTrackerTag.SAMSUNG_SMARTTAG
                    if ("FD44" in uuids && trackerTag == null) trackerTag = BleTrackerTag.APPLE_FIND_MY_SEPARATED
                    if ("FE2C" in uuids && trackerTag == null) trackerTag = BleTrackerTag.FAST_PAIR
                    if ("FEAA" in uuids && trackerTag == null) trackerTag = BleTrackerTag.EDDYSTONE
                    if ("FEAF" in uuids) trackerTag = BleTrackerTag.GOOGLE_FIND_HUB
                }

                0x06, 0x07 -> {
                    val uuids = parse128BitUuids(rawBytes, dataStart, dataEnd)
                    serviceUuids.addAll(uuids)
                    fields.add(
                        BleDecodedField(
                            typeHex,
                            if (adType == 0x07) "Complete 128-bit Service UUIDs" else "Incomplete 128-bit Service UUIDs",
                            uuids.joinToString(", ")
                        )
                    )
                }

                0x08, 0x09 -> {
                    val name = String(rawBytes, dataStart, dataLen, StandardCharsets.UTF_8)
                        .trim { it <= ' ' || it == '\u0000' }
                    if (name.isNotEmpty()) {
                        localName = name
                        fields.add(
                            BleDecodedField(
                                typeHex,
                                if (adType == 0x09) "Complete Local Name" else "Shortened Local Name",
                                name
                            )
                        )
                    }
                }

                0x0A -> {
                    if (dataLen >= 1) {
                        // Signed 8-bit integer TX Power Level (dBm)
                        val tx = rawBytes[dataStart].toInt()
                        txPowerDbm = tx
                        fields.add(
                            BleDecodedField(
                                typeHex,
                                "Advertised TX Power (0x0A)",
                                "$tx dBm (auto-calibrates P₀)"
                            )
                        )
                    }
                }

                0x16 -> {
                    if (dataLen >= 2) {
                        val uuid16 = String.format(
                            Locale.US,
                            "%02X%02X",
                            rawBytes[dataStart + 1].toInt() and 0xFF,
                            rawBytes[dataStart].toInt() and 0xFF
                        )
                        serviceUuids.add(uuid16)
                        val svcName = SIG_UUID_NAMES[uuid16]
                        val payloadHex = if (dataLen > 2) bytesToHex(rawBytes, dataStart + 2, dataEnd) else ""
                        val desc = when (uuid16) {
                            "FD5A" -> {
                                trackerTag = BleTrackerTag.SAMSUNG_SMARTTAG
                                "Samsung SmartTag Service Data (${dataLen - 2}B)"
                            }
                            "FEED" -> {
                                trackerTag = BleTrackerTag.TILE_TRACKER
                                "Tile Tracker Service Data (${dataLen - 2}B)"
                            }
                            "FEAA" -> {
                                if (trackerTag == null) trackerTag = BleTrackerTag.EDDYSTONE
                                "Eddystone Frame ($payloadHex)"
                            }
                            "FE2C" -> {
                                if (trackerTag == null) trackerTag = BleTrackerTag.FAST_PAIR
                                "Google Fast Pair Model ($payloadHex)"
                            }
                            "FEAF" -> {
                                trackerTag = BleTrackerTag.GOOGLE_FIND_HUB
                                "Google Find My Device Frame (${dataLen - 2}B)"
                            }
                            else -> {
                                val label = svcName ?: "0x$uuid16"
                                "$label: $payloadHex"
                            }
                        }
                        fields.add(BleDecodedField(typeHex, "Service Data (0x$uuid16)", desc))
                    }
                }

                0xFF -> {
                    if (dataLen >= 2) {
                        val cid = (rawBytes[dataStart].toInt() and 0xFF) or
                            ((rawBytes[dataStart + 1].toInt() and 0xFF) shl 8)
                        companyId = cid
                        val cName = COMPANY_NAMES[cid] ?: String.format(Locale.US, "Company 0x%04X", cid)
                        companyName = cName
                        val mfgStart = dataStart + 2
                        val mfgLen = (dataEnd - mfgStart).coerceAtLeast(0)

                        val decodedMfg = decodeManufacturerData(cid, rawBytes, mfgStart, mfgLen)
                        if (decodedMfg.trackerTag != null) {
                            trackerTag = decodedMfg.trackerTag
                        }
                        if (staticMfgHeaderHex == null && decodedMfg.staticHeaderHex != null) {
                            staticMfgHeaderHex = decodedMfg.staticHeaderHex
                        }
                        fields.add(
                            BleDecodedField(
                                typeHex,
                                "Manufacturer ($cName)",
                                decodedMfg.description
                            )
                        )
                    }
                }

                else -> {
                    if (dataLen > 0) {
                        fields.add(
                            BleDecodedField(
                                typeHex,
                                "AD Type $typeHex",
                                bytesToHex(rawBytes, dataStart, dataEnd)
                            )
                        )
                    }
                }
            }

            index += 1 + length
        }

        val summary = buildSummaryLine(trackerTag, companyName, localName, serviceUuids, txPowerDbm)

        return BleDecodedPacket(
            rawHexDump = rawHexDump,
            adTypes = adTypes,
            fields = fields,
            flagsSummary = flagsSummary,
            localName = localName,
            advertisedTxPowerDbm = txPowerDbm,
            serviceUuids = serviceUuids.distinct(),
            companyId = companyId,
            companyName = companyName,
            staticManufacturerHeaderHex = staticMfgHeaderHex,
            trackerTag = trackerTag,
            summaryLine = summary
        )
    }

    private data class ManufacturerDecodeResult(
        val description: String,
        val staticHeaderHex: String?,
        val trackerTag: BleTrackerTag?
    )

    private fun decodeManufacturerData(
        companyId: Int,
        bytes: ByteArray,
        offset: Int,
        len: Int
    ): ManufacturerDecodeResult {
        if (len <= 0) {
            return ManufacturerDecodeResult("Empty payload", null, null)
        }

        return when (companyId) {
            COMPANY_APPLE -> decodeAppleContinuity(bytes, offset, len)

            COMPANY_SAMSUNG -> {
                val subType = bytes[offset].toInt() and 0xFF
                val headerLen = len.coerceAtMost(2)
                val staticHex = bytesToHex(bytes, offset, offset + headerLen).replace(" ", "")
                val isSmartTag = subType == 0x42 || subType == 0x01
                val desc = if (isSmartTag) {
                    String.format(Locale.US, "SmartThings / SmartTag frame (type 0x%02X, %dB)", subType, len)
                } else {
                    String.format(Locale.US, "Samsung BLE frame (type 0x%02X, %dB)", subType, len)
                }
                ManufacturerDecodeResult(
                    description = desc,
                    staticHeaderHex = staticHex,
                    trackerTag = if (isSmartTag) BleTrackerTag.SAMSUNG_SMARTTAG else null
                )
            }

            COMPANY_TILE -> {
                val staticHex = bytesToHex(bytes, offset, offset + len.coerceAtMost(2)).replace(" ", "")
                ManufacturerDecodeResult(
                    description = "Tile Tracker Advertisement (${len}B)",
                    staticHeaderHex = staticHex,
                    trackerTag = BleTrackerTag.TILE_TRACKER
                )
            }

            else -> {
                val staticHex = bytesToHex(bytes, offset, offset + len.coerceAtMost(2)).replace(" ", "")
                val preview = bytesToHex(bytes, offset, offset + len.coerceAtMost(12))
                ManufacturerDecodeResult(
                    description = if (len > 12) "$preview … (${len}B)" else preview,
                    staticHeaderHex = staticHex,
                    trackerTag = null
                )
            }
        }
    }

    /**
     * Decodes Apple Continuity (Company ID 0x004C) TLV payloads.
     *
     * Key Find My / AirTag detection (Type 0x12, length 0x19):
     * - Byte 0: 0x12 (Offline Finding / Find My)
     * - Byte 1: payload length (0x19 = 25 bytes for full separated tracker broadcast;
     *           0x02 = 2 bytes for nearby/paired state)
     * - Byte 2: Status byte (bits 4..5 encode battery/maintainer state; length 0x19
     *           with rolling 22-byte public key indicates separated Find My state).
     * Static header excludes the 22-byte rotating EC public key so the tracker keeps
     * a stable signature across MAC rotations.
     */
    private fun decodeAppleContinuity(
        bytes: ByteArray,
        offset: Int,
        len: Int
    ): ManufacturerDecodeResult {
        val msgType = bytes[offset].toInt() and 0xFF
        val msgLen = if (len >= 2) bytes[offset + 1].toInt() and 0xFF else 0

        return when (msgType) {
            0x12 -> {
                val statusByte = if (len >= 3) bytes[offset + 2].toInt() and 0xFF else 0
                val isSeparated = msgLen >= 0x19 || len >= 24
                val batteryLevel = when ((statusByte shr 6) and 0x03) {
                    0 -> "Full"
                    1 -> "Medium"
                    2 -> "Low"
                    else -> "Critical"
                }
                // Static header: [msgType, msgLen, deviceRoleBits] — excludes rolling 22B key
                val roleBits = statusByte and 0x30
                val staticHeader = String.format(Locale.US, "%02X%02X%02X", msgType, msgLen, roleBits)
                val tag = if (isSeparated) {
                    BleTrackerTag.APPLE_FIND_MY_SEPARATED
                } else {
                    BleTrackerTag.APPLE_FIND_MY_NEARBY
                }
                val desc = if (isSeparated) {
                    "Find My Offline Finding · SEPARATED TRACKER (Battery: $batteryLevel, ${len}B)"
                } else {
                    "Find My · Owner Nearby State (Battery: $batteryLevel, ${len}B)"
                }
                ManufacturerDecodeResult(desc, staticHeader, tag)
            }

            0x02 -> {
                val staticHeader = String.format(Locale.US, "%02X%02X", msgType, msgLen)
                ManufacturerDecodeResult("iBeacon Proximity Frame (${len}B)", staticHeader, BleTrackerTag.APPLE_IBEACON)
            }

            0x05 -> {
                val staticHeader = String.format(Locale.US, "%02X%02X", msgType, msgLen)
                ManufacturerDecodeResult("Continuity · AirDrop (${len}B)", staticHeader, BleTrackerTag.APPLE_CONTINUITY)
            }

            0x07 -> {
                val modelHex = if (len >= 5) {
                    String.format(
                        Locale.US,
                        "%02X%02X",
                        bytes[offset + 3].toInt() and 0xFF,
                        bytes[offset + 4].toInt() and 0xFF
                    )
                } else {
                    ""
                }
                val staticHeader = String.format(Locale.US, "%02X%02X%s", msgType, msgLen, modelHex)
                ManufacturerDecodeResult(
                    "Continuity · Proximity Pairing / AirPods (Model 0x$modelHex)",
                    staticHeader,
                    BleTrackerTag.APPLE_AIRPODS
                )
            }

            0x09 -> {
                val staticHeader = String.format(Locale.US, "%02X%02X", msgType, msgLen)
                ManufacturerDecodeResult("Continuity · AirPlay Target", staticHeader, BleTrackerTag.APPLE_CONTINUITY)
            }

            0x0C -> {
                val staticHeader = String.format(Locale.US, "%02X%02X", msgType, msgLen)
                ManufacturerDecodeResult("Continuity · Handoff", staticHeader, BleTrackerTag.APPLE_CONTINUITY)
            }

            0x10 -> {
                val actionFlags = if (len >= 3) bytes[offset + 2].toInt() and 0xFF else 0
                // Exclude dynamic auth tag bytes at tail; keep type, len, and coarse device action mask
                val staticHeader = String.format(Locale.US, "%02X%02X%02X", msgType, msgLen, actionFlags and 0xF0)
                ManufacturerDecodeResult(
                    String.format(Locale.US, "Continuity · Nearby Info (flags 0x%02X)", actionFlags),
                    staticHeader,
                    BleTrackerTag.APPLE_CONTINUITY
                )
            }

            else -> {
                val staticHeader = String.format(Locale.US, "%02X%02X", msgType, msgLen)
                ManufacturerDecodeResult(
                    String.format(Locale.US, "Continuity · Type 0x%02X (%dB)", msgType, len),
                    staticHeader,
                    BleTrackerTag.APPLE_CONTINUITY
                )
            }
        }
    }

    private fun decodeFlags(flags: Int): String {
        val parts = mutableListOf<String>()
        if ((flags and 0x01) != 0) parts.add("LE Limited Discoverable")
        if ((flags and 0x02) != 0) parts.add("LE General Discoverable")
        if ((flags and 0x04) != 0) parts.add("BR/EDR Not Supported")
        if ((flags and 0x08) != 0) parts.add("Simultaneous LE+BR/EDR (Controller)")
        if ((flags and 0x10) != 0) parts.add("Simultaneous LE+BR/EDR (Host)")
        return if (parts.isEmpty()) {
            String.format(Locale.US, "0x%02X", flags)
        } else {
            parts.joinToString(" · ")
        }
    }

    private fun parse16BitUuids(bytes: ByteArray, start: Int, end: Int): List<String> {
        val out = mutableListOf<String>()
        var i = start
        while (i + 1 < end) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            out.add(String.format(Locale.US, "%02X%02X", hi, lo))
            i += 2
        }
        return out
    }

    private fun parse128BitUuids(bytes: ByteArray, start: Int, end: Int): List<String> {
        val out = mutableListOf<String>()
        var i = start
        while (i + 15 < end) {
            val sb = StringBuilder(36)
            for (b in 15 downTo 0) {
                sb.append(String.format(Locale.US, "%02X", bytes[i + b].toInt() and 0xFF))
                if (b == 12 || b == 10 || b == 8 || b == 6) sb.append('-')
            }
            out.add(sb.toString())
            i += 16
        }
        return out
    }

    private fun buildSummaryLine(
        trackerTag: BleTrackerTag?,
        companyName: String?,
        localName: String?,
        serviceUuids: List<String>,
        txPowerDbm: Int?
    ): String {
        val parts = mutableListOf<String>()
        if (trackerTag != null) {
            parts.add(trackerTag.displayLabel)
        } else if (companyName != null) {
            parts.add(companyName)
        } else if (localName != null) {
            parts.add(localName)
        } else if (serviceUuids.isNotEmpty()) {
            val first = serviceUuids.first()
            parts.add(SIG_UUID_NAMES[first] ?: "UUID 0x$first")
        }
        if (txPowerDbm != null) {
            parts.add("TX ${txPowerDbm}dBm")
        }
        return if (parts.isEmpty()) "Generic BLE Beacon" else parts.joinToString(" · ")
    }

    private fun trimTrailingPadding(bytes: ByteArray): Int {
        var idx = 0
        while (idx < bytes.size) {
            val len = bytes[idx].toInt() and 0xFF
            if (len == 0) return idx
            val next = idx + 1 + len
            if (next > bytes.size) return bytes.size
            idx = next
        }
        return bytes.size
    }

    private fun bytesToHex(bytes: ByteArray, start: Int, end: Int): String {
        if (start >= end) return ""
        val sb = StringBuilder((end - start) * 3)
        for (i in start until end) {
            if (i > start) sb.append(' ')
            sb.append(String.format(Locale.US, "%02X", bytes[i].toInt() and 0xFF))
        }
        return sb.toString()
    }
}
