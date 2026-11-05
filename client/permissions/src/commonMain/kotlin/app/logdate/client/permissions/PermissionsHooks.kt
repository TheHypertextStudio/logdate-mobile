@file:Suppress("ktlint:standard:filename")

package app.logdate.client.permissions

import androidx.compose.runtime.Composable

@Composable
expect fun rememberNotificationPermissionState(): NotificationPermissionState

@Composable
expect fun rememberHealthConnectPermissionState(onRequestResult: (Boolean) -> Unit = {}): HealthConnectPermissionState

@Composable
expect fun rememberMediaLibraryPermissionState(includeVideos: Boolean = true): MediaLibraryPermissionState

@Composable
expect fun rememberCalendarPermissionState(): CalendarPermissionState

@Composable
expect fun rememberContactsPermissionState(): ContactsPermissionState

/**
 * Represents the state of notification permission.
 */
data class NotificationPermissionState(
    val hasPermission: Boolean,
    val shouldShowRationale: Boolean,
    val permissionRequested: Boolean,
    val isRequestInFlight: Boolean,
    val requestPermission: () -> Unit,
)

/**
 * Represents the state of the Health Connect sleep permission request flow.
 */
data class HealthConnectPermissionState(
    val hasPermission: Boolean,
    val permissionRequested: Boolean,
    val isRequestInFlight: Boolean,
    val requestPermission: () -> Unit,
    val refreshPermissionState: () -> Unit,
)

/**
 * Represents the state of photo and video library access.
 */
data class MediaLibraryPermissionState(
    val hasPermission: Boolean,
    val shouldShowRationale: Boolean,
    val permissionRequested: Boolean,
    val requestPermission: () -> Unit,
    val refreshRevision: Int = 0,
)

/**
 * Represents the state of read-only access to the user's device calendars. Used by the
 * calendar sync settings overview to render the right "grant access / sync now / open
 * settings" branch and to drive the runtime permission request.
 */
data class CalendarPermissionState(
    val hasPermission: Boolean,
    val shouldShowRationale: Boolean,
    val permissionRequested: Boolean,
    val requestPermission: () -> Unit,
)

/**
 * Represents the state of read-only access to the user's device contacts.
 */
data class ContactsPermissionState(
    val hasPermission: Boolean,
    val shouldShowRationale: Boolean,
    val permissionRequested: Boolean,
    val requestPermission: () -> Unit,
)
