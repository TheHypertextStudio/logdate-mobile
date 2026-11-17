package app.logdate.client.sync

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionAuthenticationSnapshot
import app.logdate.client.datastore.SessionInstallationGuard
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.DeviceSessionRevocationListener
import app.logdate.client.sync.test.FakeSessionStorage
import app.logdate.client.sync.test.fakeAccountRepository
import app.logdate.shared.model.CloudAccountRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncRevocationGuardTest {
    @Test
    fun `a queued refusal cannot erase a newer login when the vault lock becomes available`() =
        runTest {
            val storage = GuardedStorage()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            storage.beforeClear = {
                entered.complete(Unit)
                release.await()
            }
            var revocations = 0
            val refresher = refresher(storage, { revocations++ })
            val attempt = async { refresher.withFreshToken(::expiredAccess, "download") }
            entered.await()
            val replacement = requireNotNull(storage.getSession()).copy(accessToken = "new-login", refreshToken = "new-refresh")
            storage.saveExplicitSession(replacement)
            release.complete(Unit)
            attempt.await()

            assertEquals(replacement, storage.getSession())
            assertEquals(0, revocations)
        }

    @Test
    fun `an explicit login with equal credentials still supersedes an in-flight refusal`() =
        runTest {
            val storage = GuardedStorage()
            val original = storage.getOriginBoundSession()
            var revocations = 0
            val refresher =
                refresher(storage, { revocations++ }) {
                    storage.saveExplicitSession(requireNotNull(original).session)
                }

            refresher.withFreshToken(::expiredAccess, "download")

            assertEquals(original, storage.getOriginBoundSession())
            assertEquals(0, revocations)
        }

    @Test
    fun `an equal session on another server is never cleared by the previous server refusal`() =
        runTest {
            val storage = GuardedStorage()
            val original = requireNotNull(storage.getSession())
            var revocations = 0
            val refresher =
                refresher(storage, { revocations++ }) {
                    storage.delegate.currentOrigin = "https://second.example"
                }

            refresher.withFreshToken(::expiredAccess, "download")

            assertEquals(OriginBoundSession("https://second.example", original), storage.getOriginBoundSession())
            assertEquals(0, revocations)
        }

    @Test
    fun `unavailable authority does not permit an unconditional sign out`() =
        runTest {
            val storage = GuardedStorage().apply { snapshotsAvailable = false }
            val original = storage.getSession()
            var revocations = 0

            refresher(storage, { revocations++ }).withFreshToken(::expiredAccess, "download")

            assertEquals(original, storage.getSession())
            assertEquals(0, revocations)
        }

    @Test
    fun `a current refused session is cleared and its device revocation is reported once`() =
        runTest {
            val storage = GuardedStorage()
            var revocations = 0

            refresher(storage, { revocations++ }).withFreshToken(::expiredAccess, "download")

            assertNull(storage.getSession())
            assertEquals(1, revocations)
        }

    private fun refresher(
        storage: SessionStorage,
        onRevoked: () -> Unit,
        beforeRefusal: suspend () -> Unit = {},
    ): SyncTokenRefresher =
        SyncTokenRefresher(
            storage,
            object : CloudAccountRepository by fakeAccountRepository() {
                override suspend fun refreshAccessToken(refreshToken: String): Result<String> {
                    beforeRefusal()
                    return Result.failure(CloudApiException("DEVICE_SESSION_REVOKED", "Revoked", 401))
                }
            },
            DeviceSessionRevocationListener { onRevoked() },
        )

    private fun expiredAccess(token: String): Result<Unit> = Result.failure(CloudApiException("EXPIRED", "Expired", 401))

    private class GuardedStorage(
        val delegate: FakeSessionStorage = FakeSessionStorage(),
    ) : SessionStorage by delegate {
        private var revision = 0L
        var snapshotsAvailable = true
        var beforeClear: suspend () -> Unit = {}

        override suspend fun captureAuthenticationSnapshot(): SessionAuthenticationSnapshot? =
            if (snapshotsAvailable) {
                SessionAuthenticationSnapshot(getOriginBoundSession(), SessionInstallationGuard(delegate.currentOrigin, revision))
            } else {
                null
            }

        override suspend fun saveExplicitSession(session: UserSession) {
            revision++
            delegate.saveSession(session)
        }

        override suspend fun clearSession() {
            beforeClear()
            revision++
            delegate.clearSession()
        }

        override suspend fun clearSessionIfUnchanged(
            expected: SessionInstallationGuard,
            onCleared: () -> Unit,
        ): Boolean {
            beforeClear()
            if (expected.origin != delegate.currentOrigin || expected.revision != revision) return false
            revision++
            delegate.clearSession()
            onCleared()
            return true
        }
    }
}
