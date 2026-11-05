package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
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
class ExistingDidProvisioningTest {
    @Test fun malformedStoredIdentityIsNotSilentlyReplaced() =
        runTest {
            for (storedDid in listOf("", " ", "did:plc:malformed")) {
                val accounts = InMemoryAccountRepository()
                val keys = InMemorySigningKeyRepository()
                val keyService = SigningKeyService(keys, "synthetic-test-key")
                val service = AtprotoIdentityService(accounts, keyService, AtprotoIdentityConfig())
                val id = Uuid.random()
                val owner = Account(id, id.toHexString(), "Stored identity", did = storedDid, createdAt = Clock.System.now())
                assertTrue(accounts.create(owner))
                assertFailsWith<IdentityLifecycleConflictException> { service.ensureIdentity(owner) }
                assertEquals(owner, accounts.findById(id))
                assertNull(keys.findActiveByAccountId(id))
            }
        }

    @Test fun incompleteExistingIdentityDoesNotBecomeAnotherDid() =
        runTest {
            for (method in HostedAccountDidMethod.entries) {
                for (missingHandle in listOf(false, true)) {
                    val accounts = InMemoryAccountRepository()
                    val keys = InMemorySigningKeyRepository()
                    val history = InMemoryHostedPlcOperationRepository()
                    val keyService = SigningKeyService(keys, "synthetic-test-key")
                    val config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.test",
                            pdsServiceEndpoint = "https://logdate.test",
                            hostedAccountDidMethod = method,
                        )
                    val service =
                        AtprotoIdentityService(
                            accounts,
                            keyService,
                            config,
                            PlcIdentityService(
                                keyService,
                                config,
                                hostedPlcOperationRepository = history,
                            ),
                        )
                    val id = Uuid.random()
                    val original =
                        Account(
                            id,
                            id.toHexString(),
                            "Existing owner",
                            did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                            handle = if (missingHandle) null else "existing.logdate.test",
                            createdAt = Clock.System.now(),
                            signingKeyPublic = if (missingHandle) "zExisting" else null,
                        )
                    assertTrue(accounts.create(original))
                    assertFailsWith<IdentityLifecycleConflictException> { service.ensureIdentity(original) }
                    assertEquals(original, accounts.findById(id))
                    assertNull(keys.findActiveByAccountId(id))
                    assertTrue(history.listByAccountId(id).isEmpty())
                }
            }
        }
}
