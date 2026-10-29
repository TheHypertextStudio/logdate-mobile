package app.logdate.client.sync.space

import app.logdate.client.datastore.UserSession
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.storage.putBytes
import app.logdate.client.sync.cloud.InMemorySecureStorage
import app.logdate.client.sync.cloud.TestCryptoManager
import app.logdate.shared.model.Journal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class AccountBoundSpaceUploadTest {
    @Test
    fun `unbound and cross account roots refuse uploads before any data home write`() =
        runTest {
            for (storedOwner in listOf(null, "another-account")) {
                val device = SpaceDevice(FakeDataHome())
                val storage = InMemorySecureStorage()
                storage.putBytes("identity_key_v1", TEST_IDENTITY_KEY)
                storedOwner?.let { storage.putString("identity_account_id_v1", it) }
                val identity = IdentityKeyManager(storage, TestCryptoManager())
                var requestedOwner: String? = null
                device.accountIdentityKey = { owner ->
                    requestedOwner = owner
                    identity.getAccountIdentityKey(owner)
                }
                device.create(Journal(title = "Offline journal", created = CREATED, lastUpdated = CREATED))

                val result = device.manager.uploadPendingChanges()

                assertFalse(result.success)
                assertEquals(device.scope.ownerId, requestedOwner, "Key admission uses the captured upload owner")
                assertEquals(0, device.home.writes)
                assertTrue(device.pendingJournals().isNotEmpty(), "Blocked entries remain available for recovery")
                assertEquals(storedOwner, storage.getString("identity_account_id_v1"))
                assertContentEquals(TEST_IDENTITY_KEY, identity.getIdentityKey())
            }
        }

    @Test
    fun `background provisioning cannot approve an offline root for a personal space account`() =
        runTest {
            for (owner in listOf(null, "another-account", "test-account-id")) {
                val storage = InMemorySecureStorage()
                storage.putBytes("identity_key_v1", TEST_IDENTITY_KEY)
                owner?.let { storage.putString("identity_account_id_v1", it) }
                val identity = IdentityKeyManager(storage, TestCryptoManager())
                val device = SpaceDevice(FakeDataHome(), identityManager = identity)
                device.accountIdentityKey = identity::getAccountIdentityKey
                device.create(Journal(title = "Retained offline journal", created = CREATED, lastUpdated = CREATED))

                val result = device.manager.uploadPendingChanges()

                assertEquals(owner == "test-account-id", result.success)
                assertEquals(if (owner == "test-account-id") 1 else 0, device.home.writes)
                assertEquals(owner, storage.getString("identity_account_id_v1"))
                assertContentEquals(TEST_IDENTITY_KEY, identity.getIdentityKey())
            }
        }

    @Test
    fun `a root bound to the captured upload owner still seals a readable journal`() =
        runTest {
            val device = SpaceDevice(FakeDataHome())
            val storage = InMemorySecureStorage()
            storage.putBytes("identity_key_v1", TEST_IDENTITY_KEY)
            storage.putString("identity_account_id_v1", device.scope.ownerId)
            val identity = IdentityKeyManager(storage, TestCryptoManager())
            device.accountIdentityKey = identity::getAccountIdentityKey
            val journal = Journal(title = "Account journal", created = CREATED, lastUpdated = CREATED)
            device.create(journal)

            val result = device.manager.uploadPendingChanges()

            assertTrue(result.success, result.errors.toString())
            assertEquals(1, device.home.writes)
            assertEquals(JOURNAL_COLLECTION, device.home.openJournal(journal.id.toString()).collection)
            assertTrue(device.pendingJournals().isEmpty())
        }

    @Test
    fun `changing the session during key admission cannot combine the old root with a new account`() =
        runTest {
            val device = SpaceDevice(FakeDataHome())
            val originalScope = device.scope
            val storage = InMemorySecureStorage()
            storage.putBytes("identity_key_v1", TEST_IDENTITY_KEY)
            storage.putString("identity_account_id_v1", originalScope.ownerId)
            val identity = IdentityKeyManager(storage, TestCryptoManager())
            val admissions = mutableListOf<String>()
            device.accountIdentityKey = { owner ->
                admissions += owner
                device.sessions.saveSession(UserSession("other-access", "other-refresh", "another-account"))
                identity.getAccountIdentityKey(owner)
            }
            device.create(Journal(title = "Retained journal", created = CREATED, lastUpdated = CREATED))

            val result = device.manager.uploadPendingChanges()

            assertFalse(result.success)
            assertEquals(originalScope.ownerId, admissions.first())
            assertTrue(admissions.drop(1).all { it == "another-account" }, "Retries must admit only the newly captured scope")
            assertTrue(result.errors.any { it.cause is SpaceScopeChangedException }, "The original admission must fail its scope fence")
            assertEquals(0, device.home.writes)
            assertTrue(device.pendingJournals().isNotEmpty())
            assertEquals(originalScope.ownerId, storage.getString("identity_account_id_v1"))
        }

    private companion object {
        val CREATED = Instant.parse("2026-10-01T09:00:00Z")
    }
}
