package com.example.dsp

import java.util.Locale
import kotlin.math.sqrt

data class DroneDetectionResult(
    val isDrone: Boolean,
    val protocolLabel: String,
    val reason: String
)

/**
 * Strict multi-protocol passive Drone / UAV detector (zero RSSI fast-fader heuristics):
 * 1. ASTM F3411 Open Drone ID via BLE Service UUID `0xFFFA` (`0x16` service data)
 * 2. ASTM F3411 Open Drone ID via Wi-Fi Vendor IE OUI `FA:0B:BC` (type `0x0D`)
 * 3. DJI Proprietary DroneID via Wi-Fi Vendor IE OUI `26:37:12`
 * 4. Authoritative IEEE Drone OUIs (24-bit MA-L & 28-bit MA-M) ONLY when Wi-Fi SSID
 *    matches known UAV prefixes (`DJI*`, `TELLO*`, `Mavic*`, `Mini*`, `Air*`,
 *    `Autel*`, `EVO*`, `Parrot*`, `Anafi*`, `Skydio*`).
 */
object DroneDetectorEngine {

    const val OPEN_DRONE_ID_BLE_UUID = "FFFA"
    const val OPEN_DRONE_ID_WIFI_OUI = "FA:0B:BC"
    const val DJI_DRONE_ID_WIFI_OUI = "26:37:12"

    val KNOWN_DRONE_OUIS_24 = setOf(
        // DJI (10 MA-L blocks)
        "04:A8:5A", "0C:9A:E6", "34:D2:62", "48:1C:B9", "4C:43:F6",
        "58:B8:58", "60:60:1F", "88:29:85", "8C:58:23", "E4:7A:2C",
        // Parrot (5 MA-L blocks)
        "00:12:1C", "00:26:7E", "90:03:B7", "90:3A:E6", "A0:14:3D",
        // Skydio
        "38:1D:14",
        // Autel legacy
        "EC:1B:BD"
    )

    // 28-bit MA-M prefixes (normalized uppercase without colons, 7 hex chars)
    val KNOWN_DRONE_MAM_28_HEX = setOf(
        "EC5BCDE", // Autel
        "18D7936", // Autel
        "E0B6F58", // Yuneec
        "98AAFC7"  // Hubsan
    )

    private val DRONE_SSID_PREFIXES = listOf(
        "DJI", "TELLO", "MAVIC", "MINI", "AIR", "AUTEL", "EVO", "PARROT", "ANAFI", "SKYDIO"
    )

    fun matchesDroneSsid(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        val upper = ssid.trim().uppercase(Locale.US)
        return DRONE_SSID_PREFIXES.any { upper.startsWith(it) }
    }

    fun matchesDroneOui(macAddress: String): String? {
        val cleanMac = macAddress.trim().uppercase(Locale.US)
        val macOui24 = if (cleanMac.length >= 8) cleanMac.substring(0, 8) else ""
        if (macOui24 in KNOWN_DRONE_OUIS_24) return macOui24
        val hexOnly = cleanMac.replace(":", "")
        if (hexOnly.length >= 7) {
            val prefix28 = hexOnly.substring(0, 7)
            if (prefix28 in KNOWN_DRONE_MAM_28_HEX) return prefix28
        }
        return null
    }

    fun detect(
        macAddress: String,
        transport: EmitterTransport,
        displayName: String? = null,
        bleDecoded: BleDecodedPacket? = null,
        wifiVendorOuiHexes: List<String> = emptyList()
    ): DroneDetectionResult {
        // 1. BLE ASTM F3411 Open Drone ID (0xFFFA with 0x16 Service Data AD type)
        if (transport == EmitterTransport.BLE && bleDecoded != null) {
            val hasServiceDataAd = 0x16 in bleDecoded.adTypes
            val hasFffaUuid = bleDecoded.serviceUuids.any {
                it.equals(OPEN_DRONE_ID_BLE_UUID, ignoreCase = true)
            }
            if (hasServiceDataAd && hasFffaUuid) {
                return DroneDetectionResult(
                    isDrone = true,
                    protocolLabel = "ASTM F3411 BLE",
                    reason = "Open Drone ID (0xFFFA Service Data)"
                )
            }
        }

        // 2. Wi-Fi Vendor IE OUIs (ASTM F3411 FA:0B:BC type 0x0D or DJI DroneID 26:37:12)
        for (oui in wifiVendorOuiHexes) {
            val norm = oui.trim().uppercase(Locale.US)
            if (norm == "$OPEN_DRONE_ID_WIFI_OUI:0D" || norm == OPEN_DRONE_ID_WIFI_OUI || norm == "FA0BBC0D") {
                return DroneDetectionResult(
                    isDrone = true,
                    protocolLabel = "ASTM F3411 Wi-Fi",
                    reason = "Open Drone ID Vendor IE (FA:0B:BC)"
                )
            }
            if (norm == DJI_DRONE_ID_WIFI_OUI || norm == "263712") {
                return DroneDetectionResult(
                    isDrone = true,
                    protocolLabel = "DJI DroneID",
                    reason = "DJI Vendor IE (26:37:12)"
                )
            }
        }

        // 3. Known Drone Wi-Fi OUI ONLY when Wi-Fi SSID matches drone prefixes
        if (transport == EmitterTransport.WIFI && matchesDroneSsid(displayName)) {
            val matchedOui = matchesDroneOui(macAddress)
            if (matchedOui != null) {
                return DroneDetectionResult(
                    isDrone = true,
                    protocolLabel = "Drone OUI ($matchedOui)",
                    reason = "Verified UAV OUI $matchedOui + SSID '$displayName'"
                )
            }
        }

        return DroneDetectionResult(
            isDrone = false,
            protocolLabel = "",
            reason = ""
        )
    }

    /**
     * 3D slant-to-ground elevation compensation:
     *     d_ground = sqrt(max(0, d_slant^2 - h_alt^2))
     */
    fun slantToGroundDistance(slantDistanceMeters: Double, altitudeMeters: Double): Double {
        if (!slantDistanceMeters.isFinite() || slantDistanceMeters <= 0.0) return 0.0
        if (!altitudeMeters.isFinite() || altitudeMeters <= 0.0) return slantDistanceMeters
        val sq = slantDistanceMeters * slantDistanceMeters - altitudeMeters * altitudeMeters
        return if (sq <= 0.0) 0.0 else sqrt(sq)
    }
}

object EvilTwinDetector {
    fun normalizeSecurity(capabilities: String?): String {
        val cap = capabilities?.uppercase(Locale.US) ?: return "OPEN"
        return when {
            cap.contains("SAE") || cap.contains("WPA3") -> "WPA3"
            cap.contains("WPA2") || cap.contains("RSN") -> "WPA2"
            cap.contains("WPA") -> "WPA"
            cap.contains("WEP") -> "WEP"
            else -> "OPEN"
        }
    }

    fun flagEvilTwins(emitters: List<DiscoveredEmitter>): List<DiscoveredEmitter> {
        val ssidCaps = HashMap<String, MutableSet<String>>()
        for (e in emitters) {
            if (e.transport == EmitterTransport.WIFI &&
                e.displayName.isNotBlank() &&
                e.displayName != "Hidden AP"
            ) {
                ssidCaps.getOrPut(e.displayName) { mutableSetOf() }
                    .add(normalizeSecurity(e.wifiCapabilities))
            }
        }
        return emitters.map { e ->
            if (e.transport == EmitterTransport.WIFI &&
                e.displayName.isNotBlank() &&
                e.displayName != "Hidden AP"
            ) {
                val caps = ssidCaps[e.displayName]
                if (caps != null && caps.size > 1) {
                    e.copy(
                        isEvilTwinSuspect = true,
                        evilTwinReason = "Security mismatch across BSSIDs: ${caps.joinToString(" vs ")}"
                    )
                } else {
                    e
                }
            } else {
                e
            }
        }
    }
}
