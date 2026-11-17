package app.logdate.feature.core.settings.ui.devices

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant

actual fun formatDeviceLastActive(timestamp: Instant): String? {
    if (timestamp == Instant.DISTANT_PAST) {
        return null
    }

    val formatter =
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
    return formatter.format(timestamp.toJavaTimeInstant())
}

private fun Instant.toJavaTimeInstant(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())
