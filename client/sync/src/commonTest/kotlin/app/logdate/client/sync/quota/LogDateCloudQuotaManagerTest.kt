package app.logdate.client.sync.quota

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.repository.quota.QuotaResult
import app.logdate.client.repository.quota.RemoteQuotaDataSource
import app.logdate.shared.model.CloudObjectType
import app.logdate.shared.model.CloudStorageCategoryUsage
import app.logdate.shared.model.CloudStorageQuota
import app.logdate.shared.model.QuotaCategoryUsage
import app.logdate.shared.model.QuotaContentType
import app.logdate.shared.model.QuotaUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LogDateCloudQuotaManagerTest {
    @Test
    fun `active quota observer refreshes when the authenticated account changes`() =
        runTest {
            val sessionStorage = TestSessionStorage(UserSession("token-a", "refresh-a", "account-a"))
            val remote =
                ScriptedRemoteQuotaDataSource(
                    quotas =
                        ArrayDeque(
                            listOf(
                                QuotaUsage(totalBytes = 1_000, usedBytes = 100, categories = emptyList()),
                                QuotaUsage(totalBytes = 2_000, usedBytes = 1_500, categories = emptyList()),
                            ),
                        ),
                )
            val manager = LogDateCloudQuotaManager(StaticQuotaCalculator(), remote, sessionStorage)
            val observed = mutableListOf<Long>()

            val observer =
                launch {
                    manager.observeQuota().collect { quota ->
                        observed += quota.usedBytes
                        if (observed.size == 1) {
                            sessionStorage.saveSession(UserSession("token-b", "refresh-b", "account-b"))
                        }
                    }
                }
            while (observed.size < 2) testScheduler.advanceUntilIdle()
            observer.cancel()

            assertEquals(listOf(100L, 1_500L), observed)
        }

    @Test
    fun `account switch never returns the previous account quota from cache`() =
        runTest {
            val sessionStorage = TestSessionStorage(UserSession("token-a", "refresh-a", "account-a"))
            val remote =
                ScriptedRemoteQuotaDataSource(
                    quotas =
                        ArrayDeque(
                            listOf(
                                QuotaUsage(totalBytes = 1_000, usedBytes = 100, categories = emptyList()),
                                QuotaUsage(totalBytes = 2_000, usedBytes = 1_500, categories = emptyList()),
                            ),
                        ),
                )
            val manager = LogDateCloudQuotaManager(StaticQuotaCalculator(), remote, sessionStorage)

            assertEquals(100, manager.getCurrentQuota().usedBytes)
            sessionStorage.saveSession(UserSession("token-b", "refresh-b", "account-b"))

            assertEquals(1_500, manager.getCurrentQuota().usedBytes)
            assertEquals(2, remote.getQuotaUsageCalls)
        }

    @Test
    fun `syncWithServer publishes authoritative server quota to observers`() =
        runTest {
            val remote =
                ScriptedRemoteQuotaDataSource(
                    QuotaUsage(
                        totalBytes = 1_000,
                        usedBytes = 250,
                        categories =
                            listOf(
                                QuotaCategoryUsage(
                                    category = QuotaContentType.IMAGE_NOTES,
                                    sizeBytes = 250,
                                    objectCount = 1,
                                ),
                            ),
                    ),
                )
            val manager = LogDateCloudQuotaManager(StaticQuotaCalculator(), remote)

            manager.syncWithServer()
            val observed = manager.observeQuota().first()

            assertEquals(1_000, observed.totalBytes)
            assertEquals(250, observed.usedBytes)
            assertEquals(CloudObjectType.IMAGE_NOTES, observed.categories.single().category)
            assertEquals(1, remote.getQuotaUsageCalls)
        }

    @Test
    fun `recordObjectCreation emits quota delta without waiting for another server fetch`() =
        runTest {
            val remote =
                ScriptedRemoteQuotaDataSource(
                    QuotaUsage(
                        totalBytes = 1_000,
                        usedBytes = 100,
                        categories =
                            listOf(
                                QuotaCategoryUsage(
                                    category = QuotaContentType.TEXT_NOTES,
                                    sizeBytes = 100,
                                    objectCount = 1,
                                ),
                            ),
                    ),
                )
            val manager = LogDateCloudQuotaManager(StaticQuotaCalculator(), remote)
            manager.syncWithServer()

            manager.recordObjectCreation(CloudObjectType.TEXT_NOTES, 50)
            val observed = manager.observeQuota().first()

            assertEquals(150, observed.usedBytes)
            assertEquals(150, observed.categories.single().sizeBytes)
            assertEquals(2, observed.categories.single().objectCount)
            assertEquals(1, remote.getQuotaUsageCalls)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a delayed old account response never overwrites the new account cache`() =
        runTest {
            val sessions = TestSessionStorage(UserSession("token-a", "refresh-a", "account-a"))
            val remote = ScriptedRemoteQuotaDataSource(QuotaUsage(1_000, 100, emptyList()))
            val manager = LogDateCloudQuotaManager(StaticQuotaCalculator(), remote, sessions)
            manager.syncWithServer()
            val oldResult = CompletableDeferred<QuotaResult<QuotaUsage>>()
            remote.onGet = { oldResult.await() }
            val oldRequest = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.syncWithServer() }
            runCurrent()
            sessions.saveSession(UserSession("token-b", "refresh-b", "account-b"))
            remote.onGet = { QuotaResult.Success(QuotaUsage(2_000, 900, emptyList())) }
            manager.syncWithServer()
            oldResult.complete(QuotaResult.Success(QuotaUsage(1_000, 150, emptyList())))
            oldRequest.join()

            assertEquals(900, manager.getCurrentQuota().usedBytes)
        }

    private class StaticQuotaCalculator : QuotaCalculator {
        private val quota =
            CloudStorageQuota(
                totalBytes = 1_000,
                usedBytes = 0,
                categories = emptyList(),
            )

        override suspend fun calculateTotalUsage(): CloudStorageQuota = quota

        override suspend fun calculateCategoryUsage(objectType: CloudObjectType): CloudStorageCategoryUsage =
            CloudStorageCategoryUsage(
                category = objectType,
                sizeBytes = 0,
                objectCount = 0,
            )
    }

    private class ScriptedRemoteQuotaDataSource(
        private val quota: QuotaUsage? = null,
        private val quotas: ArrayDeque<QuotaUsage> = ArrayDeque(),
    ) : RemoteQuotaDataSource {
        var onGet: (suspend () -> QuotaResult<QuotaUsage>)? = null
        var getQuotaUsageCalls = 0
            private set

        override suspend fun getQuotaUsage(): QuotaResult<QuotaUsage> {
            getQuotaUsageCalls += 1
            onGet?.let { return it() }
            return QuotaResult.Success(quotas.removeFirstOrNull() ?: requireNotNull(quota))
        }

        override suspend fun refreshQuotaUsage(): QuotaResult<QuotaUsage> = getQuotaUsage()
    }

    private class TestSessionStorage(
        initial: UserSession?,
    ) : SessionStorage {
        private val state = MutableStateFlow(initial)

        override fun getSession(): UserSession? = state.value

        override fun getSessionFlow(): Flow<UserSession?> = state.asStateFlow()

        override suspend fun hasValidSession(): Boolean = state.value != null

        override suspend fun saveSession(session: UserSession) {
            state.value = session
        }

        override suspend fun clearSession() {
            state.value = null
        }
    }
}
