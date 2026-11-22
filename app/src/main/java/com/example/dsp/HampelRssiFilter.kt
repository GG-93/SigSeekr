package com.example.dsp

import kotlin.math.abs
import kotlin.math.max

/**
 * Thread-safe 5-sample Hampel median filter for RSSI outlier rejection.
 *
 * Maintains a sliding window of up to [WINDOW_SIZE] (5) negative dBm samples.
 * Computes the window median `m` and Median Absolute Deviation
 * `MAD = median(|x_i - m|)`. Any incoming sample deviating from `m` by more than
 * `max(3.0 * 1.4826 * MAD, MIN_DEVIATION_THRESHOLD_DB)` is replaced by `m` so
 * single-packet multipath spikes cannot corrupt the Bayesian grid filter.
 *
 * Strictly guarantees output values remain in `[-127.0, -1.0] dBm`.
 */
class HampelRssiFilter(
    private val windowSize: Int = WINDOW_SIZE,
    private val sigmaMultiplier: Double = OUTLIER_SIGMA_MULTIPLIER
) {
    companion object {
        const val WINDOW_SIZE = 5
        const val OUTLIER_SIGMA_MULTIPLIER = 3.0
        const val MAD_SCALE_FACTOR = 1.4826
        const val MIN_DEVIATION_THRESHOLD_DB = 1.5

        fun medianOf(values: DoubleArray, count: Int): Double {
            if (count <= 0) return -100.0
            val copy = values.copyOf(count)
            copy.sort()
            val mid = count / 2
            return if (count % 2 == 1) {
                copy[mid]
            } else {
                0.5 * (copy[mid - 1] + copy[mid])
            }
        }
    }

    private val lock = Any()
    private val window = ArrayDeque<Double>(windowSize + 1)
    private val scratch = DoubleArray(windowSize)
    private val deviations = DoubleArray(windowSize)
    private var lastOutputDbm: Double? = null

    /**
     * Pushes a raw RSSI measurement (dBm) into the 5-sample window and returns
     * the Hampel-filtered RSSI strictly clamped to `[-127.0, -1.0] dBm`.
     */
    fun update(rawRssiDbm: Double): Double {
        val sanitized = RssiKalmanFilter.sanitizeNegativeDbm(rawRssiDbm)
        synchronized(lock) {
            window.addLast(sanitized)
            while (window.size > windowSize) {
                window.removeFirst()
            }

            val n = window.size
            for (i in 0 until n) {
                scratch[i] = window[i]
            }
            val med = medianOf(scratch, n)

            val output = if (n < 3) {
                sanitized
            } else {
                for (i in 0 until n) {
                    deviations[i] = abs(scratch[i] - med)
                }
                val mad = medianOf(deviations, n)
                val threshold = max(sigmaMultiplier * MAD_SCALE_FACTOR * mad, MIN_DEVIATION_THRESHOLD_DB)
                if (abs(sanitized - med) > threshold) {
                    med
                } else {
                    sanitized
                }
            }

            val clamped = RssiKalmanFilter.sanitizeNegativeDbm(output)
            lastOutputDbm = clamped
            return clamped
        }
    }

    fun lastFilteredDbm(): Double? = synchronized(lock) { lastOutputDbm }

    fun reset() {
        synchronized(lock) {
            window.clear()
            lastOutputDbm = null
        }
    }
}
