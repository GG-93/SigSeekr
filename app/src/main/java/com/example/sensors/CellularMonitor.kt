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
    val sinrDb: Int?,
    val arfcn: Int?,
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
        // 3GPP TS 36.213 LTE Timing Advance step = 16 * Ts * c / 2 ≈ 78.12 meters per unit
        const val LTE_TA_METERS_PER_UNIT = 78.12f
        // 3GPP TS 45.010 GSM Timing Advance step ≈ 553.8 meters per unit
        const val GSM_TA_METERS_PER_UNIT = 553.8f
        private const val POLL_INTERVAL_MS = 4_000L

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
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
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
        val parsed = rawCells.mapNotNull { parseCellInfo(it) }
            .sortedWith(
                compareByDescending<CellTowerInfo> { it.isServing }
                    .thenByDescending { it.rsrpDbm ?: Int.MIN_VALUE }
            )

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
            val sinr = sig.ssSinr.takeIf { it != UNAVAILABLE_INT }
                ?: sig.csiSinr.takeIf { it != UNAVAILABLE_INT }
            val nrarfcn = id.nrarfcn.takeIf { it != UNAVAILABLE_INT && it >= 0 }
            val plmn = listOfNotNull(id.mccString, id.mncString).joinToString("-").ifBlank { null }
            return CellTowerInfo(
                generation = CellRadioGeneration.NR_5G,
                isServing = cell.isRegistered,
                pci = pci,
                rsrpDbm = rsrp,
                sinrDb = sinr,
                arfcn = nrarfcn,
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
                val sinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sig.rssnr.takeIf { it != UNAVAILABLE_INT && it in -20..30 }
                } else {
                    null
                }
                val earfcn = id.earfcn.takeIf { it != UNAVAILABLE_INT && it >= 0 }
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
                    sinrDb = sinr,
                    arfcn = earfcn,
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
                    sinrDb = null,
                    arfcn = arfcn,
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
                    sinrDb = null,
                    arfcn = uarfcn,
                    timingAdvance = null,
                    timingAdvanceDistanceMeters = null,
                    plmn = plmn
                )
            }

            else -> null
        }
    }
}
