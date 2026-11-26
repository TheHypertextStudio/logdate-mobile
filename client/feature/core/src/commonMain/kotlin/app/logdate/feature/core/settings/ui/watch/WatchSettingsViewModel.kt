package app.logdate.feature.core.settings.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.watch.SharedPreferenceKey
import app.logdate.client.domain.watch.WatchNotificationSettings
import app.logdate.client.domain.watch.WatchSettingsRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
@OptIn(ExperimentalCoroutinesApi::class)
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
        connectionState
            .flatMapLatest { connection ->
                val peer = connection.nodeId?.let(peerSnapshots::observeFor) ?: peerSnapshots.observe()
                combine(peer.onStart { emit(WatchPeerSnapshot.Inactive) }, windowDays) { snapshot, days ->
                    WatchStatusResolver.resolve(connection, snapshot, days)
                }
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

    private val _sendingRequest = MutableStateFlow(false)
    val sendingRequest: StateFlow<Boolean> = _sendingRequest.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        viewModelScope.launch {
            var previousNodeId: String? = null
            var previousStateType: kotlin.reflect.KClass<out WatchConnectionState>? = null
            connectionState.collect { current ->
                val nodeChanged = previousNodeId != null && current.nodeId != previousNodeId
                val resolvedToDifferentPhase =
                    previousStateType != current::class &&
                        (
                            current is WatchConnectionState.Connected ||
                                current is WatchConnectionState.NeedsAssociation ||
                                current is WatchConnectionState.NoPairedWatch
                        )
                if (nodeChanged || resolvedToDifferentPhase) {
                    _installRequestSent.value = false
                    _requestFailure.value = null
                }
                previousNodeId = current.nodeId
                previousStateType = current::class
            }
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                connectionManager.refresh()
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun clearRequestFailure() {
        _requestFailure.value = null
    }

    fun clearInstallRequestSent() {
        _installRequestSent.value = false
    }

    fun selectWatch(nodeId: String) {
        connectionManager.selectWatch(nodeId)
    }

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

    fun confirmMissingAppAndInstall(nodeId: String) {
        val current = connectionState.value
        if (current !is WatchConnectionState.AppUnknown || current.nodeId != nodeId || current.knownInstalled) {
            refresh()
            return
        }
        _installRequestSent.value = false
        sendToWatch({ connectionManager.confirmMissingAppAndInstall(nodeId) }) { _installRequestSent.value = true }
    }

    fun installAppOnWatch() {
        _installRequestSent.value = false
        sendToWatch({ connectionManager.installAppOnWatch() }) { _installRequestSent.value = true }
    }

    fun updateAppOnWatch() {
        _installRequestSent.value = false
        sendToWatch({ connectionManager.updateAppOnWatch(connectionState.value.nodeId) }) {
            _installRequestSent.value = true
        }
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
        if (_sendingRequest.value) return
        _sendingRequest.value = true
        _requestFailure.value = null
        viewModelScope.launch {
            try {
                _requestFailure.value =
                    when (val result = request()) {
                        WatchRequestResult.Sent -> {
                            onSent()
                            null
                        }

                        is WatchRequestResult.Failed -> {
                            _installRequestSent.value = false
                            result.reason
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Watch request failed", error)
                _requestFailure.value = WatchRequestFailure.OPEN_FAILED
            } finally {
                _sendingRequest.value = false
            }
        }
    }
}
