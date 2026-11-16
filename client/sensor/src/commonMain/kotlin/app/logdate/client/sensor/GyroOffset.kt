package app.logdate.client.sensor

/**
 * How fast the device is turning, in radians per second: [x] about the axis that runs across the
 * screen and [y] about the axis that runs up it.
 */
data class GyroOffset(
    val x: Float,
    val y: Float,
)
