package app.logdate.wear.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.location.tracking.LocationTrackingManager
import app.logdate.client.permissions.PermissionManager
import app.logdate.client.permissions.PermissionType
import app.logdate.client.sync.SyncManager
import app.logdate.wear.sync.PhoneReachability
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * ViewModel backing the Wear OS Settings screen.
 *
 * The phone, the sync status and the location settings are each followed as they change. Only the
 * location permission is read again, on resume, because it can change in system settings without
 * any event reaching the app.
 */
class WearSettingsViewModel(
    private val syncManager: SyncManager,
    phoneReachability: PhoneReachability,
    private val locationSettingsRepository: LocationTrackingSettingsRepository,
    private val permissionManager: PermissionManager,
    private val locationTrackingManager: LocationTrackingManager,
    private val appVersion: String? = null,
) : ViewModel() {
    private val locationPermissionGranted = MutableStateFlow(permissionManager.isPermissionGranted(PermissionType.LOCATION))
    private val isSyncingNow = MutableStateFlow(false)

    val uiState: StateFlow<WearSettingsUiState> =
        combine(
            phoneReachability.observe(),
            syncManager.syncStatusFlow,
            locationSettingsRepository.observeSettings(),
            locationPermissionGranted,
            isSyncingNow,
        ) { phone, status, location, permissionGranted, syncingNow ->
            WearSettingsUiState(
                isPhoneConnected = phone != null,
                phoneName = phone?.displayName,
                lastSyncTime = status.lastSyncTime,
                pendingCount = status.pendingUploads,
                hasErrors = status.hasErrors,
                isSyncingNow = syncingNow,
                locationPermissionGranted = permissionGranted,
                autoTagJournalEntries = location.autoTrackForJournalEntries,
                backgroundLocationEnabled = location.backgroundTrackingEnabled,
                locationCaptureMode = location.captureMode,
                appVersion = appVersion,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WearSettingsUiState())

    /** Reads the location permission again; it may have changed in system settings. */
    fun onResume() {
        locationPermissionGranted.value = permissionManager.isPermissionGranted(PermissionType.LOCATION)
    }

    fun syncNow() {
        viewModelScope.launch {
            isSyncingNow.value = true
            try {
                syncManager.fullSync()
            } catch (e: Exception) {
                Napier.w("Sync Now failed", e)
            } finally {
                isSyncingNow.value = false
            }
        }
    }

    fun setBackgroundLocationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                locationSettingsRepository.setBackgroundTrackingEnabled(enabled)
                locationTrackingManager.startTracking()
            } catch (e: Exception) {
                Napier.w("Failed to update Wear background location setting", e)
            }
        }
    }

    fun setLocationCaptureMode(mode: LocationCaptureMode) {
        viewModelScope.launch {
            try {
                locationSettingsRepository.setCaptureMode(mode)
                locationTrackingManager.startTracking()
            } catch (e: Exception) {
                Napier.w("Failed to update Wear location capture mode", e)
            }
        }
    }

    fun openLocationPermissions() {
        permissionManager.openPermissionSettings()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

data class WearSettingsUiState(
    val isPhoneConnected: Boolean = false,
    val phoneName: String? = null,
    val lastSyncTime: Instant? = null,
    val pendingCount: Int = 0,
    val hasErrors: Boolean = false,
    val isSyncingNow: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val autoTagJournalEntries: Boolean = true,
    val backgroundLocationEnabled: Boolean = false,
    val locationCaptureMode: LocationCaptureMode = LocationCaptureMode.PASSIVE,
    val appVersion: String? = null,
)
