@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.logdate.client.permissions

import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNAuthorizationStatusDenied
import platform.Contacts.CNAuthorizationStatusNotDetermined
import platform.Contacts.CNAuthorizationStatusRestricted
import platform.Contacts.CNContactStore
import platform.Contacts.CNEntityType
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMAuthorizationStatusDenied
import platform.CoreMotion.CMAuthorizationStatusNotDetermined
import platform.CoreMotion.CMAuthorizationStatusRestricted
import platform.CoreMotion.CMMotionActivityManager
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusNotDetermined
import platform.EventKit.EKAuthorizationStatusRestricted
import platform.EventKit.EKEntityType
import platform.EventKit.EKEventStore
import platform.Foundation.NSDate
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHAuthorizationStatusRestricted
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.registerForRemoteNotifications
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject

/**
 * Real iOS [PermissionManager] backed by AVFoundation, Photos, EventKit, Contacts, CoreLocation,
 * CoreMotion, and UserNotifications.
 *
 * iOS only presents the system permission prompt the first time per app install — once a user has
 * denied a permission, subsequent calls to the request APIs return immediately with the existing
 * status. UI built on top of this manager should detect that case and use [openAppSettings] to
 * route the user to the system settings screen.
 *
 * Notification status is the only authorization that is asynchronous-only on iOS. We refresh it on
 * construction and after every request so [isPermissionGranted] can answer synchronously from the
 * cached snapshot.
 */
class IosPermissionManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : PermissionManager {
    private val statusCache = MutableStateFlow<Map<PermissionType, PermissionStatus>>(emptyMap())
    private val notificationCenter = UNUserNotificationCenter.currentNotificationCenter()
    private val eventStore = EKEventStore()
    private val contactStore = CNContactStore()

    /**
     * Active CLLocationManager request — held for the lifetime of an in-flight location prompt so
     * the delegate is not deallocated before iOS calls it back.
     */
    private var pendingLocationRequest: LocationPermissionRequest? = null

    init {
        scope.launch { refreshNotificationStatusInternal() }
    }

    override fun isPermissionGranted(type: PermissionType): Boolean = currentStatus(type) == PermissionStatus.GRANTED

    override fun arePermissionsGranted(types: Set<PermissionType>): Boolean = types.all { isPermissionGranted(it) }

    override fun observePermissions(types: Set<PermissionType>): StateFlow<Map<PermissionType, PermissionStatus>> {
        val snapshot = types.associateWith { currentStatus(it) }
        statusCache.update { it + snapshot }
        if (PermissionType.NOTIFICATION in types) {
            scope.launch { refreshNotificationStatusInternal() }
        }
        return statusCache.asStateFlow()
    }

    override fun requestPermission(
        type: PermissionType,
        onResult: (PermissionResult) -> Unit,
    ) {
        when (type) {
            PermissionType.LOCATION -> requestLocation(onResult)
            PermissionType.CAMERA -> requestCamera(onResult)
            PermissionType.MICROPHONE -> requestMicrophone(onResult)
            PermissionType.STORAGE -> requestPhotos(onResult)
            PermissionType.NOTIFICATION -> requestNotifications(onResult)
            PermissionType.CALENDAR -> requestCalendar(onResult)
            PermissionType.CONTACTS -> requestContacts(onResult)
            PermissionType.BIOMETRIC -> deliver(type, PermissionStatus.GRANTED, onResult)
            PermissionType.ACTIVITY_RECOGNITION -> requestMotion(onResult)
        }
    }

    override fun requestPermissions(
        types: Set<PermissionType>,
        onResult: (List<PermissionResult>) -> Unit,
    ) {
        if (types.isEmpty()) {
            onResult(emptyList())
            return
        }
        val results = mutableListOf<PermissionResult>()
        var remaining = types.size
        types.forEach { type ->
            requestPermission(type) { result ->
                results += result
                remaining -= 1
                if (remaining == 0) onResult(results.toList())
            }
        }
    }

    override fun openAppSettings() {
        val url = NSURL(string = UIApplicationOpenSettingsURLString)
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }

    override fun openPermissionSettings() {
        // iOS exposes only the per-app settings screen; the system shows permissions inline there.
        openAppSettings()
    }

    override fun shouldShowRationale(type: PermissionType): Boolean = false

    /** Refreshes the cached notification status. Public for use by Compose hooks. */
    suspend fun refreshNotificationStatus(): PermissionStatus = refreshNotificationStatusInternal()

    private fun currentStatus(type: PermissionType): PermissionStatus =
        when (type) {
            PermissionType.LOCATION -> mapLocationStatus(CLLocationManager().authorizationStatus)
            PermissionType.CAMERA -> mapAVAuthorizationStatus(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo))
            PermissionType.MICROPHONE -> mapMicrophoneStatus(AVAudioSession.sharedInstance().recordPermission)
            PermissionType.STORAGE -> mapPhotosStatus(PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite))
            PermissionType.NOTIFICATION -> statusCache.value[PermissionType.NOTIFICATION] ?: PermissionStatus.UNKNOWN
            PermissionType.CALENDAR -> mapEventKitStatus(EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent))
            PermissionType.CONTACTS -> mapContactsStatus(CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts))
            PermissionType.BIOMETRIC -> PermissionStatus.GRANTED
            PermissionType.ACTIVITY_RECOGNITION -> mapMotionStatus(CMMotionActivityManager.authorizationStatus())
        }

    private fun requestLocation(onResult: (PermissionResult) -> Unit) {
        val existing = currentStatus(PermissionType.LOCATION)
        if (existing != PermissionStatus.DENIED ||
            CLLocationManager().authorizationStatus != kCLAuthorizationStatusNotDetermined
        ) {
            // Only kCLAuthorizationStatusNotDetermined is eligible for a fresh prompt; everything
            // else (including DENIED) reflects a final user decision and the caller must route to
            // settings.
            cacheAndDeliver(PermissionType.LOCATION, existing, onResult)
            return
        }
        val request =
            LocationPermissionRequest { status ->
                pendingLocationRequest = null
                cacheAndDeliver(PermissionType.LOCATION, status, onResult)
            }
        pendingLocationRequest = request
        request.start()
    }

    private fun requestCamera(onResult: (PermissionResult) -> Unit) {
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            val status = if (granted) PermissionStatus.GRANTED else PermissionStatus.PERMANENTLY_DENIED
            cacheAndDeliver(PermissionType.CAMERA, status, onResult)
        }
    }

    private fun requestMicrophone(onResult: (PermissionResult) -> Unit) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            val status = if (granted) PermissionStatus.GRANTED else PermissionStatus.PERMANENTLY_DENIED
            cacheAndDeliver(PermissionType.MICROPHONE, status, onResult)
        }
    }

    private fun requestPhotos(onResult: (PermissionResult) -> Unit) {
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { rawStatus ->
            cacheAndDeliver(PermissionType.STORAGE, mapPhotosStatus(rawStatus), onResult)
        }
    }

    private fun requestNotifications(onResult: (PermissionResult) -> Unit) {
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        notificationCenter.requestAuthorizationWithOptions(options) { granted, error ->
            if (error != null) {
                Napier.w("UNUserNotificationCenter authorization failed: ${error.localizedDescription}")
            }
            scope.launch {
                val status = refreshNotificationStatusInternal()
                val final =
                    if (status == PermissionStatus.UNKNOWN) {
                        if (granted) PermissionStatus.GRANTED else PermissionStatus.PERMANENTLY_DENIED
                    } else {
                        status
                    }
                if (final == PermissionStatus.GRANTED) {
                    // Asking iOS to deliver an APNs token now means the AppDelegate registration
                    // handler fires immediately on grant rather than waiting for the next launch.
                    UIApplication.sharedApplication.registerForRemoteNotifications()
                }
                cacheAndDeliver(PermissionType.NOTIFICATION, final, onResult)
            }
        }
    }

    private fun requestCalendar(onResult: (PermissionResult) -> Unit) {
        eventStore.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent) { granted, error ->
            if (error != null) {
                Napier.w("EKEventStore authorization failed: ${error.localizedDescription}")
            }
            val status = if (granted) PermissionStatus.GRANTED else PermissionStatus.PERMANENTLY_DENIED
            cacheAndDeliver(PermissionType.CALENDAR, status, onResult)
        }
    }

    private fun requestContacts(onResult: (PermissionResult) -> Unit) {
        contactStore.requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { granted, error ->
            if (error != null) {
                Napier.w("CNContactStore authorization failed: ${error.localizedDescription}")
            }
            val status = if (granted) PermissionStatus.GRANTED else PermissionStatus.PERMANENTLY_DENIED
            cacheAndDeliver(PermissionType.CONTACTS, status, onResult)
        }
    }

    private fun requestMotion(onResult: (PermissionResult) -> Unit) {
        // CoreMotion has no explicit request API — querying activity for the first time triggers
        // the permission dialog. We submit a one-shot historical query and inspect the resulting
        // authorization status.
        val manager = CMMotionActivityManager()
        val now = NSDate()
        manager.queryActivityStartingFromDate(
            start = now,
            toDate = now,
            toQueue = NSOperationQueue.mainQueue,
        ) { _, _ ->
            val status = mapMotionStatus(CMMotionActivityManager.authorizationStatus())
            cacheAndDeliver(PermissionType.ACTIVITY_RECOGNITION, status, onResult)
        }
    }

    private suspend fun refreshNotificationStatusInternal(): PermissionStatus {
        val status =
            kotlinx.coroutines.suspendCancellableCoroutine<PermissionStatus> { continuation ->
                notificationCenter.getNotificationSettingsWithCompletionHandler { settings ->
                    val mapped =
                        settings?.authorizationStatus?.let(::mapNotificationStatus)
                            ?: PermissionStatus.UNKNOWN
                    if (continuation.isActive) continuation.resumeWith(Result.success(mapped))
                }
            }
        statusCache.update { it + (PermissionType.NOTIFICATION to status) }
        return status
    }

    private fun cacheAndDeliver(
        type: PermissionType,
        status: PermissionStatus,
        onResult: (PermissionResult) -> Unit,
    ) {
        statusCache.update { it + (type to status) }
        deliver(type, status, onResult)
    }

    private fun deliver(
        type: PermissionType,
        status: PermissionStatus,
        onResult: (PermissionResult) -> Unit,
    ) {
        scope.launch { onResult(PermissionResult(type, status)) }
    }

    private inner class LocationPermissionRequest(
        private val onResolved: (PermissionStatus) -> Unit,
    ) : NSObject(),
        CLLocationManagerDelegateProtocol {
        private val manager = CLLocationManager()

        init {
            manager.delegate = this
        }

        fun start() {
            manager.requestWhenInUseAuthorization()
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            val status = manager.authorizationStatus
            if (status == kCLAuthorizationStatusNotDetermined) return
            manager.delegate = null
            onResolved(mapLocationStatus(status))
        }
    }
}

internal fun mapLocationStatus(status: Int): PermissionStatus =
    when (status) {
        kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> PermissionStatus.GRANTED
        kCLAuthorizationStatusDenied -> PermissionStatus.PERMANENTLY_DENIED
        kCLAuthorizationStatusRestricted -> PermissionStatus.UNAVAILABLE
        kCLAuthorizationStatusNotDetermined -> PermissionStatus.DENIED
        else -> PermissionStatus.UNKNOWN
    }

internal fun mapAVAuthorizationStatus(status: Long): PermissionStatus =
    when (status) {
        AVAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
        AVAuthorizationStatusDenied -> PermissionStatus.PERMANENTLY_DENIED
        AVAuthorizationStatusRestricted -> PermissionStatus.UNAVAILABLE
        AVAuthorizationStatusNotDetermined -> PermissionStatus.DENIED
        else -> PermissionStatus.UNKNOWN
    }

internal fun mapMicrophoneStatus(status: ULong): PermissionStatus =
    when (status) {
        AVAudioSessionRecordPermissionGranted -> PermissionStatus.GRANTED
        AVAudioSessionRecordPermissionDenied -> PermissionStatus.PERMANENTLY_DENIED
        AVAudioSessionRecordPermissionUndetermined -> PermissionStatus.DENIED
        else -> PermissionStatus.UNKNOWN
    }

internal fun mapPhotosStatus(status: Long): PermissionStatus =
    when (status) {
        PHAuthorizationStatusAuthorized, PHAuthorizationStatusLimited -> PermissionStatus.GRANTED
        PHAuthorizationStatusDenied -> PermissionStatus.PERMANENTLY_DENIED
        PHAuthorizationStatusRestricted -> PermissionStatus.UNAVAILABLE
        PHAuthorizationStatusNotDetermined -> PermissionStatus.DENIED
        else -> PermissionStatus.UNKNOWN
    }

internal fun mapEventKitStatus(status: Long): PermissionStatus =
    when (status) {
        EKAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
        EKAuthorizationStatusDenied -> PermissionStatus.PERMANENTLY_DENIED
        EKAuthorizationStatusRestricted -> PermissionStatus.UNAVAILABLE
        EKAuthorizationStatusNotDetermined -> PermissionStatus.DENIED
        else -> PermissionStatus.UNKNOWN
    }

internal fun mapContactsStatus(status: Long): PermissionStatus =
    when (status) {
        CNAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
        CNAuthorizationStatusDenied -> PermissionStatus.PERMANENTLY_DENIED
        CNAuthorizationStatusRestricted -> PermissionStatus.UNAVAILABLE
        CNAuthorizationStatusNotDetermined -> PermissionStatus.DENIED
        else -> PermissionStatus.UNKNOWN
    }

internal fun mapMotionStatus(status: Long): PermissionStatus =
    when (status) {
        CMAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
        CMAuthorizationStatusDenied -> PermissionStatus.PERMANENTLY_DENIED
        CMAuthorizationStatusRestricted -> PermissionStatus.UNAVAILABLE
        CMAuthorizationStatusNotDetermined -> PermissionStatus.DENIED
        else -> PermissionStatus.UNKNOWN
    }

internal fun mapNotificationStatus(status: Long): PermissionStatus =
    when (status) {
        UNAuthorizationStatusAuthorized,
        UNAuthorizationStatusProvisional,
        UNAuthorizationStatusEphemeral,
        -> PermissionStatus.GRANTED

        UNAuthorizationStatusDenied -> PermissionStatus.PERMANENTLY_DENIED

        UNAuthorizationStatusNotDetermined -> PermissionStatus.DENIED

        else -> PermissionStatus.UNKNOWN
    }
