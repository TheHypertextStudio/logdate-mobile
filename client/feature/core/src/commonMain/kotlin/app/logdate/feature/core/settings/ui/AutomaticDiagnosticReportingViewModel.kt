package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.sync.diagnostics.DiagnosticReportingController
import app.logdate.client.sync.diagnostics.DiagnosticReportingSettings
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AutomaticDiagnosticReportingViewModel internal constructor(
    private val actions: DiagnosticReportingActions,
) : ViewModel() {
    constructor(controller: DiagnosticReportingController) : this(ControllerDiagnosticReportingActions(controller))

    private val mutableState = MutableStateFlow(AutomaticDiagnosticReportingState())
    internal val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var toggleJob: Job? = null
    private var deleteJob: Job? = null
    private var generation = 0L
    private var lastKnown: DiagnosticReportingSettings? = null
    private var initialized = false
    private var ownedStorageFailure: DiagnosticReportingSettings? = null

    init {
        viewModelScope.launch {
            actions.settings.collect { settings -> if (initialized) acceptSettings(settings) }
        }
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        ownedStorageFailure = null
        mutableState.update { it.copy(availability = ReportingAvailability.Loading(lastKnown)) }
        refreshJob =
            viewModelScope.launch {
                try {
                    actions.refresh()
                    acceptSettings(actions.settings.value, finishRefresh = true)
                } catch (cancelled: CancellationException) {
                    mutableState.update {
                        it.copy(
                            availability =
                                lastKnown?.let { ReportingAvailability.Available(it) } ?: ReportingAvailability.NotLoaded,
                        )
                    }
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not read automatic reporting settings", error)
                    mutableState.update { it.copy(availability = ReportingAvailability.Unavailable(lastKnown)) }
                }
            }
    }

    fun setEnabled(enabled: Boolean) {
        val current = state.value.settings ?: return
        if (state.value.isBusy || (enabled && !current.available)) return
        if (current.enabled == enabled) {
            mutableState.update { it.copy(toggle = ReportingToggleOperation.Idle) }
            return
        }
        if (!enabled) {
            saveToggle(false, null)
            return
        }
        val request = generation
        mutableState.update { it.copy(toggle = ReportingToggleOperation.Preparing) }
        toggleJob =
            viewModelScope.launch {
                try {
                    val approval = actions.prepareConsent()
                    if (request != generation) return@launch
                    if (approval == null) retainOwnedStorageFailure()
                    mutableState.update {
                        it.copy(
                            toggle =
                                if (approval != null && approval.destination == it.settings?.destination) {
                                    ReportingToggleOperation.AwaitingConsent(approval)
                                } else {
                                    ReportingToggleOperation.Failed(true, ReportingToggleFailure.PREPARE)
                                },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    if (request == generation) mutableState.update { it.copy(toggle = ReportingToggleOperation.Idle) }
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not prepare automatic reporting consent", error)
                    if (request == generation) retainOwnedStorageFailure()
                    if (request ==
                        generation
                    ) {
                        mutableState.update { it.copy(toggle = ReportingToggleOperation.Failed(true, ReportingToggleFailure.PREPARE)) }
                    }
                }
            }
    }

    fun confirmToggle() {
        val pending = state.value.toggle as? ReportingToggleOperation.AwaitingConsent ?: return
        saveToggle(true, pending.approval)
    }

    fun cancelToggleConsent() {
        if (state.value.toggle is ReportingToggleOperation.AwaitingConsent) {
            mutableState.update {
                it.copy(
                    toggle = ReportingToggleOperation.Idle,
                )
            }
        }
    }

    fun retryToggle() {
        (state.value.toggle as? ReportingToggleOperation.Failed)?.let { setEnabled(it.requested) }
    }

    private fun saveToggle(
        enabled: Boolean,
        approval: DiagnosticReportingApproval?,
    ) {
        val request = generation
        mutableState.update { it.copy(toggle = ReportingToggleOperation.Saving(enabled)) }
        toggleJob =
            viewModelScope.launch {
                try {
                    val accepted = if (enabled) approval?.let { actions.enable(it) } == true else actions.disable()
                    if (request != generation) return@launch
                    if (!accepted) retainOwnedStorageFailure()
                    mutableState.update {
                        it.copy(
                            toggle =
                                if (accepted) {
                                    ReportingToggleOperation.Idle
                                } else {
                                    ReportingToggleOperation.Failed(
                                        enabled,
                                        if (enabled) ReportingToggleFailure.ENABLE else ReportingToggleFailure.DISABLE,
                                    )
                                },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    if (request == generation) mutableState.update { it.copy(toggle = ReportingToggleOperation.Idle) }
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not change automatic reporting", error)
                    if (request == generation) retainOwnedStorageFailure()
                    if (request == generation) {
                        mutableState.update {
                            it.copy(
                                toggle =
                                    ReportingToggleOperation.Failed(
                                        enabled,
                                        if (enabled) ReportingToggleFailure.ENABLE else ReportingToggleFailure.DISABLE,
                                    ),
                            )
                        }
                    }
                }
            }
    }

    fun requestDelete() {
        if (state.value.isBusy || state.value.settings?.available != true) return
        val request = generation
        mutableState.update { it.copy(deletion = ReportingDeleteOperation.Preparing) }
        deleteJob =
            viewModelScope.launch {
                try {
                    val approval = actions.prepareConsent()
                    if (request != generation) return@launch
                    if (approval == null) retainOwnedStorageFailure()
                    mutableState.update {
                        it.copy(
                            deletion =
                                if (approval != null && approval.destination == it.settings?.destination) {
                                    ReportingDeleteOperation.AwaitingConsent(approval)
                                } else {
                                    ReportingDeleteOperation.Failed(ReportingDeleteFailure.PREPARE)
                                },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    if (request == generation) mutableState.update { it.copy(deletion = ReportingDeleteOperation.Idle) }
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not prepare uploaded report deletion", error)
                    if (request == generation) retainOwnedStorageFailure()
                    if (request ==
                        generation
                    ) {
                        mutableState.update { it.copy(deletion = ReportingDeleteOperation.Failed(ReportingDeleteFailure.PREPARE)) }
                    }
                }
            }
    }

    fun confirmDelete() {
        val pending = state.value.deletion as? ReportingDeleteOperation.AwaitingConsent ?: return
        val request = generation
        mutableState.update { it.copy(deletion = ReportingDeleteOperation.Deleting) }
        deleteJob =
            viewModelScope.launch {
                try {
                    val deleted = actions.delete(pending.approval)
                    if (request != generation) return@launch
                    if (!deleted) retainOwnedStorageFailure()
                    mutableState.update {
                        it.copy(
                            deletion =
                                if (deleted) {
                                    ReportingDeleteOperation.Deleted
                                } else {
                                    ReportingDeleteOperation.Failed(
                                        ReportingDeleteFailure.DELETE,
                                    )
                                },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    if (request == generation) mutableState.update { it.copy(deletion = ReportingDeleteOperation.Idle) }
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not delete uploaded diagnostic reports", error)
                    if (request == generation) retainOwnedStorageFailure()
                    if (request ==
                        generation
                    ) {
                        mutableState.update { it.copy(deletion = ReportingDeleteOperation.Failed(ReportingDeleteFailure.DELETE)) }
                    }
                }
            }
    }

    fun cancelDeleteConsent() {
        if (state.value.deletion is ReportingDeleteOperation.AwaitingConsent) {
            mutableState.update {
                it.copy(deletion = ReportingDeleteOperation.Idle)
            }
        }
    }

    fun retryDelete() {
        if (state.value.deletion is ReportingDeleteOperation.Failed) requestDelete()
    }

    private fun retainOwnedStorageFailure() {
        actions.settings.value
            .takeIf { it.storageFailed }
            ?.let { ownedStorageFailure = it }
    }

    private fun acceptSettings(
        settings: DiagnosticReportingSettings,
        finishRefresh: Boolean = false,
    ) {
        if (settings.storageFailed) {
            // StateFlow may deliver the controller's owned failure after the action has finished.
            if (!finishRefresh && (state.value.isTogglePending || state.value.isDeletePending || settings == ownedStorageFailure)) return
            mutableState.update { it.copy(availability = ReportingAvailability.Unavailable(lastKnown)) }
            return
        }
        if (lastKnown != null && lastKnown?.destination != settings.destination) {
            generation++
            toggleJob?.cancel()
            deleteJob?.cancel()
            mutableState.update { it.copy(toggle = ReportingToggleOperation.Idle, deletion = ReportingDeleteOperation.Idle) }
        }
        ownedStorageFailure = null
        lastKnown = settings
        initialized = true
        mutableState.update {
            it.copy(
                availability =
                    if (!finishRefresh && it.availability is ReportingAvailability.Loading) {
                        ReportingAvailability.Loading(settings)
                    } else {
                        ReportingAvailability.Available(settings)
                    },
            )
        }
    }
}
