package com.example.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

enum class CellRadioGeneration(val shortLabel: String) {
    NR_5G("5G NR"),
    LTE_4G("4G LTE"),
    WCDMA_3G("3G UMTS"),
    GSM_2G("2G GSM")
}

data class CellTowerInfo(
    val generation: CellRadioGeneration,
    val isServing: Boolean,
    val pci: Int?,
    val rsrpDbm: Int?,
    val rsrqDb: Int? = null,
    val sinrDb: Int?,
    val arfcn: Int?,
    val bands: List<Int> = emptyList(),
    val cellIdentityId: Long? = null,
    val tacOrLac: Int? = null,
    val timingAdvance: Int?,
    val timingAdvanceDistanceMeters: Float?,
    val plmn: String?
)

data class CellularSnapshot(
    val towers: List<CellTowerInfo> = emptyList(),
    val servingGeneration: CellRadioGeneration? = null,
    val voiceNetworkTypeName: String? = null,
    val dataNetworkTypeName: String? = null,
    val hasFineLocationPermission: Boolean = false,
    /**
     * READ_PHONE_STATE is strictly optional and on-demand (never requested at startup).
     * When false on GrapheneOS, basic PCI/RSRP/TA still operate via ACCESS_FINE_LOCATION.
     */
    val hasPhoneStatePermission: Boolean = false,
    val is2gDowngradeAlert: Boolean = false,
    val downgradeReason: String? = null,
    val lastUpdatedMs: Long = 0L
)

/**
 * Serving & neighbor 5G NR / 4G LTE / 3G / 2G cellular monitor with Timing Advance
 * distance estimation and optional 2G Stingray / IMSI-catcher downgrade detection.
 */
class CellularMonitor(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val UNAVAILABLE_INT = Int.MAX_VALUE
        private const val UNAVAILABLE_LONG = Long.MAX_VALUE
        // 3GPP TS 36.213 LTE Timing Advance step = 16 * Ts * c / 2 ≈ 78.12 meters per unit
        const val LTE_TA_METERS_PER_UNIT = 78.12f
        // 3GPP TS 45.010 GSM Timing Advance step ≈ 553.8 meters per unit
        const val GSM_TA_METERS_PER_UNIT = 553.8f
        const val TOWER_CACHE_TTL_MS = 35_000L
        private const val POLL_INTERVAL_MS = 4_000L

        data class CellTowerKey(
            val generation: CellRadioGeneration,
            val pci: Int?,
            val arfcn: Int?
        )

        data class CachedCellTower(
            val tower: CellTowerInfo,
            val lastSeenElapsedMs: Long
        )

        /**
         * Merges fresh baseband cell reports into a 35-second rolling cache keyed by
         * `(rat, pci, arfcn)` so partial Serving-only sweeps never drop neighbor towers.
         */
        fun mergeCellTowerCache(
            cache: MutableMap<CellTowerKey, CachedCellTower>,
            freshTowers: List<CellTowerInfo>,
            nowElapsedMs: Long
        ): List<CellTowerInfo> {
            if (freshTowers.any { it.isServing }) {
                for ((key, cached) in cache) {
                    if (cached.tower.isServing) {
                        cache[key] = cached.copy(tower = cached.tower.copy(isServing = false))
                    }
                }
            }
            for (tower in freshTowers) {
                val key = CellTowerKey(tower.generation, tower.pci, tower.arfcn)
                cache[key] = CachedCellTower(tower = tower, lastSeenElapsedMs = nowElapsedMs)
            }
            val iter = cache.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if ((nowElapsedMs - entry.value.lastSeenElapsedMs) > TOWER_CACHE_TTL_MS) {
                    iter.remove()
                }
            }
            return cache.values
                .map { it.tower }
                .sortedWith(
                    compareByDescending<CellTowerInfo> { it.isServing }
                        .thenByDescending { it.rsrpDbm ?: Int.MIN_VALUE }
                )
        }

        private val CARRIER_PLMN_MAP = mapOf(
            // Canada (MCC 302)
            "302-720" to "Rogers Wireless (CA)",
            "302-320" to "Rogers / Chatr (CA)",
            "302-370" to "Fido / Rogers (CA)",
            "302-220" to "Telus Mobility (CA)",
            "302-221" to "Telus Mobility (CA)",
            "302-610" to "Bell Mobility (CA)",
            "302-640" to "Bell Mobility (CA)",
            "302-880" to "Bell / Telus Shared RAN (CA)",
            "302-490" to "Freedom Mobile (CA)",
            "302-500" to "Vidéotron (CA)",
            "302-510" to "Vidéotron (CA)",
            "302-780" to "SaskTel (CA)",
            "302-620" to "ICE Wireless (CA)",
            "302-710" to "Globalstar Canada",
            // United States (MCC 310/311)
            "310-410" to "AT&T Mobility (US)",
            "310-260" to "T-Mobile (US)",
            "311-480" to "Verizon Wireless (US)",
            "310-120" to "T-Mobile / Sprint (US)"
        )

        fun lookupCarrierName(plmn: String?): String? {
            if (plmn.isNullOrBlank()) return null
            val normalized = plmn.trim()
            CARRIER_PLMN_MAP[normalized]?.let { return it }
            // Normalize 2-digit vs 3-digit MNC variations (e.g., 302-720)
            val parts = normalized.split('-')
            if (parts.size == 2) {
                val mcc = parts[0]
                val mncPadded = parts[1].padStart(3, '0')
                CARRIER_PLMN_MAP["$mcc-$mncPadded"]?.let { return it }
                if (mcc == "302") return "Canadian Carrier ($normalized)"
                if (mcc == "310" || mcc == "311") return "US Carrier ($normalized)"
            }
            return null
        }

        fun deriveBandLabel(
            generation: CellRadioGeneration,
            arfcn: Int?,
            reportedBands: List<Int>
        ): String {
            if (reportedBands.isNotEmpty()) {
                val prefix = if (generation == CellRadioGeneration.NR_5G) "n" else "B"
                return reportedBands.joinToString(", ") { "$prefix$it" }
            }
            if (arfcn == null) return "Unknown Band"
            return when (generation) {
                CellRadioGeneration.LTE_4G -> when (arfcn) {
                    in 0..599 -> "B1 (2100 MHz)"
                    in 600..1199 -> "B2 (1900 MHz PCS)"
                    in 1200..1949 -> "B3 (1800 MHz)"
                    in 1950..2399 -> "B4 (1700/2100 AWS)"
                    in 2400..2649 -> "B5 (850 MHz)"
                    in 2750..3449 -> "B7 (2600 MHz)"
                    in 3450..3799 -> "B8 (900 MHz)"
                    in 5010..5179 -> "B12 (700 MHz)"
                    in 5180..5279 -> "B13 (700 MHz)"
                    in 5730..5849 -> "B17 (700 MHz)"
                    in 8040..8689 -> "B25 (1900 MHz)"
                    in 9210..9659 -> "B28 (700 MHz)"
                    in 9660..9769 -> "B29 (700 MHz DL)"
                    in 9770..9869 -> "B30 (2300 MHz)"
                    in 37750..38249 -> "B38 (2600 TDD)"
                    in 39650..41589 -> "B41 (2500 TDD)"
                    in 66436..67335 -> "B66 (1700/2100 AWS-3)"
                    in 68586..68935 -> "B71 (600 MHz)"
                    else -> "EARFCN $arfcn"
                }

                CellRadioGeneration.NR_5G -> when (arfcn) {
                    in 123400..130400 -> "n71 (600 MHz)"
                    in 173800..178800 -> "n5 (850 MHz)"
                    in 386000..398000 -> "n2 (1900 MHz)"
                    in 422000..440000 -> "n66 (AWS)"
                    in 499200..537999 -> "n41 (2500 MHz)"
                    in 524000..538000 -> "n7 (2600 MHz)"
                    in 620000..653333 -> "n78 (3500 MHz C-Band)"
                    in 620000..680000 -> "n77 (3700 MHz C-Band)"
                    else -> "NR-ARFCN $arfcn"
                }

                CellRadioGeneration.WCDMA_3G -> "UARFCN $arfcn"
                CellRadioGeneration.GSM_2G -> "GSM ARFCN $arfcn"
            }
        }

        fun rsrpQualitySummary(rsrpDbm: Int?): String = when {
            rsrpDbm == null -> "Unavailable"
            rsrpDbm >= -80 -> "Excellent (very strong / close tower)"
            rsrpDbm >= -95 -> "Good (solid urban/suburban coverage)"
            rsrpDbm >= -110 -> "Fair (mid-cell or indoor attenuation)"
            else -> "Weak (cell edge or heavy obstruction)"
        }

        fun lteTimingAdvanceToMeters(ta: Int?): Float? {
            if (ta == null || ta < 0 || ta > 1282 || ta == UNAVAILABLE_INT) return null
            return ta * LTE_TA_METERS_PER_UNIT
        }

        fun gsmTimingAdvanceToMeters(ta: Int?): Float? {
            if (ta == null || ta < 0 || ta > 63 || ta == UNAVAILABLE_INT) return null
            return ta * GSM_TA_METERS_PER_UNIT
        }

        fun evaluate2gDowngradeAlert(
            previouslyObservedModernRat: Boolean,
            servingGen: CellRadioGeneration?,
            voiceNetType: Int?,
            dataNetType: Int?,
            servingRsrpDbm: Int?,
            neighborCount: Int
        ): Pair<Boolean, String?> {
            val isVoice2g = voiceNetType != null && is2gNetworkType(voiceNetType)
            val isData2g = dataNetType != null && is2gNetworkType(dataNetType)
            val isServing2g = servingGen == CellRadioGeneration.GSM_2G

            if (!isServing2g && !isVoice2g && !isData2g) {
                return false to null
            }
            if (previouslyObservedModernRat) {
                return true to "Active downgrade from 4G/5G to 2G GSM detected"
            }
            if (isServing2g && (servingRsrpDbm != null && servingRsrpDbm >= -65) && neighborCount == 0) {
                return true to "Isolated high-power 2G GSM cell (${servingRsrpDbm} dBm, 0 neighbors)"
            }
            if (isVoice2g) {
                return true to "Voice radio forced onto legacy 2G GSM/EDGE"
            }
            return true to "Serving cell operating on unencrypted 2G GSM"
        }

        @Suppress("DEPRECATION")
        private fun is2gNetworkType(type: Int): Boolean {
            return type == TelephonyManager.NETWORK_TYPE_GSM ||
                type == TelephonyManager.NETWORK_TYPE_GPRS ||
                type == TelephonyManager.NETWORK_TYPE_EDGE ||
                type == TelephonyManager.NETWORK_TYPE_CDMA ||
                type == TelephonyManager.NETWORK_TYPE_1xRTT ||
                type == TelephonyManager.NETWORK_TYPE_IDEN
        }

        @Suppress("DEPRECATION")
        private fun networkTypeName(type: Int): String = when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            19 -> "4G LTE-A (CA)" // NETWORK_TYPE_LTE_CA
            TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi (IWLAN)"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
            TelephonyManager.NETWORK_TYPE_GSM -> "2G GSM"
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT -> "2G CDMA"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
            else -> "RAT #$type"
        }
    }

    private val appContext = context.applicationContext
    private val telephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val cellExecutor = Executors.newSingleThreadExecutor()
    private val towerCacheLock = Any()
    private val towerCache = LinkedHashMap<CellTowerKey, CachedCellTower>()

    private val _snapshot = MutableStateFlow(CellularSnapshot())
    val snapshot: StateFlow<CellularSnapshot> = _snapshot.asStateFlow()

    private var pollJob: Job? = null
    private var observedModernRat = false

    fun hasReadPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                refreshOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun shutdown() {
        stop()
        cellExecutor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    suspend fun refreshOnce() {
        val tm = telephonyManager ?: return
        val hasFine = hasFineLocationPermission()
        val hasPhoneState = hasReadPhoneStatePermission()

        if (!hasFine) {
            _snapshot.value = _snapshot.value.copy(
                hasFineLocationPermission = false,
                hasPhoneStatePermission = hasPhoneState
            )
            return
        }

        val rawCells: List<CellInfo> = fetchCellInfoSafely(tm)
        val freshParsed = rawCells.mapNotNull { parseCellInfo(it) }
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val parsed = synchronized(towerCacheLock) {
            mergeCellTowerCache(towerCache, freshParsed, nowElapsed)
        }

        val serving = parsed.firstOrNull { it.isServing }
        if (serving?.generation == CellRadioGeneration.NR_5G ||
            serving?.generation == CellRadioGeneration.LTE_4G
        ) {
            observedModernRat = true
        }

        var voiceType: Int? = null
        var dataType: Int? = null
        if (hasPhoneState) {
            try {
                voiceType = tm.voiceNetworkType
                dataType = tm.dataNetworkType
                if (dataType == TelephonyManager.NETWORK_TYPE_NR ||
                    dataType == TelephonyManager.NETWORK_TYPE_LTE
                ) {
                    observedModernRat = true
                }
            } catch (_: SecurityException) {
                // GrapheneOS may revoke dynamically
            }
        }

        val neighborsCount = parsed.count { !it.isServing }
        val (alert, reason) = evaluate2gDowngradeAlert(
            previouslyObservedModernRat = observedModernRat,
            servingGen = serving?.generation,
            voiceNetType = voiceType,
            dataNetType = dataType,
            servingRsrpDbm = serving?.rsrpDbm,
            neighborCount = neighborsCount
        )

        _snapshot.value = CellularSnapshot(
            towers = parsed,
            servingGeneration = serving?.generation,
            voiceNetworkTypeName = voiceType?.let { networkTypeName(it) },
            dataNetworkTypeName = dataType?.let { networkTypeName(it) },
            hasFineLocationPermission = true,
            hasPhoneStatePermission = hasPhoneState,
            is2gDowngradeAlert = alert,
            downgradeReason = reason,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchCellInfoSafely(tm: TelephonyManager): List<CellInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fresh = withTimeoutOrNull(1_500L) {
                suspendCancellableCoroutine<List<CellInfo>> { cont ->
                    try {
                        tm.requestCellInfoUpdate(
                            cellExecutor,
                            object : TelephonyManager.CellInfoCallback() {
                                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                    if (cont.isActive) cont.resume(cellInfo)
                                }

                                override fun onError(errorCode: Int, detail: Throwable?) {
                                    if (cont.isActive) cont.resume(emptyList())
                                }
                            }
                        )
                    } catch (_: SecurityException) {
                        if (cont.isActive) cont.resume(emptyList())
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
            }
            if (!fresh.isNullOrEmpty()) return fresh
        }

        return try {
            tm.allCellInfo ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCellInfo(cell: CellInfo): CellTowerInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) {
            val id = cell.cellIdentity as? CellIdentityNr ?: return null
            val sig = cell.cellSignalStrength as? CellSignalStrengthNr ?: return null
            val pci = id.pci.takeIf { it != UNAVAILABLE_INT && it >= 0 }
            val rsrp = sig.ssRsrp.takeIf { it != UNAVAILABLE_INT && it < 0 }
                ?: sig.csiRsrp.takeIf { it != UNAVAILABLE_INT && it < 0 }
                ?: sig.dbm.takeIf { it != UNAVAILABLE_INT && it < 0 }
            val rsrq = sig.ssRsrq.takeIf { it != UNAVAILABLE_INT && it <= 0 }
                ?: sig.csiRsrq.takeIf { it != UNAVAILABLE_INT && it <= 0 }
            val sinr = sig.ssSinr.takeIf { it != UNAVAILABLE_INT }
                ?: sig.csiSinr.takeIf { it != UNAVAILABLE_INT }
            val nrarfcn = id.nrarfcn.takeIf { it != UNAVAILABLE_INT && it >= 0 }
            val nci = id.nci.takeIf { it != UNAVAILABLE_LONG && it >= 0L }
            val tac = id.tac.takeIf { it != UNAVAILABLE_INT && it >= 0 }
            val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                id.bands.filter { it > 0 }
            } else {
                emptyList()
            }
            val plmn = listOfNotNull(id.mccString, id.mncString).joinToString("-").ifBlank { null }
            return CellTowerInfo(
                generation = CellRadioGeneration.NR_5G,
                isServing = cell.isRegistered,
                pci = pci,
                rsrpDbm = rsrp,
                rsrqDb = rsrq,
                sinrDb = sinr,
                arfcn = nrarfcn,
                bands = bands,
                cellIdentityId = nci,
                tacOrLac = tac,
                timingAdvance = null,
                timingAdvanceDistanceMeters = null,
                plmn = plmn
            )
        }

        return when (cell) {
            is CellInfoLte -> {
                val id: CellIdentityLte = cell.cellIdentity
                val sig: CellSignalStrengthLte = cell.cellSignalStrength
                val pci = id.pci.takeIf { it != UNAVAILABLE_INT && it >= 0 }
                val rsrp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sig.rsrp.takeIf { it != UNAVAILABLE_INT && it < 0 }
                } else {
                    sig.dbm.takeIf { it != UNAVAILABLE_INT && it < 0 }
                }
                val rsrq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sig.rsrq.takeIf { it != UNAVAILABLE_INT && it <= 0 }
                } else {
                    null
                }
                val sinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sig.rssnr.takeIf { it != UNAVAILABLE_INT && it in -20..30 }
                } else {
                    null
                }
                val earfcn = id.earfcn.takeIf { it != UNAVAILABLE_INT && it >= 0 }
                val eci = id.ci.takeIf { it != UNAVAILABLE_INT && it >= 0 }?.toLong()
                val tac = id.tac.takeIf { it != UNAVAILABLE_INT && it >= 0 }
                val bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    id.bands.filter { it > 0 }
                } else {
                    emptyList()
                }
                val ta = sig.timingAdvance.takeIf { it != UNAVAILABLE_INT && it in 0..1282 }
                val plmn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    listOfNotNull(id.mccString, id.mncString).joinToString("-").ifBlank { null }
                } else {
                    null
                }
                CellTowerInfo(
                    generation = CellRadioGeneration.LTE_4G,
                    isServing = cell.isRegistered,
                    pci = pci,
                    rsrpDbm = rsrp,
                    rsrqDb = rsrq,
                    sinrDb = sinr,
                    arfcn = earfcn,
                    bands = bands,
                    cellIdentityId = eci,
                    tacOrLac = tac,
                    timingAdvance = ta,
                    timingAdvanceDistanceMeters = lteTimingAdvanceToMeters(ta),
                    plmn = plmn
                )
            }

            is CellInfoGsm -> {
                val id: CellIdentityGsm = cell.cellIdentity
                val sig: CellSignalStrengthGsm = cell.cellSignalStrength
                val rssi = sig.dbm.takeIf { it != UNAVAILABLE_INT && it < 0 }
                val arfcn = id.arfcn.takeIf { it != UNAVAILABLE_INT && it >= 0 }
                val cid = id.cid.takeIf { it != UNAVAILABLE_INT && it >= 0 }?.toLong()
                val lac = id.lac.takeIf { it != UNAVAILABLE_INT && it >= 0 }
                val ta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sig.timingAdvance.takeIf { it != UNAVAILABLE_INT && it in 0..63 }
                } else {
                    null
                }
                val plmn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    listOfNotNull(id.mccString, id.mncString).joinToString("-").ifBlank { null }
                } else {
                    null
                }
                CellTowerInfo(
                    generation = CellRadioGeneration.GSM_2G,
                    isServing = cell.isRegistered,
                    pci = id.bsic.takeIf { it != UNAVAILABLE_INT && it >= 0 },
                    rsrpDbm = rssi,
                    rsrqDb = null,
                    sinrDb = null,
                    arfcn = arfcn,
                    bands = emptyList(),
                    cellIdentityId = cid,
                    tacOrLac = lac,
                    timingAdvance = ta,
                    timingAdvanceDistanceMeters = gsmTimingAdvanceToMeters(ta),
                    plmn = plmn
                )
            }

            is CellInfoWcdma -> {
                val id: CellIdentityWcdma = cell.cellIdentity
                val sig: CellSignalStrengthWcdma = cell.cellSignalStrength
                val psc = id.psc.takeIf { it != UNAVAILABLE_INT && it >= 0 }
                val rscp = sig.dbm.takeIf { it != UNAVAILABLE_INT && it < 0 }
                val uarfcn = id.uarfcn.takeIf { it != UNAVAILABLE_INT && it >= 0 }
                val cid = id.cid.takeIf { it != UNAVAILABLE_INT && it >= 0 }?.toLong()
                val lac = id.lac.takeIf { it != UNAVAILABLE_INT && it >= 0 }
                val plmn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    listOfNotNull(id.mccString, id.mncString).joinToString("-").ifBlank { null }
                } else {
                    null
                }
                CellTowerInfo(
                    generation = CellRadioGeneration.WCDMA_3G,
                    isServing = cell.isRegistered,
                    pci = psc,
                    rsrpDbm = rscp,
                    rsrqDb = null,
                    sinrDb = null,
                    arfcn = uarfcn,
                    bands = emptyList(),
                    cellIdentityId = cid,
                    tacOrLac = lac,
                    timingAdvance = null,
                    timingAdvanceDistanceMeters = null,
                    plmn = plmn
                )
            }

            else -> null
        }
    }
}
