package com.example.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.dsp.AngleMath
import com.example.dsp.BayesianGridFilter
import com.example.dsp.Point2D
import com.example.dsp.UserPose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI

/**
 * Inertial Dead-Reckoning Engine locked to the 50m x 50m Cartesian arena.
 *
 * - Initializes user at (25.0m, 25.0m).
 * - Reads azimuth psi in [-pi, pi] from SensorManager.getOrientation()
 *   where 0 = North, +pi/2 = East (clockwise).
 * - Converts to Cartesian heading theta = wrap(pi/2 - wrap(psi - psi_offset)).
 * - On TYPE_STEP_DETECTOR:
 *       x_{t+1} = x_t + L_step * cos(theta)
 *       y_{t+1} = y_t + L_step * sin(theta)
 */
class ImuDeadReckoningEngine(context: Context) : SensorEventListener {

    companion object {
        const val START_X_METERS = 25.0
        const val START_Y_METERS = 25.0
        private const val MAX_TRAIL_POINTS = 500
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

    private val stepDetectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val hasRotationVector: Boolean
        get() = rotationVectorSensor != null || (accelSensor != null && magnetSensor != null)

    val hasStepDetector: Boolean
        get() = stepDetectorSensor != null

    private val lock = Any()
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val lastAccel = FloatArray(3)
    private val lastMagnet = FloatArray(3)
    private var hasAccel = false
    private var hasMagnet = false

    @Volatile
    private var stepLengthMeters: Double = 0.72

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
                sensorManager.registerListener(this, magnetSensor, SensorManager.SENSOR_DELAY_GAME)
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

    /**
     * Triggers a single dead-reckoning step using the exact Cartesian step equation.
     * Called automatically on TYPE_STEP_DETECTOR events.
     */
    fun applyStep() {
        synchronized(lock) {
            val current = _pose.value
            val (dx, dy) = AngleMath.stepDisplacement(current.headingRad, stepLengthMeters)
            val nextX = (current.xMeters + dx).coerceIn(0.0, BayesianGridFilter.ARENA_SIZE_METERS)
            val nextY = (current.yMeters + dy).coerceIn(0.0, BayesianGridFilter.ARENA_SIZE_METERS)
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
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                updateOrientation(orientationAngles[0].toDouble())
            }

            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccel, 0, 3)
                hasAccel = true
                if (hasMagnet &&
                    SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMagnet)
                ) {
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    updateOrientation(orientationAngles[0].toDouble())
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagnet, 0, 3)
                hasMagnet = true
                if (hasAccel &&
                    SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMagnet)
                ) {
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    updateOrientation(orientationAngles[0].toDouble())
                }
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                applyStep()
            }
        }
    }

    private fun updateOrientation(rawAzimuthPsiRad: Double) {
        if (!rawAzimuthPsiRad.isFinite()) return
        synchronized(lock) {
            val current = _pose.value
            val theta = AngleMath.azimuthToCartesianHeading(
                azimuthPsiRad = rawAzimuthPsiRad,
                headingOffsetRad = current.headingOffsetRad
            )
            _pose.value = current.copy(
                rawAzimuthRad = rawAzimuthPsiRad,
                headingRad = theta
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op; manual Zero Heading button allows immediate field calibration near metal/rebar
    }
}
