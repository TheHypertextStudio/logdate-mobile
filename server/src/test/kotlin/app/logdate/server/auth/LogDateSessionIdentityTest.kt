package app.logdate.server.auth

import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LogDateSessionIdentityTest {
    @Test fun currentExternalAuthorityIsUsedWithoutRestoringHostedMetadata() =
        runTest {
            val id = Uuid.random()
            val current =
                Account(
                    id,
                    "owner",
                    "Owner",
                    did = "did:web:current.test",
                    createdAt = Clock.System.now(),
                    identityKind = AccountIdentityKind.EXTERNAL,
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                    identityEpoch = 1,
                )
            val accounts =
                object : AccountRepository by InMemoryAccountRepository() {
                    override suspend fun findById(id: Uuid) = current.takeIf { it.id == id }
                }
            val keys = SigningKeyService(InMemorySigningKeyRepository(), "synthetic-test-kek")
            val identity = AtprotoIdentityService(accounts, keys, AtprotoIdentityConfig())
            assertEquals(current, ensureLogDateSessionIdentity(id, accounts, identity))
            assertNull(keys.findActiveKey(id))
        }

    @Test fun missingInactiveModeratedAndUnknownAccountsCannotUseMetadataDegradation() =
        runTest {
            val base = InMemoryAccountRepository()
            val id = Uuid.random()
            val owner = Account(id, "owner", "Owner", did = "did:web:owner.test", createdAt = Clock.System.now())
            assertTrue(base.create(owner))
            var current: Account? = owner
            val accounts =
                object : AccountRepository by base {
                    override suspend fun findById(id: Uuid) = current?.takeIf { it.id == id }
                }
            val keys = SigningKeyService(InMemorySigningKeyRepository(), "synthetic-test-kek")
            val identity = AtprotoIdentityService(accounts, keys, AtprotoIdentityConfig())
            assertEquals(owner, ensureLogDateSessionIdentity(id, accounts, identity))
            for (refused in listOf(
                null,
                owner.copy(isActive = false),
                owner.copy(moderation = AccountModeration.SUSPENDED),
                owner.copy(identityKind = AccountIdentityKind.UNRECOGNIZED),
            )) {
                current = refused
                assertNull(ensureLogDateSessionIdentity(id, accounts, identity))
            }
            assertNull(keys.findActiveKey(id))
            assertEquals(owner, base.findById(id))
        }

    @Test fun malformedStoredDidDoesNotCreateASessionOrReplacementIdentity() =
        runTest {
            val accounts = InMemoryAccountRepository()
            val id = Uuid.random()
            val owner = Account(id, "owner", "Owner", did = "malformed-stored-identity", createdAt = Clock.System.now())
            assertTrue(accounts.create(owner))
            val keys = SigningKeyService(InMemorySigningKeyRepository(), "synthetic-test-kek")
            val identity = AtprotoIdentityService(accounts, keys, AtprotoIdentityConfig())
            assertFailsWith<IdentityLifecycleConflictException> { ensureLogDateSessionIdentity(id, accounts, identity) }
            assertEquals(owner, accounts.findById(id))
            assertNull(keys.findActiveKey(id))
        }
}
