package app.logdate.wear.v2.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.permissions.PermissionManager
import app.logdate.client.permissions.PermissionType
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.datalayer.v2.WearProtocolGate
import app.logdate.wear.reminders.ReminderPermissions
import app.logdate.wear.reminders.WatchReminders
import app.logdate.wear.sync.PhoneReachability
import app.logdate.wear.v2.haptics.HapticsPreference
import app.logdate.wear.v2.haptics.HapticsPreferenceStore
import app.logdate.wear.v2.phone.HandoffResult
import app.logdate.wear.v2.phone.PhoneHandoff
import app.logdate.wear.v2.phone.PhoneLink
import app.logdate.wear.v2.phone.PhoneLinkRepository
import app.logdate.wear.v2.today.HandoffState
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the settings section shows.
 *
 * @property places Captures save the place: the setting is on and location access is granted.
 * @property phoneSettingsNodeId The phone "More on your phone" opens, or null while it cannot.
 */
data class WatchSettingsUiState(
    val status: WatchStatus = WatchStatus.Healthy,
    val haptics: HapticsPreference = HapticsPreference.ALL,
    val places: Boolean = false,
    val phoneSettingsNodeId: String? = null,
    val handoff: HandoffState = HandoffState.IDLE,
    val retrying: Boolean = false,
    val reminders: RemindersSetting = RemindersSetting(),
    val connection: WatchConnectionDetails = WatchConnectionDetails(),
)

/** The reminders switch, and whether workout monitoring actually registered. */
data class RemindersSetting(
    val enabled: Boolean = false,
    val afterWorkouts: Boolean = false,
)

/**
 * The settings at the end of the moment list. Every input is followed as it changes; location and
 * physical activity access are read again on resume, since they change in system settings without an
 * event.
 */
class WatchSettingsViewModel(
    private val syncManager: SyncManager,
    phoneReachability: PhoneReachability,
    private val haptics: HapticsPreferenceStore,
    private val locationSettings: LocationTrackingSettingsRepository,
    private val permissionManager: PermissionManager,
    phoneLinks: PhoneLinkRepository,
    protocolGate: WearProtocolGate,
    private val phoneHandoff: PhoneHandoff,
    private val reminders: WatchReminders,
    private val reminderPermissions: ReminderPermissions,
    connectionDetails: Flow<WatchConnectionDetails> = flowOf(WatchConnectionDetails()),
) : ViewModel() {
    private val locationGranted = MutableStateFlow(permissionManager.isPermissionGranted(PermissionType.LOCATION))
    private val notificationsGranted = MutableStateFlow(reminderPermissions.notificationsGranted())
    private val handoff = MutableStateFlow(HandoffState.IDLE)
    private val retrying = MutableStateFlow(false)

    private val status = combine(syncManager.syncStatusFlow, phoneReachability.observe(), ::watchStatusOf)
    private val connectionStatus =
        combine(status, connectionDetails, syncManager.syncStatusFlow) { status, details, sync ->
            status to details.copy(pending = sync.pendingUploads, lastSync = sync.lastSyncTime)
        }
    private val places =
        combine(locationSettings.observeSettings(), locationGranted) { settings, granted -> settings.autoTrackForJournalEntries && granted }
    private val phoneSettingsNode =
        combine(protocolGate.isFeatureActive(WearFeatures.PHONE_SETTINGS_LINK), phoneLinks.observe()) { active, link ->
            (link as? PhoneLink.Connected)?.nodeId?.takeIf { active }
        }
    private val remindersSetting =
        combine(reminders.enabled, reminders.workoutsActive, notificationsGranted) { on, workouts, notifications ->
            RemindersSetting(enabled = on && notifications, afterWorkouts = on && notifications && workouts)
        }

    val uiState: StateFlow<WatchSettingsUiState> =
        combine(
            connectionStatus,
            haptics.preference,
            places,
            phoneSettingsNode,
            combine(handoff, retrying, remindersSetting, ::Triple),
        ) { syncStatus, hapticsChoice, placesOn, node, (handoffState, isRetrying, remindersState) ->
            WatchSettingsUiState(
                status = syncStatus.first,
                connection = syncStatus.second,
                haptics = hapticsChoice,
                places = placesOn,
                phoneSettingsNodeId = node,
                handoff = handoffState,
                retrying = isRetrying,
                reminders = remindersState,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WatchSettingsUiState())

    fun setHaptics(preference: HapticsPreference) = haptics.set(preference)

    fun setPlaces(enabled: Boolean) {
        viewModelScope.launch {
            try {
                locationSettings.updateSettings(locationSettings.getSettings().copy(autoTrackForJournalEntries = enabled))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Couldn't turn places ${if (enabled) "on" else "off"} on the watch", e)
            }
        }
    }

    /** Whether turning places on first needs location access. */
    fun placesNeedPermission(): Boolean = !permissionManager.isPermissionGranted(PermissionType.LOCATION)

    fun onLocationPermissionResult(granted: Boolean) {
        locationGranted.value = granted
        if (granted) setPlaces(true)
    }

    /** Whether turning reminders on first needs notifications or physical activity access. */
    fun remindersNeedPermissions(): Boolean = !canRemind() || !reminderPermissions.activityRecognitionGranted()

    /** After the permission request: reminders turn on once they can work, and stay off otherwise. */
    fun onReminderPermissionsResult() {
        notificationsGranted.value = reminderPermissions.notificationsGranted()
        if (canRemind()) setReminders(true)
    }

    fun setReminders(enabled: Boolean) {
        viewModelScope.launch {
            try {
                reminders.setEnabled(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Couldn't turn reminders ${if (enabled) "on" else "off"} on the watch", e)
            }
        }
    }

    /** Reads location and physical activity access again; either may have changed in system settings. */
    fun onResume() {
        locationGranted.value = permissionManager.isPermissionGranted(PermissionType.LOCATION)
        notificationsGranted.value = reminderPermissions.notificationsGranted()
        viewModelScope.launch {
            try {
                reminders.restore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.w("Could not refresh watch reminders after permissions changed", e)
            }
        }
    }

    /** Sends what is waiting again, once at a time. */
    fun retrySync() {
        if (retrying.value) return
        retrying.value = true
        viewModelScope.launch {
            try {
                syncManager.fullSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.w("Sending moments to the phone again failed", e)
            } finally {
                retrying.value = false
            }
        }
    }

    /** Opens the phone's Watch page on the phone. */
    fun openPhoneSettings() {
        val node = uiState.value.phoneSettingsNodeId ?: return
        if (handoff.value == HandoffState.SENDING) return
        handoff.value = HandoffState.SENDING
        viewModelScope.launch {
            handoff.value =
                when (phoneHandoff.open(PHONE_WATCH_SETTINGS_LINK, node)) {
                    HandoffResult.SENT -> HandoffState.SENT
                    HandoffResult.FAILED -> HandoffState.FAILED
                }
        }
    }

    fun dismissHandoff() {
        handoff.value = HandoffState.IDLE
    }

    /** Evening reminders need notifications; workouts additionally need activity access. */
    private fun canRemind(): Boolean = reminderPermissions.notificationsGranted()

    companion object {
        const val PHONE_WATCH_SETTINGS_LINK = "studio.hypertext.logdate://settings/watch"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
