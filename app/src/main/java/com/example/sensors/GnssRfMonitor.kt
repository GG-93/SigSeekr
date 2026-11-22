package com.example.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

enum class GnssInterferenceLevel(val displayLabel: String) {
    UNKNOWN("AWAITING GNSS"),
    NOMINAL("NOMINAL"),
    ELEVATED("ELEVATED NOISE"),
    JAMMING_SUSPECTED("JAMMING DETECTED"),
    SPOOFING_SUSPECTED("SPOOFING SUSPECTED")
}

data class GnssEpochSample(
    val meanAgcDb: Double?,
    val top3Cn0DbHz: Double
)

data class GnssRfStatus(
    val interferenceLevel: GnssInterferenceLevel = GnssInterferenceLevel.UNKNOWN,
    val satelliteCount: Int = 0,
    val meanCn0DbHz: Float = 0f,
    val top3Cn0DbHz: Float = 0f,
    val latestAgcDb: Float? = null,
    val agcDeltaDb: Float = 0f,
    val cn0DeltaDbHz: Float = 0f,
    val epochsObserved: Int = 0,
    val reason: String = "Listening for raw GNSS AGC & C/N0 measurements",
    val lastUpdatedMs: Long = 0L
)

/**
 * Implements Android's GnssLogger 10-epoch vs 50-epoch differential AGC & C/N0
 * jamming/spoofing classifier:
 * - Jamming: AGC drops AND C/N0 drops simultaneously (raised RF noise floor).
 * - Spoofing: AGC drops AND C/N0 increases simultaneously (loud fake satellite carrier).
 */
class GnssInterferenceAnalyzer {

    companion object {
        private const val RECENT_WINDOW = 10
        private const val BASELINE_WINDOW = 50
        private const val MAX_HISTORY = RECENT_WINDOW + BASELINE_WINDOW
        private const val AGC_DROP_THRESHOLD_DB = -2.0
        private const val CN0_DROP_THRESHOLD_DBHZ = -3.0
        private const val CN0_SURGE_THRESHOLD_DBHZ = 3.0
    }

    private val history = ArrayDeque<GnssEpochSample>()

    fun reset() {
        history.clear()
    }

    fun pushEpoch(
        meanAgcDb: Double?,
        meanCn0DbHz: Double,
        top3Cn0DbHz: Double,
        satelliteCount: Int,
        timestampMs: Long
    ): GnssRfStatus {
        history.addLast(GnssEpochSample(meanAgcDb, top3Cn0DbHz))
        while (history.size > MAX_HISTORY) {
            history.removeFirst()
        }

        val total = history.size
        if (total < RECENT_WINDOW + 5) {
            // Absolute cold-start fallback if launched directly inside severe jamming
            val severeColdJam = satelliteCount > 0 && top3Cn0DbHz < 18.0 && (meanAgcDb != null && meanAgcDb < -6.0)
            return GnssRfStatus(
                interferenceLevel = if (severeColdJam) GnssInterferenceLevel.JAMMING_SUSPECTED else GnssInterferenceLevel.NOMINAL,
                satelliteCount = satelliteCount,
                meanCn0DbHz = meanCn0DbHz.toFloat(),
                top3Cn0DbHz = top3Cn0DbHz.toFloat(),
                latestAgcDb = meanAgcDb?.toFloat(),
                agcDeltaDb = 0f,
                cn0DeltaDbHz = 0f,
                epochsObserved = total,
                reason = if (severeColdJam) {
                    "Severe low C/N0 & depressed AGC on cold start"
                } else {
                    "Building baseline ($total/${RECENT_WINDOW + 5} epochs)"
                },
                lastUpdatedMs = timestampMs
            )
        }

        val all = history.toList()
        val recent = all.takeLast(RECENT_WINDOW)
        val baseline = all.dropLast(RECENT_WINDOW)

        val recentCn0 = recent.map { it.top3Cn0DbHz }.average()
        val baseCn0 = baseline.map { it.top3Cn0DbHz }.average()
        val cn0Delta = recentCn0 - baseCn0

        val recentAgcVals = recent.mapNotNull { it.meanAgcDb }
        val baseAgcVals = baseline.mapNotNull { it.meanAgcDb }
        val agcDelta = if (recentAgcVals.isNotEmpty() && baseAgcVals.isNotEmpty()) {
            recentAgcVals.average() - baseAgcVals.average()
        } else {
            0.0
        }

        val hasAgc = recentAgcVals.isNotEmpty() && baseAgcVals.isNotEmpty()
        val (level, reason) = when {
            hasAgc && agcDelta <= AGC_DROP_THRESHOLD_DB && cn0Delta <= CN0_DROP_THRESHOLD_DBHZ -> {
                GnssInterferenceLevel.JAMMING_SUSPECTED to
                    String.format(
                        java.util.Locale.US,
                        "AGC dropped %.1f dB & C/N0 dropped %.1f dB-Hz",
                        agcDelta,
                        cn0Delta
                    )
            }

            hasAgc && agcDelta <= AGC_DROP_THRESHOLD_DB && cn0Delta >= CN0_SURGE_THRESHOLD_DBHZ -> {
                GnssInterferenceLevel.SPOOFING_SUSPECTED to
                    String.format(
                        java.util.Locale.US,
                        "AGC dropped %.1f dB while C/N0 surged +%.1f dB-Hz",
                        agcDelta,
                        cn0Delta
                    )
            }

            (hasAgc && agcDelta <= -1.5) || cn0Delta <= -4.5 -> {
                GnssInterferenceLevel.ELEVATED to
                    String.format(
                        java.util.Locale.US,
                        "RF variance ΔAGC %.1f dB · ΔC/N0 %.1f dB-Hz",
                        agcDelta,
                        cn0Delta
                    )
            }

            else -> {
                GnssInterferenceLevel.NOMINAL to "AGC & C/N0 tracking nominal"
            }
        }

        return GnssRfStatus(
            interferenceLevel = level,
            satelliteCount = satelliteCount,
            meanCn0DbHz = meanCn0DbHz.toFloat(),
            top3Cn0DbHz = top3Cn0DbHz.toFloat(),
            latestAgcDb = meanAgcDb?.toFloat(),
            agcDeltaDb = agcDelta.toFloat(),
            cn0DeltaDbHz = cn0Delta.toFloat(),
            epochsObserved = total,
            reason = reason,
            lastUpdatedMs = timestampMs
        )
    }
}

/**
 * Raw GNSS AGC & C/N0 jamming/spoofing monitor backed by Android's
 * [LocationManager.registerGnssMeasurementsCallback].
 */
class GnssRfMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val gnssExecutor = Executors.newSingleThreadExecutor()
    private val analyzer = GnssInterferenceAnalyzer()

    private val _status = MutableStateFlow(GnssRfStatus())
    val status: StateFlow<GnssRfStatus> = _status.asStateFlow()

    private var registered = false

    private val callback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            val measurements = eventArgs.measurements
            if (measurements.isEmpty()) return

            val cn0Values = measurements
                .map { it.cn0DbHz }
                .filter { it.isFinite() && it > 0.0 }
                .sortedDescending()
            if (cn0Values.isEmpty()) return

            val meanCn0 = cn0Values.average()
            val top3Cn0 = cn0Values.take(3).average()

            val agcValues = mutableListOf<Double>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                for (agc in eventArgs.gnssAutomaticGainControls) {
                    val lvl = agc.levelDb
                    if (lvl.isFinite()) agcValues.add(lvl)
                }
            }
            if (agcValues.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                for (m in measurements) {
                    if (m.hasAutomaticGainControlLevelDb()) {
                        val lvl = m.automaticGainControlLevelDb
                        if (lvl.isFinite()) agcValues.add(lvl)
                    }
                }
            }

            val meanAgc = if (agcValues.isNotEmpty()) agcValues.average() else null
            _status.value = analyzer.pushEpoch(
                meanAgcDb = meanAgc,
                meanCn0DbHz = meanCn0,
                top3Cn0DbHz = top3Cn0,
                satelliteCount = cn0Values.size,
                timestampMs = System.currentTimeMillis()
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (registered) return
        val lm = locationManager ?: return
        val hasFine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) return

        registered = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.registerGnssMeasurementsCallback(gnssExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssMeasurementsCallback(callback, Handler(Looper.getMainLooper()))
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        if (!registered) return
        try {
            locationManager?.unregisterGnssMeasurementsCallback(callback)
        } catch (_: Exception) {
            // Ignore unregister errors
        }
        registered = false
    }

    fun resetBaseline() {
        analyzer.reset()
        _status.value = GnssRfStatus()
    }
}
