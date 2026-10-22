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
import kotlin.math.sqrt

/**
 * Camera elevation (degrees above the horizon, see [AimConfig]) at ~50 Hz.
 *
 * Prefers the game rotation vector (accelerometer + gyroscope, no magnetometer: fast and not thrown
 * off by speakers or steel furniture); falls back to the rotation vector, then to gravity alone.
 * Rotation about the camera axis (portrait vs landscape) does not change the elevation.
 */
class OrientationSensor(context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)

    val sensorName: String
        get() = chooseSensor()?.let { "${it.name} (${typeName(it.type)})" } ?: "none"

    val available: Boolean get() = chooseSensor() != null

    fun samples(): Flow<OrientationSample> = callbackFlow {
        val sensor = chooseSensor() ?: run {
            close()
            return@callbackFlow
        }
        val rotation = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val elevation = when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                        val g = sqrt(x * x + y * y + z * z).takeIf { it > 0.1f } ?: return
                        Math.toDegrees(asin((-z / g).coerceIn(-1f, 1f).toDouble())).toFloat()
                    }
                    else -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        // rotation[8] = the device Z axis' vertical component; the back camera looks along -Z.
                        Math.toDegrees(asin((-rotation[8]).coerceIn(-1f, 1f).toDouble())).toFloat()
                    }
                }
                trySend(OrientationSample(event.timestamp / 1_000_000, elevation))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { manager.unregisterListener(listener) }
    }

    private fun chooseSensor(): Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private fun typeName(type: Int) = when (type) {
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "game rotation vector"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation vector"
        Sensor.TYPE_GRAVITY -> "gravity"
        else -> "type $type"
    }
}
