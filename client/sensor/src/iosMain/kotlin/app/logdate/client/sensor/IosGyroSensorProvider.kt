package app.logdate.client.sensor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import platform.CoreMotion.CMMotionManager
import platform.CoreMotion.CMRotationRate
import platform.Foundation.NSOperationQueue

/**
 * iOS implementation of [GyroSensorProvider] using CoreMotion.
 */
@Suppress("CAST_NEVER_SUCCEEDS")
@OptIn(ExperimentalForeignApi::class)
class IosGyroSensorProvider(
    coroutineScope: CoroutineScope,
) : GyroSensorProvider {
    private val motionManager = CMMotionManager()
    private val operationQueue = NSOperationQueue.mainQueue
    private val _currentValue = MutableSharedFlow<GyroOffset>()

    init {
        if (motionManager.gyroAvailable) {
            // Motion on screen follows these readings, so they have to arrive about once a frame.
            motionManager.gyroUpdateInterval = 1.0 / 60.0

            // Start gyroscope updates
            motionManager.startGyroUpdatesToQueue(operationQueue) { gyroData, error ->
                if (error != null) {
                    // Error occurred, do nothing
                    return@startGyroUpdatesToQueue
                }

                if (gyroData != null) {
                    // TODO: Determine if this works according to the documentation
                    // Some weird stuff happens here due to Kotlin interop with Objective-C
                    val rotationRate = gyroData.rotationRate as CMRotationRate
                    val xValue = rotationRate.x.toFloat()
                    val yValue = rotationRate.y.toFloat()
                    _currentValue.tryEmit(GyroOffset(xValue, yValue))
                }
            }
        }
    }

    override val currentValue: SharedFlow<GyroOffset> =
        _currentValue.shareIn(
            coroutineScope,
            started = SharingStarted.WhileSubscribed(),
        )

    override fun cleanup() {
        if (motionManager.gyroActive) {
            motionManager.stopGyroUpdates()
        }
    }
}
