package app.logdate.feature.core.settings.ui

import app.logdate.client.repository.quota.QuotaResult
import app.logdate.client.repository.quota.RemoteQuotaDataSource
import app.logdate.client.sync.quota.LogDateCloudQuotaManager
import app.logdate.client.sync.quota.QuotaCalculator
import app.logdate.shared.model.CloudObjectType
import app.logdate.shared.model.CloudQuotaSnapshot
import app.logdate.shared.model.CloudStorageCategoryUsage
import app.logdate.shared.model.CloudStorageQuota
import app.logdate.shared.model.QuotaUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DataSettingsQuotaScopeTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun switchingAccountsClearsUsageBeforeTheNextAccountsLookupAndFailureCannotReplayIt() =
        runTest {
            val fixture = ScopedQuotaFixture()
            val next = CompletableDeferred<QuotaResult<QuotaUsage>>()
            fixture.response = { account -> if (account == "account-b") next.await() else fixture.quota(100) }
            val model = fixture.data.create()
            runCurrent()
            assertEquals(
                100,
                model.uiState.value.quotaState
                    ?.usedBytes,
            )

            fixture.data.switchAccount("account-b")
            runCurrent()
            assertEquals(true, model.uiState.value.isAuthenticated)
            assertNull(model.uiState.value.quotaState)
            next.complete(QuotaResult.Error("Fixture unavailable"))
            runCurrent()
            assertNull(model.uiState.value.quotaState)
            assertIs<QuotaAvailability.Unavailable>(model.uiState.value.quotaAvailability)
        }

    @Test
    fun aLateAccountAResponseCannotReplaceAccountBsConfirmedUsage() =
        runTest {
            val fixture = ScopedQuotaFixture()
            val model = fixture.data.create()
            runCurrent()
            assertEquals(
                100,
                model.uiState.value.quotaState
                    ?.usedBytes,
            )
            val late = CompletableDeferred<QuotaResult<QuotaUsage>>()
            fixture.response = { account -> if (account == "test-account") late.await() else fixture.quota(900) }
            val oldRequest = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.manager.syncWithServer() }
            runCurrent()
            fixture.data.switchAccount("account-b")
            runCurrent()
            assertEquals(
                900,
                model.uiState.value.quotaState
                    ?.usedBytes,
            )

            late.complete(fixture.quota(150))
            oldRequest.join()
            runCurrent()
            assertEquals(
                900,
                model.uiState.value.quotaState
                    ?.usedBytes,
            )
        }

    @Test
    fun sameAccountTokenRefreshKeepsItsUsageWithoutStartingAnotherLookup() =
        runTest {
            val fixture = ScopedQuotaFixture()
            val model = fixture.data.create()
            runCurrent()
            val initialRequests = fixture.requests
            fixture.data.refreshToken()
            runCurrent()
            assertEquals(
                100,
                model.uiState.value.quotaState
                    ?.usedBytes,
            )
            assertEquals(initialRequests, fixture.requests)
        }

    @Test
    fun unscopedSnapshotsCannotBecomeTheSignedInAccountsUsage() =
        runTest {
            val fixture =
                DataSettingsFixture(
                    quotaSnapshots = flowOf(CloudQuotaSnapshot.Available(null, CloudStorageQuota(1_000, 500, emptyList()))),
                )
            val model = fixture.create()
            runCurrent()
            assertEquals(true, model.uiState.value.isAuthenticated)
            assertNull(model.uiState.value.quotaState)
            assertIs<QuotaAvailability.Unavailable>(model.uiState.value.quotaAvailability)
        }

    private class ScopedQuotaFixture {
        val data = DataSettingsFixture()
        var requests = 0
        var response: suspend (String?) -> QuotaResult<QuotaUsage> = { quota(100) }
        val manager =
            LogDateCloudQuotaManager(
                object : QuotaCalculator {
                    override suspend fun calculateTotalUsage() = CloudStorageQuota(1_000, 0, emptyList())

                    override suspend fun calculateCategoryUsage(objectType: CloudObjectType) = CloudStorageCategoryUsage(objectType, 0)
                },
                object : RemoteQuotaDataSource {
                    override suspend fun getQuotaUsage(): QuotaResult<QuotaUsage> {
                        requests++
                        return response(data.sessionStorage.getSession()?.accountId)
                    }

                    override suspend fun refreshQuotaUsage() = getQuotaUsage()
                },
                data.sessionStorage,
            )

        init {
            data.quotaManagerOverride = manager
        }

        fun quota(used: Long) = QuotaResult.Success(QuotaUsage(1_000, used, emptyList()))
    }
}
