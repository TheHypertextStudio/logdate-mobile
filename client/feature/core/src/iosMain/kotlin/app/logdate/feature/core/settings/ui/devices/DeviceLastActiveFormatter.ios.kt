package app.logdate.feature.core.settings.ui.devices

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.time.Instant

actual fun formatDeviceLastActive(timestamp: Instant): String? {
    if (timestamp == Instant.DISTANT_PAST) {
        return null
    }

    val formatter =
        NSDateFormatter().apply {
            dateStyle = NSDateFormatterMediumStyle
            timeStyle = NSDateFormatterNoStyle
        }

    val date = NSDate.dateWithTimeIntervalSince1970(timestamp.epochSeconds.toDouble())
    return formatter.stringFromDate(date)
}
