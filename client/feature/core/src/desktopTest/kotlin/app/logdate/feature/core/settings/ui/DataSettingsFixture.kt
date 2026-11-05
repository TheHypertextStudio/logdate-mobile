package app.logdate.feature.core.settings.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.logdate.client.data.maintenance.DataIntegrityService
import app.logdate.client.database.dao.journals.JournalContentDao
import app.logdate.client.database.dao.maintenance.IntegrityDao
import app.logdate.client.database.dao.sync.SyncMetadataDao
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.domain.quota.ObserveCloudQuotaUseCase
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncStatus
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.CloudQuotaManager
import app.logdate.shared.model.CloudQuotaSnapshot
import app.logdate.shared.model.CloudStorageQuota
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

internal class DataSettingsFixture(
    val quota: MutableSharedFlow<CloudStorageQuota> = MutableSharedFlow(),
    val quotaFlow: Flow<CloudStorageQuota> = quota,
    val archivePhase: CloudArchivePhase = CloudArchivePhase.COMPLETE,
    val archiveFlow: Flow<CloudArchiveStatus>? = null,
    val quotaSnapshots: Flow<CloudQuotaSnapshot>? = null,
    val sessionFlowOverride: Flow<UserSession?>? = null,
    private val cachedSessionMissing: Boolean = false,
) {
    val syncAccepted = CompletableDeferred<Unit>()
    val archiveAccepted = CompletableDeferred<Unit>()
    var syncRequests = 0
    var archiveRequests = 0
    var sessionObservations = 0
    var ownerFailure: Throwable? = null
    var syncFailure: Throwable? = null
    var archiveFailure: Throwable? = null
    var auditGate: CompletableDeferred<Unit>? = null
    var audits = 0
    private val session = UserSession("test-access", "test-refresh", "test-account")
    private val sessions = MutableStateFlow<UserSession?>(session)
    var quotaManagerOverride: CloudQuotaManager? = null
    val sessionStorage: SessionStorage =
        object : SessionStorage {
            override fun getSession(): UserSession? = if (cachedSessionMissing) null else sessions.value

            override fun getSessionFlow(): Flow<UserSession?> =
                flow {
                    sessionObservations++
                    emitAll(sessionFlowOverride ?: sessions)
                }

            override suspend fun hasValidSession() = true

            override suspend fun saveSession(session: UserSession) {
                sessions.value = session
            }

            override suspend fun clearSession() {
                sessions.value = null
            }
        }

    fun switchAccount(accountId: String) {
        sessions.value = session.copy(accountId = accountId)
    }

    private val config = DefaultLogDateConfigRepository()

    fun signOut() {
        sessions.value = null
    }

    fun refreshToken() {
        sessions.value = session.copy(accessToken = "updated-test-token")
    }

    private val syncStatus = MutableStateFlow(SyncStatus(true, null, 0, false, false))

    fun create(): DataSettingsViewModel {
        val quotaManager =
            proxy<CloudQuotaManager> { name, _ ->
                when (name) {
                    "observeQuota" -> quotaFlow
                    "observeQuotaSnapshots" ->
                        quotaSnapshots ?: flow<CloudQuotaSnapshot> {
                            val account = sessionStorage.getSession()?.accountId
                            emit(CloudQuotaSnapshot.Loading(account))
                            emitAll(quotaFlow.map { CloudQuotaSnapshot.Available(account, it) })
                        }
                    "getCurrentQuota" -> CloudStorageQuota(100, 0, emptyList())
                    else -> error("Unexpected quota call: $name")
                }
            }
        val syncManager =
            proxy<SyncManager> { name, arguments ->
                when (name) {
                    "getSyncStatusFlow" -> syncStatus
                    "requestBackup" ->
                        suspendResult(arguments) {
                            syncRequests++
                            syncFailure?.let { throw it }
                            syncAccepted.await()
                        }
                    else -> error("Unexpected sync call: $name")
                }
            }
        val preferences =
            LogdatePreferencesDataSource(
                object : DataStore<Preferences> {
                    override val data = flowOf(emptyPreferences())

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(emptyPreferences())
                },
            )
        val owner =
            object : CanonicalOwnerProvider {
                override suspend fun getCanonicalOwnerId(): String {
                    ownerFailure?.let { throw it }
                    return "test-owner"
                }

                override suspend fun hasBoundOwner() = true
            }
        val integrity =
            DataIntegrityService(
                proxy<IntegrityDao> { name, arguments ->
                    if (name == "countOrphanedJournalLinks") {
                        suspendResult(arguments) {
                            audits++
                            auditGate?.await()
                            0
                        }
                    } else {
                        0
                    }
                },
                proxy<SyncMetadataDao> { _, _ -> emptyList<Any>() },
                proxy<JournalContentDao> { _, _ -> false },
                config,
                owner,
                proxy<DeviceIdProvider> { _, _ -> error("Device identity not used by these tests") },
            )
        return DataSettingsViewModel(
            ObserveCloudQuotaUseCase(quotaManagerOverride ?: quotaManager),
            syncManager,
            sessionStorage,
            preferences,
            config,
            integrity,
            object : CloudArchiveStatusSource {
                override fun observe(session: UserSession) = archiveFlow ?: flowOf(CloudArchiveStatus(archivePhase))

                override suspend fun requestBackup() {
                    archiveRequests++
                    archiveFailure?.let { throw it }
                    archiveAccepted.await()
                }
            },
        )
    }
}

private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
        call(method.name, arguments ?: emptyArray())
    } as T

private fun <T> suspendResult(
    arguments: Array<out Any?>,
    block: suspend () -> T,
): Any? {
    @Suppress("UNCHECKED_CAST")
    val continuation = arguments.last() as kotlin.coroutines.Continuation<T>
    return block.startCoroutineUninterceptedOrReturn(continuation)
}
