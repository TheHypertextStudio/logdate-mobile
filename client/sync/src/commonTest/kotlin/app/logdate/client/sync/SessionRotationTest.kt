package app.logdate.client.sync

import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.DefaultCloudJournalDataSource
import app.logdate.client.sync.cloud.DeviceSessionRevocationListener
import app.logdate.client.sync.cloud.RefreshedSession
import app.logdate.client.sync.cloud.SessionRefreshingAccountRepository
import app.logdate.client.sync.test.FakeCloudAccountRepository
import app.logdate.client.sync.test.fakeAccountRepository
import app.logdate.client.sync.test.fakeCloudApiClient
import app.logdate.client.sync.test.fakeSessionStorage
import app.logdate.client.sync.test.testDefaultSyncManager
import app.logdate.shared.model.CloudAccountRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A linked watch's session slides forward and its refresh token rotates, so the new one has to be
 * stored; and when the session is revoked, the watch must learn it at once to stop and erase itself.
 */
class SessionRotationTest {
    @Test
    fun `a rotated refresh token replaces the old one in the session`() =
        runTest {
            val sessionStorage = fakeSessionStorage()
            val refresher =
                SyncTokenRefresher(
                    sessionStorage,
                    RotatingAccountRepository(Result.success(RefreshedSession("new-access", "new-refresh"))),
                )

            val result = refresher.withFreshToken(::rejectTheFirstToken, "probe")

            assertEquals("new-access", result.getOrThrow())
            assertEquals(UserSession("new-access", "new-refresh", "test-account-id"), sessionStorage.getSession())
        }

    @Test
    fun `a server that does not rotate keeps the refresh token`() =
        runTest {
            val sessionStorage = fakeSessionStorage()
            val refresher = SyncTokenRefresher(sessionStorage, fakeAccountRepository())

            refresher.withFreshToken(::rejectTheFirstToken, "probe")

            assertEquals(UserSession("new-token", "test-refresh-token", "test-account-id"), sessionStorage.getSession())
        }

    @Test
    fun `a revoked device session signs out and is reported`() =
        runTest {
            val sessionStorage = fakeSessionStorage()
            var revocations = 0
            val refresher =
                SyncTokenRefresher(
                    sessionStorage,
                    RotatingAccountRepository(Result.failure(CloudApiException(REVOKED, "revoked", statusCode = 401))),
                    DeviceSessionRevocationListener { revocations++ },
                )

            val result = refresher.withFreshToken(::rejectTheFirstToken, "probe")

            assertEquals(REVOKED, assertIs<CloudApiException>(result.exceptionOrNull()).errorCode)
            assertNull(sessionStorage.getSession())
            assertEquals(1, revocations)
        }

    @Test
    fun `an expired refresh token signs out without reporting a revoked device`() =
        runTest {
            val sessionStorage = fakeSessionStorage()
            var revocations = 0
            val refresher =
                SyncTokenRefresher(
                    sessionStorage,
                    RotatingAccountRepository(Result.failure(CloudApiException("INVALID_REFRESH_TOKEN", "expired", statusCode = 401))),
                    DeviceSessionRevocationListener { revocations++ },
                )

            refresher.withFreshToken(::rejectTheFirstToken, "probe")

            assertNull(sessionStorage.getSession())
            assertEquals(0, revocations)
        }

    @Test
    fun `a refresh that cannot reach the server keeps the session and reports nothing`() =
        runTest {
            val sessionStorage = fakeSessionStorage()
            var revocations = 0
            val refresher =
                SyncTokenRefresher(
                    sessionStorage,
                    RotatingAccountRepository(Result.failure(CloudApiException("NETWORK_ERROR", "offline"))),
                    DeviceSessionRevocationListener { revocations++ },
                )

            refresher.withFreshToken(::rejectTheFirstToken, "probe")

            assertEquals("test-refresh-token", sessionStorage.getSession()?.refreshToken)
            assertEquals(0, revocations)
        }

    @Test
    fun `the sync engine passes a revoked session to its listener`() =
        runTest {
            var revocations = 0
            val api =
                fakeCloudApiClient {
                    getJournalChangesResponse =
                        Result.failure(CloudApiException(REVOKED, "revoked", statusCode = 401))
                }
            val manager =
                testDefaultSyncManager(
                    cloudJournalDataSource = DefaultCloudJournalDataSource(api),
                    cloudAccountRepository =
                        RotatingAccountRepository(
                            Result.failure(CloudApiException(REVOKED, "revoked", statusCode = 401)),
                        ),
                    deviceSessionRevocationListener = DeviceSessionRevocationListener { revocations++ },
                )

            manager.downloadRemoteChanges()

            assertEquals(1, revocations)
        }

    private suspend fun rejectTheFirstToken(token: String): Result<String> =
        if (token == "test-access-token") {
            Result.failure(CloudApiException("TOKEN_EXPIRED", "expired", statusCode = 401))
        } else {
            Result.success(token)
        }

    private class RotatingAccountRepository(
        private val result: Result<RefreshedSession>,
    ) : CloudAccountRepository by FakeCloudAccountRepository(),
        SessionRefreshingAccountRepository {
        override suspend fun refreshSession(refreshToken: String): Result<RefreshedSession> = result
    }

    private companion object {
        const val REVOKED = "DEVICE_SESSION_REVOKED"
    }
}
