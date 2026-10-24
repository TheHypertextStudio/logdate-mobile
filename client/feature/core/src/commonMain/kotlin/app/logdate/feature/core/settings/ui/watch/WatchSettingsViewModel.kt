package app.logdate.feature.core.settings.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.watch.SharedPreferenceKey
import app.logdate.client.domain.watch.WatchNotificationSettings
import app.logdate.client.domain.watch.WatchSettingsRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the watch settings hub and detail screens.
 *
 * Exposes the current watch connection state and notification settings, sends requests to the
 * watch, and records why the last one failed so the screens can say so.
 */
class WatchSettingsViewModel(
    private val connectionManager: WatchConnectionManager,
    private val settingsRepository: WatchSettingsRepository,
    peerSnapshots: WatchPeerSnapshotSource = WatchPeerSnapshotSource { flowOf(WatchPeerSnapshot.Inactive) },
    windowDays: Flow<Int> = flowOf(SharedPreferenceKey.WindowDays.default),
) : ViewModel() {
    val connectionState: StateFlow<WatchConnectionState> =
        connectionManager
            .observeConnectionState()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchConnectionState.Loading)

    /**
     * What the Watch page shows: null until the phone has looked for the watch.
     *
     * The watch's report starts as [WatchPeerSnapshot.Inactive], so the connection shows at once and a
     * Data Layer that is slow or unavailable cannot hold the page on its loading state.
     */
    val status: StateFlow<WatchStatus?> =
        combine(
            connectionState,
            peerSnapshots.observe().onStart { emit(WatchPeerSnapshot.Inactive) },
            windowDays,
        ) { connection, peer, days ->
            WatchStatusResolver.resolve(connection, peer, days)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val notificationSettings: StateFlow<WatchNotificationSettings> =
        settingsRepository
            .observeNotificationSettings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchNotificationSettings())

    private val _requestFailure = MutableStateFlow<WatchRequestFailure?>(null)

    /** Why the last Install or Open request didn't go through; cleared when the next one starts. */
    val requestFailure: StateFlow<WatchRequestFailure?> = _requestFailure.asStateFlow()

    private val _installRequestSent = MutableStateFlow(false)
    val installRequestSent: StateFlow<Boolean> = _installRequestSent.asStateFlow()

    fun beginAssociation() {
        viewModelScope.launch {
            try {
                connectionManager.beginAssociation()
            } catch (e: Exception) {
                Napier.e(e) { "Failed to begin watch association" }
            }
        }
    }

    fun requestSync() {
        viewModelScope.launch {
            try {
                connectionManager.requestSync()
            } catch (e: Exception) {
                Napier.e(e) { "Failed to request watch sync" }
            }
        }
    }

    fun installAppOnWatch() {
        _installRequestSent.value = false
        sendToWatch({ connectionManager.installAppOnWatch() }) { _installRequestSent.value = true }
    }

    fun openAppOnWatch() {
        sendToWatch(request = { connectionManager.openAppOnWatch() })
    }

    // Notification settings mutations

    fun setShowEntryNotifications(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setShowEntryNotifications(enabled)
            } catch (e: Exception) {
                Napier.e(e) { "Failed to update entry notifications setting" }
            }
        }
    }

    private fun sendToWatch(
        request: suspend () -> WatchRequestResult,
        onSent: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _requestFailure.value = null
            _requestFailure.value =
                when (val result = request()) {
                    WatchRequestResult.Sent -> {
                        onSent()
                        null
                    }

                    is WatchRequestResult.Failed -> {
                        result.reason
                    }
                }
        }
    }
}
