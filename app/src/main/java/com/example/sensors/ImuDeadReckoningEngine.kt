package com.example.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.dsp.AngleMath
import com.example.dsp.BayesianGridFilter
import com.example.dsp.MagneticFieldStatus
import com.example.dsp.Point2D
import com.example.dsp.UserPose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Inertial Dead-Reckoning Engine + Ambient Magnetic Flux Anomaly Monitor.
 *
 * - Initializes user at (25.0m, 25.0m).
 * - Reads azimuth psi in [-pi, pi] from SensorManager.getOrientation()
 *   where 0 = North, +pi/2 = East (clockwise).
 * - Converts to Cartesian heading theta = wrap(pi/2 - wrap(psi - psi_offset)).
 * - On TYPE_STEP_DETECTOR:
 *       x_{t+1} = x_t + L_step * cos(theta)
 *       y_{t+1} = y_t + L_step * sin(theta)
 * - Monitors TYPE_MAGNETIC_FIELD total flux B = sqrt(Bx^2 + By^2 + Bz^2) (uT)
 *   and flags `isMagneticWarped = true` whenever B < 25 uT or B > 70 uT.
 */
class ImuDeadReckoningEngine(context: Context) : SensorEventListener {

    companion object {
        const val START_X_METERS = 25.0
        const val START_Y_METERS = 25.0
        const val MIN_NOMINAL_FLUX_UT = 25.0f
        const val MAX_NOMINAL_FLUX_UT = 70.0f
        const val MAGNETIC_DRIFT_THRESHOLD_UT = 6.0f
        private const val MAX_TRAIL_POINTS = 500

        fun computeMagneticStatus(
            bx: Float,
            by: Float,
            bz: Float,
            baselineUt: Float? = null,
            needsFigure8: Boolean = false
        ): MagneticFieldStatus {
            val flux = sqrt(bx * bx + by * by + bz * bz)
            if (!flux.isFinite()) {
                return MagneticFieldStatus(
                    fluxMicroTesla = 0f,
                    baselineFluxMicroTesla = baselineUt,
                    driftDeltaMicroTesla = 0f,
                    isMagneticWarped = true,
                    needsFigure8Calibration = needsFigure8,
                    hasSensorReading = true
                )
            }
            val driftDelta = if (baselineUt != null) kotlin.math.abs(flux - baselineUt) else 0f
            val warped = if (baselineUt != null) {
                driftDelta > MAGNETIC_DRIFT_THRESHOLD_UT
            } else {
                flux < MIN_NOMINAL_FLUX_UT || flux > MAX_NOMINAL_FLUX_UT
            }
            return MagneticFieldStatus(
                fluxMicroTesla = flux,
                baselineFluxMicroTesla = baselineUt,
                driftDeltaMicroTesla = driftDelta,
                isMagneticWarped = warped,
                needsFigure8Calibration = needsFigure8,
                hasSensorReading = true
            )
        }
    }

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val stepDetectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val hasRotationVector: Boolean
        get() = rotationVectorSensor != null || (accelSensor != null && magnetSensor != null)

    val hasStepDetector: Boolean
        get() = stepDetectorSensor != null

    val hasMagnetometer: Boolean
        get() = magnetSensor != null

    private val lock = Any()
    private val rotationMatrix = FloatArray(9)
    private val lastRotationMatrix = FloatArray(9).apply {
        this[0] = 1f; this[4] = 1f; this[8] = 1f
    }
    private val lastAccel = FloatArray(3)
    private val lastMagnet = FloatArray(3)
    private var hasRotationMatrix = false
    private var hasAccel = false
    private var hasMagnet = false
    private var smoothedFluxUt: Float? = null
    private var baselineFluxUt: Float? = null
    private var needsFigure8Calibration: Boolean = false
    private var lastGyroTimestampNs: Long = 0L

    @Volatile
    private var stepLengthMeters: Double = 0.72

    @Volatile
    private var screenOutwardMode: Boolean = false

    private var isStarted = false

    private val _pose = MutableStateFlow(
        UserPose(
            xMeters = START_X_METERS,
            yMeters = START_Y_METERS,
            headingRad = PI / 2.0,
            rawAzimuthRad = 0.0,
            headingOffsetRad = 0.0,
            stepCount = 0,
            trail = listOf(Point2D(START_X_METERS, START_Y_METERS))
        )
    )
    val pose: StateFlow<UserPose> = _pose.asStateFlow()

    private val _magneticStatus = MutableStateFlow(MagneticFieldStatus())
    val magneticStatus: StateFlow<MagneticFieldStatus> = _magneticStatus.asStateFlow()

    fun start() {
        synchronized(lock) {
            if (isStarted) return
            if (rotationVectorSensor != null) {
                sensorManager.registerListener(
                    this,
                    rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            } else if (accelSensor != null && magnetSensor != null) {
                sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
            }

            // Always monitor raw magnetometer flux B (uT) for ferromagnetic rebar/metal distortion
            if (magnetSensor != null) {
                sensorManager.registerListener(
                    this,
                    magnetSensor,
                    SensorManager.SENSOR_DELAY_UI
                )
            }

            if (gyroSensor != null) {
                lastGyroTimestampNs = 0L
                sensorManager.registerListener(
                    this,
                    gyroSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }

            if (stepDetectorSensor != null) {
                sensorManager.registerListener(
                    this,
                    stepDetectorSensor,
                    SensorManager.SENSOR_DELAY_FASTEST
                )
            }
            isStarted = true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!isStarted) return
            sensorManager.unregisterListener(this)
            isStarted = false
        }
    }

    fun updateStepLength(meters: Float) {
        stepLengthMeters = meters.toDouble().coerceIn(0.50, 1.00)
    }

    /**
     * Toggles manual +180 deg heading flip for placing the phone screen-outward
     * inside a breast pocket. Default (false) aligns upright forward heading with
     * rear camera (-Z, screen facing user).
     */
    fun setScreenOutwardMode(enabled: Boolean) {
        synchronized(lock) {
            if (screenOutwardMode == enabled) return
            screenOutwardMode = enabled
            if (hasRotationMatrix) {
                updateOrientationFromMatrixLocked(lastRotationMatrix)
            } else {
                val current = _pose.value
                val flippedAzimuth = AngleMath.wrapAngle(current.rawAzimuthRad + PI)
                val newTheta = AngleMath.azimuthToCartesianHeading(flippedAzimuth, current.headingOffsetRad)
                _pose.value = current.copy(
                    rawAzimuthRad = flippedAzimuth,
                    headingRad = newTheta,
                    screenOutwardMode = enabled
                )
            }
        }
    }

    /** Captures current ambient magnetic flux B_base (uT) as the clean local baseline. */
    fun snapshotMagneticBaseline() {
        synchronized(lock) {
            val currentFlux = smoothedFluxUt
            if (currentFlux != null && currentFlux.isFinite()) {
                baselineFluxUt = currentFlux
                _magneticStatus.value = _magneticStatus.value.copy(
                    baselineFluxMicroTesla = currentFlux,
                    driftDeltaMicroTesla = 0f,
                    isMagneticWarped = false
                )
            }
        }
    }

    /** Captures current raw azimuth as compass offset so physical facing aligns to Grid North (0 deg). */
    fun zeroHeading() {
        synchronized(lock) {
            val current = _pose.value
            val newOffset = current.rawAzimuthRad
            val newTheta = AngleMath.azimuthToCartesianHeading(current.rawAzimuthRad, newOffset)
            _pose.value = current.copy(
                headingOffsetRad = newOffset,
                headingRad = newTheta
            )
            snapshotMagneticBaseline()
        }
    }

    /** Clears manual compass bias back to raw magnetic North. */
    fun clearHeadingOffset() {
        synchronized(lock) {
            val current = _pose.value
            val newTheta = AngleMath.azimuthToCartesianHeading(current.rawAzimuthRad, 0.0)
            _pose.value = current.copy(
                headingOffsetRad = 0.0,
                headingRad = newTheta
            )
        }
    }

    /** Snaps user back to center (25.0m, 25.0m) and clears breadcrumb trail. */
    fun resetPoseToCenter() {
        synchronized(lock) {
            val current = _pose.value
            _pose.value = current.copy(
                xMeters = START_X_METERS,
                yMeters = START_Y_METERS,
                stepCount = 0,
                trail = listOf(Point2D(START_X_METERS, START_Y_METERS))
            )
        }
    }

    /** Clears stray breadcrumb trail while preserving current user coordinates. */
    fun clearTrail() {
        synchronized(lock) {
            val current = _pose.value
            _pose.value = current.copy(
                trail = listOf(Point2D(current.xMeters, current.yMeters))
            )
        }
    }

    /**
     * Triggers a single dead-reckoning step using the exact Cartesian step equation.
     * Called automatically on TYPE_STEP_DETECTOR events.
     */
    fun applyStep() {
        synchronized(lock) {
            val current = _pose.value
            val (dx, dy) = AngleMath.stepDisplacement(current.headingRad, stepLengthMeters)
            val nextX = current.xMeters + dx
            val nextY = current.yMeters + dy
            val updatedTrail = ArrayList<Point2D>(
                (current.trail.size + 1).coerceAtMost(MAX_TRAIL_POINTS)
            ).apply {
                val startIdx = (current.trail.size + 1 - MAX_TRAIL_POINTS).coerceAtLeast(0)
                for (i in startIdx until current.trail.size) {
                    add(current.trail[i])
                }
                add(Point2D(nextX, nextY))
            }
            _pose.value = current.copy(
                xMeters = nextX,
                yMeters = nextY,
                stepCount = current.stepCount + 1,
                trail = updatedTrail
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                synchronized(lock) {
                    updateOrientationFromMatrixLocked(rotationMatrix)
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccel, 0, 3)
                hasAccel = true
                if (rotationVectorSensor == null && hasMagnet &&
                    SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMagnet)
                ) {
                    synchronized(lock) {
                        updateOrientationFromMatrixLocked(rotationMatrix)
                    }
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagnet, 0, 3)
                hasMagnet = true

                // Monitor total magnetic flux B = sqrt(Bx^2 + By^2 + Bz^2) in uT
                val rawFlux = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                if (rawFlux.isFinite()) {
                    val prev = smoothedFluxUt
                    val filtered = if (prev == null) rawFlux else (prev * 0.75f + rawFlux * 0.25f)
                    smoothedFluxUt = filtered
                    val base = baselineFluxUt
                    val driftDelta = if (base != null) kotlin.math.abs(filtered - base) else 0f
                    val warped = if (base != null) {
                        driftDelta > MAGNETIC_DRIFT_THRESHOLD_UT
                    } else {
                        filtered < MIN_NOMINAL_FLUX_UT || filtered > MAX_NOMINAL_FLUX_UT
                    }
                    _magneticStatus.value = MagneticFieldStatus(
                        fluxMicroTesla = filtered,
                        baselineFluxMicroTesla = base,
                        driftDeltaMicroTesla = driftDelta,
                        isMagneticWarped = warped,
                        needsFigure8Calibration = needsFigure8Calibration,
                        hasSensorReading = true
                    )
                }

                if (rotationVectorSensor == null && hasAccel &&
                    SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMagnet)
                ) {
                    synchronized(lock) {
                        updateOrientationFromMatrixLocked(rotationMatrix)
                    }
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val ts = event.timestamp
                if (lastGyroTimestampNs != 0L && ts > lastGyroTimestampNs) {
                    val dtSec = (ts - lastGyroTimestampNs) * 1e-9
                    if (dtSec in 0.0..0.25) {
                        synchronized(lock) {
                            if (_magneticStatus.value.isMagneticWarped) {
                                val worldYawRate = AngleMath.computeWorldVerticalYawRate(
                                    wxRadPerSec = event.values[0],
                                    wyRadPerSec = event.values[1],
                                    wzRadPerSec = event.values[2],
                                    rotationMatrix = lastRotationMatrix
                                )
                                val dTheta = worldYawRate * dtSec
                                val current = _pose.value
                                val nextTheta = AngleMath.wrapAngle(current.headingRad + dTheta)
                                val nextRawAzimuth = AngleMath.wrapAngle(current.rawAzimuthRad - dTheta)
                                _pose.value = current.copy(
                                    headingRad = nextTheta,
                                    rawAzimuthRad = nextRawAzimuth,
                                    hasOrientationReading = true
                                )
                            }
                        }
                    }
                }
                lastGyroTimestampNs = ts
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                applyStep()
            }
        }
    }

    private fun updateOrientationFromMatrixLocked(matrix: FloatArray) {
        System.arraycopy(matrix, 0, lastRotationMatrix, 0, 9)
        hasRotationMatrix = true
        val orient = AngleMath.extractOrientationFromRotationMatrix(
            rotationMatrix = matrix,
            screenOutwardMode = screenOutwardMode
        )
        if (!orient.azimuthRad.isFinite()) return
        val current = _pose.value
        if (_magneticStatus.value.isMagneticWarped) {
            // Coast on pure tilt-compensated gyroscope yaw while magnetically drifted,
            // updating only pitch/roll/tilt-guard so steel rebar cannot twist the arrow.
            _pose.value = current.copy(
                pitchDeg = orient.pitchDeg,
                rollDeg = orient.rollDeg,
                isShieldTiltGuarded = orient.isTiltGuardTriggered,
                screenOutwardMode = screenOutwardMode,
                hasOrientationReading = true
            )
            return
        }
        val theta = AngleMath.azimuthToCartesianHeading(
            azimuthPsiRad = orient.azimuthRad,
            headingOffsetRad = current.headingOffsetRad
        )
        _pose.value = current.copy(
            rawAzimuthRad = orient.azimuthRad,
            headingRad = theta,
            pitchDeg = orient.pitchDeg,
            rollDeg = orient.rollDeg,
            isShieldTiltGuarded = orient.isTiltGuardTriggered,
            screenOutwardMode = screenOutwardMode,
            hasOrientationReading = true
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD || sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val lowAcc = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
            synchronized(lock) {
                needsFigure8Calibration = lowAcc
                _magneticStatus.value = _magneticStatus.value.copy(
                    needsFigure8Calibration = lowAcc
                )
            }
        }
    }
}
