package app.logdate.feature.core.settings.ui.devices

import kotlin.time.Instant

/**
 * Formats the day of a device's last activity as a medium-length date, such as "Oct 16, 2026", or
 * returns `null` when the device has never reported one.
 */
expect fun formatDeviceLastActive(timestamp: Instant): String?
