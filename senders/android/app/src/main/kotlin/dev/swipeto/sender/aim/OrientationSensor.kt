package dev.swipeto.sender.aim

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Camera elevation (degrees above the horizon, see [AimConfig]) at ~50 Hz.
 *
 * Prefers the game rotation vector (accelerometer + gyroscope, no magnetometer: fast and not thrown
 * off by speakers or steel furniture); falls back to the rotation vector, then the gravity sensor,
 * and finally the raw accelerometer through a low-pass filter. Many budget phones (the Galaxy A15
 * among them) have no gyroscope and so none of the first three. Rotation about the camera axis
 * (portrait vs landscape) does not change the elevation.
 */
class OrientationSensor(context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)

    val sensorName: String
        get() = chooseSensor()?.let { "${it.name} (${typeName(it.type)})" } ?: "none"

    val available: Boolean get() = chooseSensor() != null

    /** Whether headings will be reported (a magnetometer is present). */
    val hasCompass: Boolean get() = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null

    fun samples(): Flow<OrientationSample> = callbackFlow {
        val sensor = chooseSensor() ?: run {
            close()
            return@callbackFlow
        }
        val rotation = FloatArray(9)
        val gravity = FloatArray(3)
        var lastAccelNs = 0L
        // Compass: magnetometer + a slower-filtered gravity, both only for the heading.
        val compassGravity = FloatArray(3)
        val magnetic = FloatArray(3)
        var hasCompassGravity = false
        var hasMagnetic = false
        val compassRotation = FloatArray(9)
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        fun heading(): Float? {
            if (!hasCompassGravity || !hasMagnetic) return null
            if (!SensorManager.getRotationMatrix(compassRotation, null, compassGravity, magnetic)) return null
            // World direction of the back camera (-Z): east = -R[2], north = -R[5].
            val east = -compassRotation[2]
            val north = -compassRotation[5]
            // Pointing almost straight down, the horizontal direction is meaningless.
            if (east * east + north * north < 0.04f) return null
            return Compass.normalize(Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat())
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        for (i in 0..2) magnetic[i] = if (hasMagnetic) magnetic[i] + 0.2f * (event.values[i] - magnetic[i]) else event.values[i]
                        hasMagnetic = true
                        return
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        for (i in 0..2) compassGravity[i] = if (hasCompassGravity) compassGravity[i] + 0.1f * (event.values[i] - compassGravity[i]) else event.values[i]
                        hasCompassGravity = true
                        if (event.sensor != sensor) return
                    }
                }
                val elevation = when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> elevationFromGravity(event.values[0], event.values[1], event.values[2]) ?: return
                    Sensor.TYPE_ACCELEROMETER -> {
                        // Low-pass the raw accelerometer to separate gravity from hand motion.
                        val dt = if (lastAccelNs == 0L) 0f else (event.timestamp - lastAccelNs) / 1e9f
                        lastAccelNs = event.timestamp
                        val alpha = if (dt <= 0f) 1f else dt / (ACCEL_FILTER_SECONDS + dt)
                        for (i in 0..2) gravity[i] += alpha * (event.values[i] - gravity[i])
                        elevationFromGravity(gravity[0], gravity[1], gravity[2]) ?: return
                    }
                    else -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        // rotation[8] = the device Z axis' vertical component; the back camera looks along -Z.
                        Math.toDegrees(asin((-rotation[8]).coerceIn(-1f, 1f).toDouble())).toFloat()
                    }
                }
                trySend(OrientationSample(event.timestamp / 1_000_000, elevation, heading()))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (magnetometer != null) {
            manager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_GAME)
            if (accelerometer != null && accelerometer != sensor) manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
        awaitClose { manager.unregisterListener(listener) }
    }

    private fun chooseSensor(): Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private fun typeName(type: Int) = when (type) {
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "game rotation vector"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation vector"
        Sensor.TYPE_GRAVITY -> "gravity"
        Sensor.TYPE_ACCELEROMETER -> "accelerometer, filtered"
        else -> "type $type"
    }

    private companion object {
        /** Low-pass time constant for the accelerometer fallback: smooth enough, still under a dwell. */
        const val ACCEL_FILTER_SECONDS = 0.08f

        /** Elevation of the back camera (-Z) given the "up" vector a resting accelerometer reports. */
        fun elevationFromGravity(x: Float, y: Float, z: Float): Float? {
            val g = sqrt(x * x + y * y + z * z).takeIf { it > 0.1f } ?: return null
            return Math.toDegrees(asin((-z / g).coerceIn(-1f, 1f).toDouble())).toFloat()
        }
    }
}
