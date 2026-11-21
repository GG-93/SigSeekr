package com.example.sensors

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Native offline Geiger proximity feedback using Android Vibrator/VibratorManager
 * and ToneGenerator (zero external MP3/audio assets).
 *
 * Smoothly ramps pulse frequency from:
 *   - 1.0 Hz at estimated distance >= 15.0 m
 *   - 12.0 Hz at estimated distance <= 1.0 m
 */
class GeigerFeedbackEngine(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val MIN_FREQ_HZ = 1.0f
        const val MAX_FREQ_HZ = 12.0f
        const val FAR_DISTANCE_M = 15.0f
        const val NEAR_DISTANCE_M = 1.0f

        /** Maps estimated target distance (meters) smoothly to click cadence in [1.0, 12.0] Hz. */
        fun distanceToFrequencyHz(distanceMeters: Float): Float {
            if (!distanceMeters.isFinite() || distanceMeters >= FAR_DISTANCE_M) return MIN_FREQ_HZ
            if (distanceMeters <= NEAR_DISTANCE_M) return MAX_FREQ_HZ
            val normalizedProximity = ((FAR_DISTANCE_M - distanceMeters) / (FAR_DISTANCE_M - NEAR_DISTANCE_M))
                .coerceIn(0f, 1f)
            // Smooth quadratic ramp gives crisp resolution during final 5m -> 1m approach
            return MIN_FREQ_HZ + (MAX_FREQ_HZ - MIN_FREQ_HZ) * (normalizedProximity * normalizedProximity)
        }
    }

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }

    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var hasActiveTarget: Boolean = false

    @Volatile
    private var currentDistanceMeters: Float = FAR_DISTANCE_M

    private var pulseJob: Job? = null

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
        refreshLoop()
    }

    fun updateTargetState(locked: Boolean, estimatedDistanceMeters: Float) {
        hasActiveTarget = locked
        if (estimatedDistanceMeters.isFinite() && estimatedDistanceMeters > 0f) {
            currentDistanceMeters = estimatedDistanceMeters
        }
        refreshLoop()
    }

    fun currentPulseRateHz(): Float {
        return if (enabled && hasActiveTarget) {
            distanceToFrequencyHz(currentDistanceMeters)
        } else {
            0f
        }
    }

    private fun refreshLoop() {
        val shouldRun = enabled && hasActiveTarget
        if (shouldRun && pulseJob?.isActive != true) {
            ensureToneGenerator()
            pulseJob = scope.launch(Dispatchers.Default) {
                while (isActive && enabled && hasActiveTarget) {
                    val freqHz = distanceToFrequencyHz(currentDistanceMeters)
                    emitClick(freqHz)
                    val intervalMs = (1000.0f / freqHz).toLong().coerceIn(80L, 1000L)
                    delay(intervalMs)
                }
            }
        } else if (!shouldRun) {
            pulseJob?.cancel()
            pulseJob = null
        }
    }

    private fun ensureToneGenerator() {
        if (toneGenerator == null) {
            toneGenerator = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 75)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun emitClick(freqHz: Float) {
        // Crisp 18ms audio tick
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 18)
        } catch (_: Exception) {
            // Audio focus or hardware audio unavailable
        }

        // Crisp haptic tick scaling subtly in amplitude as target approaches
        val vib = vibrator ?: return
        try {
            if (!vib.hasVibrator()) return
            val amplitude = (90 + ((freqHz - MIN_FREQ_HZ) / (MAX_FREQ_HZ - MIN_FREQ_HZ)) * 165)
                .toInt()
                .coerceIn(80, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(18L, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(18L)
            }
        } catch (_: Exception) {
            // Ignore haptic failure on devices without vibrator
        }
    }

    fun release() {
        enabled = false
        pulseJob?.cancel()
        pulseJob = null
        try {
            toneGenerator?.release()
        } catch (_: Exception) {
            // Ignore release error
        }
        toneGenerator = null
    }
}
