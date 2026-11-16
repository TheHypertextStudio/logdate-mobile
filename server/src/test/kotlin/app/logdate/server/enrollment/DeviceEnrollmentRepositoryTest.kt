package app.logdate.server.enrollment

import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceEnrollmentRepositoryTest {
    @Test
    fun `approval is account bound short lived and one use`() =
        runTest {
            val repository = InMemoryDeviceEnrollmentRepository()
            val account = UUID.randomUUID()
            val otherAccount = UUID.randomUUID()
            val request =
                DeviceEnrollment(
                    id = UUID.randomUUID(),
                    accountId = account,
                    deviceName = "Alice's Mac",
                    publicKey = "base64url-public-key",
                    confirmationCode = "413827",
                    expiresAt = 1_000,
                    claimHash = "claim-hash",
                )
            repository.create(request)

            assertNull(repository.get(otherAccount, request.id, now = 100))
            assertFalse(repository.approve(otherAccount, request.id, "413827", "opaque-envelope", now = 100))
            assertFalse(repository.approve(account, request.id, "wrong", "opaque-envelope", now = 100))
            assertTrue(repository.approve(account, request.id, "413827", "opaque-envelope", now = 100))
            assertTrue(repository.approve(account, request.id, "413827", "opaque-envelope", now = 100))
            assertFalse(repository.approve(account, request.id, "413827", "second-envelope", now = 100))
            assertEquals("opaque-envelope", repository.claim("claim-hash", now = 100)?.encryptedEnvelope)
            assertEquals("opaque-envelope", repository.claim("claim-hash", now = 100)?.encryptedEnvelope)
            assertEquals("opaque-envelope", repository.consume(account, request.id, now = 100))
            assertNull(repository.consume(account, request.id, now = 100))
            assertNull(repository.claim("claim-hash", now = 100))
        }

    @Test
    fun `expired or cancelled requests cannot be approved`() =
        runTest {
            val repository = InMemoryDeviceEnrollmentRepository()
            val account = UUID.randomUUID()
            val expired = DeviceEnrollment(UUID.randomUUID(), account, "Mac", "key", "111111", expiresAt = 500)
            repository.create(expired)
            assertNull(repository.get(account, expired.id, now = 500))
            assertFalse(repository.approve(account, expired.id, "111111", "envelope", now = 500))

            val cancelled = DeviceEnrollment(UUID.randomUUID(), account, "Mac", "key", "222222", expiresAt = 1_000)
            repository.create(cancelled)
            assertTrue(repository.cancel(account, cancelled.id))
            assertFalse(repository.approve(account, cancelled.id, "222222", "envelope", now = 100))
        }

    @Test
    fun `session is issued once only for an unexpired pending request of its account`() =
        runTest {
            val repository = InMemoryDeviceEnrollmentRepository()
            val account = UUID.randomUUID()
            val request = DeviceEnrollment(UUID.randomUUID(), account, "Mac", "key", "333333", expiresAt = 1_000)
            repository.create(request)

            assertEquals(SessionIssueResult.NOT_FOUND, repository.markSessionIssued(UUID.randomUUID(), request.id, now = 100))
            assertEquals(SessionIssueResult.NOT_FOUND, repository.markSessionIssued(account, request.id, now = 1_000))
            assertEquals(SessionIssueResult.ISSUED, repository.markSessionIssued(account, request.id, now = 100))
            assertEquals(SessionIssueResult.ALREADY_ISSUED, repository.markSessionIssued(account, request.id, now = 100))
            assertTrue(repository.approve(account, request.id, "333333", "envelope", now = 100))
            assertEquals(SessionIssueResult.NOT_PENDING, repository.markSessionIssued(account, request.id, now = 100))

            val approved = DeviceEnrollment(UUID.randomUUID(), account, "Mac", "key", "444444", expiresAt = 1_000)
            repository.create(approved)
            assertTrue(repository.approve(account, approved.id, "444444", "envelope", now = 100))
            assertEquals(SessionIssueResult.NOT_PENDING, repository.markSessionIssued(account, approved.id, now = 100))
        }
}
