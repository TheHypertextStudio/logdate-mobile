package app.logdate.client.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn

class AndroidGyroSensorProvider(
    coroutineScope: CoroutineScope,
    context: Context,
) : GyroSensorProvider {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    override val currentValue: SharedFlow<GyroOffset> =
        callbackFlow {
            if (gyroscope == null) {
                awaitClose()
                return@callbackFlow
            }

            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val (x, y) = event.values
                        trySend(GyroOffset(x, y))
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {
                        // No-op
                    }
                }

            sensorManager.registerListener(
                listener,
                gyroscope,
                // Motion on screen follows these readings, so they have to arrive about once a frame.
                SensorManager.SENSOR_DELAY_GAME,
            )

            awaitClose {
                sensorManager.unregisterListener(listener)
            }
        }.shareIn(
            coroutineScope,
            started = SharingStarted.WhileSubscribed(),
        )

    override fun cleanup() {
        // Sensor lifecycle is now tied to flow subscribers via callbackFlow.
        // No manual cleanup needed.
    }
}
