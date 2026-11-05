package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.data.maintenance.DataIntegrityService
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.restore.NativeBackupStatus
import app.logdate.client.device.restore.NativeBackupStatusSource
import app.logdate.client.device.restore.UnsupportedNativeBackupStatusSource
import app.logdate.client.domain.quota.ObserveCloudQuotaUseCase
import app.logdate.client.sync.SyncManager
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.CloudQuotaSnapshot
import app.logdate.shared.model.ServerCapability
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

class DataSettingsViewModel(
    private val observeCloudQuotaUseCase: ObserveCloudQuotaUseCase,
    private val syncManager: SyncManager,
    private val sessionStorage: SessionStorage,
    private val preferencesDataSource: LogdatePreferencesDataSource,
    private val configRepository: LogDateConfigRepository,
    private val dataIntegrityService: DataIntegrityService,
    private val cloudArchiveStatusSource: CloudArchiveStatusSource = UnavailableCloudArchiveStatusSource,
    private val nativeBackupStatusSource: NativeBackupStatusSource = UnsupportedNativeBackupStatusSource,
) : ViewModel() {
    val nativeBackupStatus: StateFlow<NativeBackupStatus> =
        nativeBackupStatusSource.observe().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            NativeBackupStatus.Unsupported,
        )

    fun retryNativeRecovery() = nativeBackupStatusSource.retryRecovery()

    @OptIn(ExperimentalCoroutinesApi::class)
    val reviewableIssueCount: StateFlow<Int> =
        sessionStorage.getSessionFlow().flatMapLatest { session ->
            if (session == null) {
                flowOf(0)
            } else {
                syncManager.observeDeadLetters().map { issues ->
                    val bound = sessionStorage.getOriginBoundSession()
                    issues.count { it.scope?.ownerId == session.accountId && it.scope?.serverOrigin == bound?.origin }
                }.onStart { emit(0) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _uiState = MutableStateFlow(DataSettingsState())
    val uiState: StateFlow<DataSettingsState> = _uiState.asStateFlow()
    val integrityState: StateFlow<IntegrityState> =
        uiState.map { it.integrityState }.stateIn(viewModelScope, SharingStarted.Eagerly, IntegrityState())

    private val pendingFeedback = MutableStateFlow<List<SyncFeedbackDelivery>>(emptyList())
    val syncFeedbackDelivery: StateFlow<SyncFeedbackDelivery?> =
        pendingFeedback.map { it.firstOrNull() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val syncFeedback: StateFlow<SyncFeedback?> =
        syncFeedbackDelivery.map { it?.feedback }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var nextFeedbackId = 0L
    private var quotaObservation: Job? = null
    private var quotaGeneration = 0L
    private var accountObservation: Job? = null
    private var syncRequestJob: Job? = null
    private var archiveRequestJob: Job? = null
    private var accountGeneration = 0L
    private var observedAccountId: String? = null
    private var hasObservedAccount = false
    private val observedRestores = mutableSetOf<Uuid>()
    private var restoreAuditPending = false

    init {
        observeAccount()
        observeCapabilities()
        viewModelScope.launch {
            try {
                syncManager.syncStatusFlow.collect { status -> _uiState.update { it.copy(syncStatus = status) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not observe sync status", error)
            }
        }
    }

    fun observeQuota() {
        val accountId = observedAccountId ?: return
        if (quotaObservation?.isActive == true && uiState.value.quotaAvailability !is QuotaAvailability.Unavailable) return
        quotaObservation?.cancel()
        val observation = ++quotaGeneration
        val account = accountGeneration
        _uiState.update { it.copy(quotaAvailability = QuotaAvailability.Loading(it.quotaState)) }
        quotaObservation =
            viewModelScope.launch {
                try {
                    observeCloudQuotaUseCase.observeSnapshots().collect { snapshot ->
                        if (account != accountGeneration || observation != quotaGeneration) return@collect
                        if (snapshot.accountId != accountId) {
                            if (snapshot.accountId == null) {
                                _uiState.update { it.copy(quotaAvailability = QuotaAvailability.Unavailable(it.quotaState)) }
                            }
                            return@collect
                        }
                        _uiState.update {
                            it.copy(
                                quotaAvailability =
                                    when (snapshot) {
                                        is CloudQuotaSnapshot.Loading -> QuotaAvailability.Loading(it.quotaState)
                                        is CloudQuotaSnapshot.Available -> QuotaAvailability.Available(snapshot.quota)
                                        is CloudQuotaSnapshot.Unavailable -> QuotaAvailability.Unavailable(it.quotaState)
                                    },
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Could not observe cloud quota", error)
                    if (account == accountGeneration && observation == quotaGeneration) {
                        _uiState.update { it.copy(quotaAvailability = QuotaAvailability.Unavailable(it.quotaState)) }
                    }
                }
            }
    }

    fun observeAccount() {
        if (accountObservation?.isActive == true) return
        _uiState.update {
            it.copy(
                accountAvailability =
                    it.isAuthenticated?.let { authenticated -> AccountAvailability.Available(authenticated) }
                        ?: AccountAvailability.Loading,
            )
        }
        accountObservation =
            viewModelScope.launch {
                try {
                    sessionStorage.getSessionFlow().collectLatest { session ->
                        val generation = updateAccount(session?.accountId)
                        if (session != null) {
                            try {
                                cloudArchiveStatusSource.observe(session).collect { status ->
                                    if (generation == accountGeneration) {
                                        _uiState.update {
                                            if (status.phase == CloudArchivePhase.CHECKING &&
                                                it.cloudArchiveStatus.phase != CloudArchivePhase.CHECKING
                                            ) {
                                                it
                                            } else {
                                                it.copy(cloudArchiveStatus = status)
                                            }
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Napier.e("Could not observe cloud archive", error)
                                if (generation == accountGeneration) {
                                    _uiState.update { it.copy(cloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.UNAVAILABLE)) }
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Could not observe stored account", error)
                    _uiState.update { it.copy(accountAvailability = AccountAvailability.Unavailable(it.isAuthenticated)) }
                }
            }
    }

    private fun updateAccount(accountId: String?): Long {
        val changed = !hasObservedAccount || observedAccountId != accountId
        if (changed) {
            accountGeneration++
            quotaGeneration++
            quotaObservation?.cancel()
            quotaObservation = null
            syncRequestJob?.cancel()
            archiveRequestJob?.cancel()
            pendingFeedback.value = emptyList()
            observedAccountId = accountId
            hasObservedAccount = true
        }
        _uiState.update {
            it.copy(
                accountAvailability = AccountAvailability.Available(accountId != null),
                quotaAvailability = if (changed) QuotaAvailability.Loading() else it.quotaAvailability,
                cloudArchiveStatus =
                    if (changed) {
                        CloudArchiveStatus(if (accountId == null) CloudArchivePhase.SIGNED_OUT else CloudArchivePhase.CHECKING)
                    } else {
                        it.cloudArchiveStatus
                    },
                syncRequest = if (changed) SyncRequestState.Idle else it.syncRequest,
                archiveRequest = if (changed) ArchiveRequestState.Idle else it.archiveRequest,
            )
        }
        if (changed && accountId != null) observeQuota()
        return accountGeneration
    }

    private fun observeCapabilities() {
        viewModelScope.launch {
            try {
                combine(configRepository.serverDescriptor, configRepository.backendUrl) { descriptor, backendUrl ->
                    descriptor?.hasCapability(ServerCapability.MANAGED_QUOTA)
                        ?: (backendUrl == DefaultLogDateConfigRepository.DEFAULT_BACKEND_URL)
                }.collect { available -> _uiState.update { it.copy(isQuotaAvailable = available) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not observe quota capability", error)
            }
        }
    }

    fun runIntegrityCheck() {
        if (uiState.value.integrityState.isRunning) return
        updateIntegrity { it.copy(operation = IntegrityOperation.Checking) }
        viewModelScope.launch {
            try {
                val report = dataIntegrityService.audit()
                updateIntegrity { it.copy(operation = IntegrityOperation.Idle, lastReport = report) }
            } catch (cancelled: CancellationException) {
                updateIntegrity { it.copy(operation = IntegrityOperation.Idle) }
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Integrity audit failed", error)
                updateIntegrity { it.copy(operation = IntegrityOperation.Failed(IntegrityFailure.CHECK)) }
            } finally {
                auditRestoredDataWhenReady()
            }
        }
    }

    fun repairIntegrity() {
        if (uiState.value.integrityState.isRunning) return
        updateIntegrity { it.copy(operation = IntegrityOperation.Repairing) }
        viewModelScope.launch {
            try {
                val result = dataIntegrityService.repair()
                updateIntegrity { it.copy(lastRepair = result) }
                refreshIntegrityAfterRepair()
            } catch (cancelled: CancellationException) {
                updateIntegrity { it.copy(operation = IntegrityOperation.Idle) }
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Integrity repair failed", error)
                updateIntegrity { it.copy(operation = IntegrityOperation.Failed(IntegrityFailure.REPAIR)) }
            } finally {
                auditRestoredDataWhenReady()
            }
        }
    }

    fun onRestoreCompleted(operationId: Uuid) {
        if (!observedRestores.add(operationId)) return
        restoreAuditPending = true
        auditRestoredDataWhenReady()
    }

    private fun auditRestoredDataWhenReady() {
        if (!restoreAuditPending || uiState.value.integrityState.isRunning || !viewModelScope.isActive) return
        restoreAuditPending = false
        runIntegrityCheck()
    }

    private suspend fun refreshIntegrityAfterRepair() {
        try {
            val report = dataIntegrityService.audit()
            updateIntegrity { it.copy(operation = IntegrityOperation.Idle, lastReport = report) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Could not refresh integrity report after repair", error)
            updateIntegrity { it.copy(operation = IntegrityOperation.Failed(IntegrityFailure.REFRESH_AFTER_REPAIR)) }
        }
    }

    private fun updateIntegrity(transform: (IntegrityState) -> IntegrityState) {
        _uiState.update { it.copy(integrityState = transform(it.integrityState)) }
    }

    /** The platform worker owns the backup; this operation only requests scheduling. */
    fun syncNow(allowMeteredMedia: Boolean = false) {
        if (uiState.value.isAuthenticated == null) return
        if (uiState.value.syncRequest == SyncRequestState.Requesting) return
        val generation = accountGeneration
        _uiState.update { it.copy(syncRequest = SyncRequestState.Requesting) }
        syncRequestJob =
            viewModelScope.launch {
                try {
                    if (sessionStorage.getSession() == null) {
                        _uiState.update { it.copy(syncRequest = SyncRequestState.Idle) }
                        deliver(SyncFeedback.NeedsAccount)
                        return@launch
                    }
                    syncManager.requestBackup(allowMeteredMedia)
                    if (generation != accountGeneration) return@launch
                    _uiState.update { it.copy(syncRequest = SyncRequestState.Accepted) }
                    deliver(SyncFeedback.Started)
                } catch (cancelled: CancellationException) {
                    if (generation == accountGeneration) _uiState.update { it.copy(syncRequest = SyncRequestState.Idle) }
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Could not start sync", error)
                    if (generation != accountGeneration) return@launch
                    _uiState.update { it.copy(syncRequest = SyncRequestState.Failed) }
                    deliver(SyncFeedback.Failed)
                }
            }
    }

    fun backupArchiveNow() {
        if (uiState.value.archiveRequest == ArchiveRequestState.Requesting) return
        if (sessionStorage.getSession() == null || !uiState.value.cloudArchiveStatus.canRetry) return
        val generation = accountGeneration
        _uiState.update { it.copy(archiveRequest = ArchiveRequestState.Requesting) }
        archiveRequestJob =
            viewModelScope.launch {
                try {
                    cloudArchiveStatusSource.requestBackup()
                    if (generation != accountGeneration) return@launch
                    _uiState.update { it.copy(archiveRequest = ArchiveRequestState.Accepted) }
                } catch (cancelled: CancellationException) {
                    if (generation == accountGeneration) _uiState.update { it.copy(archiveRequest = ArchiveRequestState.Idle) }
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not request Cloud archive backup", error)
                    if (generation != accountGeneration) return@launch
                    _uiState.update { it.copy(archiveRequest = ArchiveRequestState.Failed) }
                }
            }
    }

    private fun deliver(feedback: SyncFeedback) {
        val delivery = SyncFeedbackDelivery(++nextFeedbackId, feedback)
        pendingFeedback.update { it + delivery }
    }

    fun acknowledgeSyncFeedback(id: Long) {
        pendingFeedback.update { queue -> queue.filterNot { it.id == id } }
    }
}
