package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.PlcIdentityService
import app.logdate.server.identity.SigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.identity.StoredSigningKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class InitialIdentityProvisioningPostgresTest {
    @Test fun simultaneousMissingKeyReadsProduceOneDurableActiveKey() =
        withFixture {
            coroutineScope {
                val arrivals = AtomicInteger()
                val bothReadMissing = CompletableDeferred<Unit>()

                fun instance(): SigningKeyService {
                    val repository =
                        object : SigningKeyRepository by PostgreSQLSigningKeyRepository() {
                            override suspend fun findActiveByAccountId(accountId: Uuid): StoredSigningKey? {
                                val found = PostgreSQLSigningKeyRepository().findActiveByAccountId(accountId)
                                if (found == null) {
                                    if (arrivals.incrementAndGet() == 2) bothReadMissing.complete(Unit)
                                    withTimeout(5_000) { bothReadMissing.await() }
                                }
                                return found
                            }
                        }
                    return SigningKeyService(repository, KEK)
                }
                val keys =
                    withTimeout(10_000) {
                        listOf(instance(), instance())
                            .map { service ->
                                async(Dispatchers.IO) { service.ensureActiveKey(owner.id) }
                            }.awaitAll()
                    }
                assertEquals(1, keys.map { it.id }.distinct().size)
                assertEquals(1, activeKeys())
                assertEquals(keys.first().id, assertNotNull(PostgreSQLSigningKeyRepository().findActiveByAccountId(owner.id)).id)
                assertEquals(keys.first().id, keyService().ensureActiveKey(owner.id).id)
            }
        }

    @Test fun ambiguousExistingKeysNeedReconciliationInsteadOfAnotherKey() =
        withFixture {
            val first =
                StoredSigningKey(
                    Uuid.random(),
                    owner.id,
                    publicKeyMultibase = "zFirst",
                    privateKeyEncrypted = "synthetic-first",
                    createdAt = Clock.System.now(),
                )
            val repository = PostgreSQLSigningKeyRepository()
            repository.save(first)
            repository.save(first.copy(id = Uuid.random(), publicKeyMultibase = "zSecond"))
            assertFailsWith<IdentityLifecycleConflictException> { keyService().ensureActiveKey(owner.id) }
            assertEquals(2, activeKeys())
        }

    @Test fun genesisCandidateSurvivesRestartAndDifferentRequestedHandle() =
        withFixture {
            val original = plc().provisionHostedDid(owner.id, "original.logdate.test")
            val resumed = plc().provisionHostedDid(owner.id, "different.logdate.test")
            assertEquals(original.did, resumed.did)
            assertEquals(original.operation, resumed.operation)
            assertEquals(1, PostgreSQLHostedPlcOperationRepository().listByAccountId(owner.id).size)
            assertEquals(1, activeKeys())
        }

    @Test fun accountAssignmentUsesThePersistedGenesisHandleAfterRestart() =
        withFixture {
            val original = plc().provisionHostedDid(owner.id, "original.logdate.test")
            val keys = keyService()
            val service =
                AtprotoIdentityService(
                    PostgreSQLAccountRepository(),
                    keys,
                    config,
                    PlcIdentityService(keys, config, hostedPlcOperationRepository = PostgreSQLHostedPlcOperationRepository()),
                )
            val resumed = service.ensureIdentity(owner)
            assertEquals(original.did, resumed.did)
            assertEquals("original.logdate.test", resumed.handle)
            assertEquals(original.publicKeyMultibase, resumed.signingKeyPublic)
            assertEquals(1, PostgreSQLHostedPlcOperationRepository().listByAccountId(owner.id).size)
        }

    @Test fun durableGenesisCannotBeClaimedByAnotherAccountBeforeAssignment() =
        withFixture {
            val original = plc().provisionHostedDid(owner.id, "original.logdate.test")
            val id = Uuid.random()
            val claimant = Account(id, id.toHexString(), "Another owner", did = original.did, createdAt = Clock.System.now())
            assertEquals(false, PostgreSQLAccountRepository().create(claimant))
            assertEquals(1, PostgreSQLHostedPlcOperationRepository().listByDid(original.did).size)
        }

    @Test fun independentServersInstallTheSamePersistedIdentity() =
        withFixture {
            coroutineScope {
                val arrivals = AtomicInteger()
                val bothReadUnassigned = CompletableDeferred<Unit>()

                fun instance(domain: String): AtprotoIdentityService {
                    val accounts =
                        object : AccountRepository by PostgreSQLAccountRepository() {
                            override suspend fun findById(id: Uuid): Account? {
                                val found = PostgreSQLAccountRepository().findById(id)
                                if (found != null && found.did == null) {
                                    if (arrivals.incrementAndGet() == 2) bothReadUnassigned.complete(Unit)
                                    withTimeout(5_000) { bothReadUnassigned.await() }
                                }
                                return found
                            }
                        }
                    val keys = keyService()
                    val config = config.copy(handleDomain = domain)
                    return AtprotoIdentityService(
                        accounts,
                        keys,
                        config,
                        PlcIdentityService(keys, config, hostedPlcOperationRepository = PostgreSQLHostedPlcOperationRepository()),
                    )
                }
                val identities =
                    withTimeout(10_000) {
                        listOf(instance("left.logdate.test"), instance("right.logdate.test"))
                            .map { service ->
                                async(Dispatchers.IO) { service.ensureIdentity(owner) }
                            }.awaitAll()
                    }
                val stored = assertNotNull(PostgreSQLAccountRepository().findById(owner.id))
                assertEquals(1, identities.map { it.did }.distinct().size)
                assertTrue(
                    identities.all { it.did == stored.did && it.handle == stored.handle && it.signingKeyPublic == stored.signingKeyPublic },
                )
                val history = PostgreSQLHostedPlcOperationRepository().listByAccountId(owner.id)
                assertEquals(1, history.size)
                assertEquals(stored.did, history.single().did)
                assertEquals(1, activeKeys())
            }
        }

    @Test fun missingGenesisPrivateKeyIsNotReplacedDuringResume() =
        withFixture {
            val original = plc().provisionHostedDid(owner.id, "original.logdate.test")
            removeKeys()
            assertFailsWith<IdentityLifecycleConflictException> { plc().provisionHostedDid(owner.id, "changed.logdate.test") }
            assertEquals(0, activeKeys())
            assertEquals(listOf(original.did), PostgreSQLHostedPlcOperationRepository().listByAccountId(owner.id).map { it.did })
        }

    @Test fun assignedAuthorityCannotGetAnUnrelatedReplacementForAMissingKey() =
        withFixture {
            val keys = keyService()
            val stored = AtprotoIdentityService(PostgreSQLAccountRepository(), keys, config, plc()).ensureIdentity(owner)
            removeKeys()
            assertFailsWith<IdentityLifecycleConflictException> { keyService().ensureActiveKey(owner.id) }
            assertEquals(0, activeKeys())
            assertEquals(stored, PostgreSQLAccountRepository().findById(owner.id))
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val config = AtprotoIdentityConfig(handleDomain = "logdate.test", pdsServiceEndpoint = "https://logdate.test")

        fun keyService() = SigningKeyService(PostgreSQLSigningKeyRepository(), KEK)

        fun plc() = PlcIdentityService(keyService(), config, hostedPlcOperationRepository = PostgreSQLHostedPlcOperationRepository())

        suspend fun removeKeys() =
            source.blueskyTransaction {
                prepareStatement("DELETE FROM signing_keys WHERE account_id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }

        suspend fun activeKeys(): Int =
            source.blueskyTransaction {
                prepareStatement("SELECT COUNT(*) FROM signing_keys WHERE account_id = ? AND revoked_at IS NULL").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner = Account(id, id.toHexString(), "New owner", createdAt = Clock.System.now())
            assertTrue(PostgreSQLAccountRepository().create(owner))
            Fixture(source, owner).block()
        }

    private companion object {
        const val KEK = "synthetic-provisioning-kek"
    }
}
