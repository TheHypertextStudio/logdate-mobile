@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.logdate.client.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import platform.Contacts.CNContactStore
import platform.Contacts.CNEntityType
import platform.EventKit.EKEntityType
import platform.EventKit.EKEventStore
import platform.HealthKit.HKAuthorizationStatusSharingAuthorized
import platform.HealthKit.HKCategoryTypeIdentifierSleepAnalysis
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectType
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHPhotoLibrary
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter

@Composable
actual fun rememberNotificationPermissionState(): NotificationPermissionState {
    var status by remember { mutableStateOf(PermissionStatus.UNKNOWN) }
    var permissionRequested by remember { mutableStateOf(false) }
    var isRequestInFlight by remember { mutableStateOf(false) }
    var refreshNonce by remember { mutableStateOf(0) }
    val center = remember { UNUserNotificationCenter.currentNotificationCenter() }

    LaunchedEffect(refreshNonce) {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val raw = settings?.authorizationStatus ?: return@getNotificationSettingsWithCompletionHandler
            status = mapNotificationStatus(raw)
        }
    }

    return NotificationPermissionState(
        hasPermission = status == PermissionStatus.GRANTED,
        shouldShowRationale = status == PermissionStatus.PERMANENTLY_DENIED,
        permissionRequested = permissionRequested,
        isRequestInFlight = isRequestInFlight,
        requestPermission = {
            isRequestInFlight = true
            val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
            center.requestAuthorizationWithOptions(options) { _, error ->
                if (error != null) {
                    Napier.w("UNUserNotificationCenter authorization failed: ${error.localizedDescription}")
                }
                permissionRequested = true
                isRequestInFlight = false
                refreshNonce += 1
            }
        },
    )
}

@Composable
actual fun rememberHealthConnectPermissionState(onRequestResult: (Boolean) -> Unit): HealthConnectPermissionState {
    val requestResult by rememberUpdatedState(onRequestResult)
    val scope = rememberCoroutineScope()
    val healthStore = remember { HKHealthStore() }
    val sleepType = remember { HKObjectType.categoryTypeForIdentifier(HKCategoryTypeIdentifierSleepAnalysis) }
    val healthAvailable = remember { HKHealthStore.isHealthDataAvailable() && sleepType != null }

    var hasPermission by remember { mutableStateOf(false) }
    var permissionRequested by remember { mutableStateOf(false) }
    var isRequestInFlight by remember { mutableStateOf(false) }
    var refreshNonce by remember { mutableStateOf(0) }

    // HealthKit deliberately does not expose read-permission status to apps to prevent
    // probing — `authorizationStatusForType` reflects share-write authorization only.
    // Treat write-authorized as "permission granted" for the purpose of the onboarding gate;
    // the actual sleep query will return no data if the user denied read access.
    LaunchedEffect(refreshNonce) {
        hasPermission =
            healthAvailable &&
            sleepType != null &&
            healthStore.authorizationStatusForType(sleepType) == HKAuthorizationStatusSharingAuthorized
    }

    return HealthConnectPermissionState(
        hasPermission = hasPermission,
        permissionRequested = permissionRequested,
        isRequestInFlight = isRequestInFlight,
        requestPermission = {
            if (!healthAvailable || sleepType == null) {
                permissionRequested = true
                requestResult(false)
                return@HealthConnectPermissionState
            }
            isRequestInFlight = true
            healthStore.requestAuthorizationToShareTypes(
                typesToShare = null,
                readTypes = setOf(sleepType),
            ) { _, error ->
                if (error != null) {
                    Napier.w("HealthKit authorization failed: ${error.localizedDescription}")
                }
                scope.launch {
                    permissionRequested = true
                    isRequestInFlight = false
                    hasPermission = healthStore.authorizationStatusForType(sleepType) == HKAuthorizationStatusSharingAuthorized
                    requestResult(hasPermission)
                    refreshNonce += 1
                }
            }
        },
        refreshPermissionState = {
            refreshNonce += 1
        },
    )
}

@Composable
actual fun rememberMediaLibraryPermissionState(includeVideos: Boolean): MediaLibraryPermissionState {
    var status by remember {
        mutableStateOf(mapPhotosStatus(PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)))
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var refreshNonce by remember { mutableStateOf(0) }

    LaunchedEffect(refreshNonce) {
        status = mapPhotosStatus(PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite))
    }

    return MediaLibraryPermissionState(
        hasPermission = status == PermissionStatus.GRANTED,
        shouldShowRationale = status == PermissionStatus.PERMANENTLY_DENIED,
        permissionRequested = permissionRequested,
        refreshRevision = refreshNonce,
        requestPermission = {
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { rawStatus ->
                status = mapPhotosStatus(rawStatus)
                permissionRequested = true
                refreshNonce += 1
            }
        },
    )
}

@Composable
actual fun rememberCalendarPermissionState(): CalendarPermissionState {
    val eventStore = remember { EKEventStore() }
    var status by remember {
        mutableStateOf(mapEventKitStatus(EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)))
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var refreshNonce by remember { mutableStateOf(0) }

    LaunchedEffect(refreshNonce) {
        status = mapEventKitStatus(EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent))
    }

    return CalendarPermissionState(
        hasPermission = status == PermissionStatus.GRANTED,
        shouldShowRationale = status == PermissionStatus.PERMANENTLY_DENIED,
        permissionRequested = permissionRequested,
        requestPermission = {
            eventStore.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent) { _, error ->
                if (error != null) {
                    Napier.w("EKEventStore authorization failed: ${error.localizedDescription}")
                }
                permissionRequested = true
                refreshNonce += 1
            }
        },
    )
}

@Composable
actual fun rememberContactsPermissionState(): ContactsPermissionState {
    val contactStore = remember { CNContactStore() }
    var status by remember {
        mutableStateOf(mapContactsStatus(CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts)))
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var refreshNonce by remember { mutableStateOf(0) }

    LaunchedEffect(refreshNonce) {
        status = mapContactsStatus(CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts))
    }

    return ContactsPermissionState(
        hasPermission = status == PermissionStatus.GRANTED,
        shouldShowRationale = status == PermissionStatus.PERMANENTLY_DENIED,
        permissionRequested = permissionRequested,
        requestPermission = {
            contactStore.requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { _, error ->
                if (error != null) {
                    Napier.w("CNContactStore authorization failed: ${error.localizedDescription}")
                }
                permissionRequested = true
                refreshNonce += 1
            }
        },
    )
}
