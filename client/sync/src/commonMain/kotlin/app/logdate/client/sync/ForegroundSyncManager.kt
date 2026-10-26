package app.logdate.client.sync

import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.networking.DataUsageMode
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.networking.NetworkState
import app.logdate.client.networking.shouldSyncMetadata
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-only sync manager for platforms without OS background schedulers.
 */
@OptIn(FlowPreview::class)
class ForegroundSyncManager(
    private val defaultSyncManager: DefaultSyncManager,
    private val sessionStorage: SessionStorage,
    private val preferencesDataSource: LogdatePreferencesDataSource,
    private val networkMonitor: NetworkAvailabilityMonitor,
    private val dataUsagePolicy: DataUsagePolicy,
    private val syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SyncManager {
    private var periodicJob: Job? = null

    init {
        syncScope.launch {
            // Being signed in is the whole condition. There used to be a stored preference
            // ANDed in here, which signing out set to false and nothing ever set back - so
            // signing in again left backups off for good, with the queue growing and the app
            // reporting itself healthy. Signing out already stops sync by clearing the session.
            sessionStorage
                .getSessionFlow()
                .map { it?.accountId to sessionStorage.getOriginBoundSession()?.origin }
                .distinctUntilChanged()
                .collect { (accountId, _) ->
                    if (accountId != null) {
                        stopPeriodicSync()
                        defaultSyncManager.readinessGate.allowProbe(manual = true)
                        startPeriodicSync()
                    } else {
                        stopPeriodicSync()
                    }
                }
        }

        syncScope.launch {
            var lastNetworkState: NetworkState? = null
            networkMonitor
                .observeNetwork()
                .debounce(2000)
                .distinctUntilChanged()
                .collect { currentState ->
                    if (lastNetworkState is NetworkState.NotConnected && currentState is NetworkState.Connected) {
                        handleNetworkRestored()
                    }
                    lastNetworkState = currentState
                }
        }
    }

    override fun sync(startNow: Boolean) {
        if (startNow) {
            // Delegates, so "Back up now" releases waiting entries the same way everywhere.
            defaultSyncManager.sync(startNow = true)
        } else {
            startPeriodicSync()
        }
    }

    override suspend fun requestBackup(allowMeteredMedia: Boolean) {
        if (!allowMeteredMedia) {
            sync(startNow = true)
            return
        }
        val session = requireNotNull(sessionStorage.getOriginBoundSession())
        val scope =
            app.logdate.client.sync.metadata
                .UploadScope(session.session.accountId, session.origin)
        withMobileDataConsent(scope) {
            defaultSyncManager.releaseUploadBackoff()
            SyncPassLoop(runPass = { defaultSyncManager.fullSync() }).runUntilSettled()
        }
    }

    override suspend fun uploadPendingChanges(): SyncResult = defaultSyncManager.uploadPendingChanges()

    override suspend fun <T> whilePaused(block: suspend () -> T): T = defaultSyncManager.whilePaused(block)

    override suspend fun downloadRemoteChanges(): SyncResult = defaultSyncManager.downloadRemoteChanges()

    override suspend fun syncContent(): SyncResult = defaultSyncManager.syncContent()

    override suspend fun syncJournals(): SyncResult = defaultSyncManager.syncJournals()

    override suspend fun syncAssociations(): SyncResult = defaultSyncManager.syncAssociations()

    override suspend fun syncDrafts(): SyncResult = defaultSyncManager.syncDrafts()

    override suspend fun fullSync(): SyncResult = defaultSyncManager.fullSync()

    override suspend fun getSyncStatus(): SyncStatus = defaultSyncManager.getSyncStatus()

    override fun observeDeadLetters(): Flow<List<SyncDeadLetterRecord>> = defaultSyncManager.observeDeadLetters()

    override suspend fun retryDeadLetter(id: String) = defaultSyncManager.retryDeadLetter(id)

    override suspend fun discardDeadLetter(id: String) = defaultSyncManager.discardDeadLetter(id)

    override val syncStatusFlow = defaultSyncManager.syncStatusFlow

    private fun startPeriodicSync() {
        if (periodicJob?.isActive == true) {
            return
        }
        periodicJob =
            syncScope.launch {
                while (isActive) {
                    val mode = dataUsagePolicy.currentMode()
                    if (mode.shouldSyncMetadata()) {
                        if (defaultSyncManager.hasDueWork() || defaultSyncManager.hasRemoteRefreshDue()) {
                            SyncPassLoop(
                                runPass = { defaultSyncManager.fullSync() },
                                shouldContinue = { dataUsagePolicy.currentMode().shouldSyncMetadata() },
                            ).runUntilSettled()
                        }
                    } else {
                        Napier.d("Skipping periodic sync cycle — data usage policy restricts metadata sync")
                    }
                    val interval =
                        when (mode) {
                            is DataUsageMode.Restricted -> RESTRICTED_SYNC_INTERVAL_MS
                            else -> PERIODIC_SYNC_INTERVAL_MS
                        }
                    delay(interval)
                }
            }
        Napier.d("Foreground sync scheduler enabled")
    }

    private fun stopPeriodicSync() {
        periodicJob?.cancel()
        periodicJob = null
        Napier.d("Foreground sync scheduler disabled")
    }

    private suspend fun handleNetworkRestored() {
        val lastError = defaultSyncManager.getLastSyncError()
        if (lastError != null && lastError.type != SyncErrorType.AUTHENTICATION_ERROR) {
            Napier.d("Retrying sync after network restoration")
            defaultSyncManager.fullSync()
        }
    }

    companion object {
        private const val PERIODIC_SYNC_INTERVAL_MS = 30 * 60 * 1000L
        private const val RESTRICTED_SYNC_INTERVAL_MS = 60 * 60 * 1000L
    }
}
