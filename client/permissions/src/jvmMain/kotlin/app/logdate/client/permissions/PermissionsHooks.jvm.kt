package app.logdate.client.permissions

import androidx.compose.runtime.Composable

@Composable
actual fun rememberNotificationPermissionState(): NotificationPermissionState =
    NotificationPermissionState(
        hasPermission = true,
        shouldShowRationale = false,
        permissionRequested = false,
        isRequestInFlight = false,
        requestPermission = {},
    )

@Composable
actual fun rememberHealthConnectPermissionState(onRequestResult: (Boolean) -> Unit): HealthConnectPermissionState =
    HealthConnectPermissionState(
        hasPermission = false,
        permissionRequested = false,
        isRequestInFlight = false,
        requestPermission = { onRequestResult(false) },
        refreshPermissionState = {},
    )

@Composable
actual fun rememberMediaLibraryPermissionState(includeVideos: Boolean): MediaLibraryPermissionState =
    MediaLibraryPermissionState(
        hasPermission = true,
        shouldShowRationale = false,
        permissionRequested = false,
        requestPermission = {},
    )

@Composable
actual fun rememberCalendarPermissionState(): CalendarPermissionState =
    CalendarPermissionState(
        hasPermission = false,
        shouldShowRationale = false,
        permissionRequested = false,
        requestPermission = {},
    )

@Composable
actual fun rememberContactsPermissionState(): ContactsPermissionState =
    ContactsPermissionState(
        hasPermission = false,
        shouldShowRationale = false,
        permissionRequested = false,
        requestPermission = {},
    )
