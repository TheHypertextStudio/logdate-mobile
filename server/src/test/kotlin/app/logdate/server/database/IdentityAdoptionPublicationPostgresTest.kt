package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.PlcIdentityService
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.identity.StoredHostedPlcOperation
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcLogEntry
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionPublicationPostgresTest {
    @Test fun durableAttemptRefusesAnAlreadyPreparedHold() =
        withFixture {
            val operation = prepare()
            assertTrue(history.beginPublicationAttempt(owner.id, did))
            assertEquals(true, history.hasPublicationAttempt(did))
            assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE), fence(operation))
            assertEquals(IdentityAdoptionPhase.PREPARING, adoption.get(owner.id, operation.id)?.phase)
        }

    @Test fun heldSourceCannotStartPublicationOrReachProvider() =
        withFixture {
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(fence(prepare()))
            val provider = Provider(this)
            assertEquals(
                IdentityFenceOutcome.HELD,
                assertFailsWith<IdentityWriteRefusedException> {
                    rotate(provider)
                }.outcome,
            )
            assertEquals(0, provider.submissions)
            assertEquals(false, history.hasPublicationAttempt(did))
            assertEquals(listOf(original), history.listByAccountId(owner.id))
        }

    @Test fun anotherAccountCannotAuthorizePublicationOfThisDid() =
        withFixture {
            val id = Uuid.random()
            assertTrue(accounts.create(Account(id, id.toHexString(), "Other", createdAt = Clock.System.now())))
            assertEquals(
                IdentityFenceOutcome.CHANGED,
                assertFailsWith<IdentityWriteRefusedException> {
                    history.beginPublicationAttempt(id, did)
                }.outcome,
            )
            assertEquals(false, history.hasPublicationAttempt(did))
        }

    @Test fun oldEpochCannotAuthorizeProviderSubmission() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val provider = Provider(this)
            assertEquals(
                IdentityFenceOutcome.CHANGED,
                assertFailsWith<IdentityWriteRefusedException> {
                    rotate(provider)
                }.outcome,
            )
            assertEquals(0, provider.submissions)
            assertEquals(false, history.hasPublicationAttempt(did))
        }

    @Test fun providerWaitKeepsNoSqlTransactionAndBlocksHoldAcrossInstances() =
        withFixture {
            coroutineScope {
                val operation = prepare()
                val provider = Provider(this@withFixture)
                provider.pause = true
                val publishing =
                    async(Dispatchers.IO) {
                        runCatching {
                            rotate(provider)
                        }
                    }
                try {
                    withTimeout(5_000) { provider.entered.await() }
                    assertEquals(0, idleTransactions())
                    assertEquals(true, PostgreSQLHostedPlcOperationRepository().hasPublicationAttempt(did))
                    assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE), fence(operation))
                    provider.release.complete(Unit)
                    assertSame(provider.failure, withTimeout(5_000) { publishing.await() }.exceptionOrNull())
                    assertEquals(true, history.hasPublicationAttempt(did))
                    assertEquals(listOf(original), history.listByAccountId(owner.id))
                } finally {
                    provider.release.complete(Unit)
                    withContext(NonCancellable) { withTimeout(5_000) { publishing.await() } }
                }
            }
        }

    @Test fun simultaneousHoldAndPublicationHaveExactlyOneWinner() =
        withFixture {
            coroutineScope {
                val operation = prepare()
                val start = CompletableDeferred<Unit>()
                val publishing =
                    async(Dispatchers.IO) {
                        start.await()
                        runCatching { history.beginPublicationAttempt(owner.id, did) }
                    }
                val holding =
                    async(Dispatchers.IO) {
                        start.await()
                        fence(operation)
                    }
                start.complete(Unit)
                val publication = withTimeout(5_000) { publishing.await() }
                val held = withTimeout(5_000) { holding.await() }
                val publicationWon = publication.getOrNull() == true
                assertEquals(1, listOf(publicationWon, held is IdentityAdoptionOutcome.Accepted<*>).count { it })
                assertEquals(publicationWon, history.hasPublicationAttempt(did))
                assertEquals(
                    if (publicationWon) IdentityAdoptionPhase.PREPARING else IdentityAdoptionPhase.FENCED,
                    adoption.get(owner.id, operation.id)?.phase,
                )
                if (!publicationWon) {
                    assertEquals(
                        IdentityFenceOutcome.HELD,
                        assertIs<IdentityWriteRefusedException>(publication.exceptionOrNull()).outcome,
                    )
                }
            }
        }

    @Test fun deletedAccountCannotAuthorizePublication() =
        withFixture {
            assertTrue(accounts.deleteAccount(owner.id))
            assertEquals(
                IdentityFenceOutcome.DELETED,
                assertFailsWith<IdentityWriteRefusedException> {
                    history.beginPublicationAttempt(owner.id, did)
                }.outcome,
            )
            assertEquals(false, history.hasPublicationAttempt(did))
        }

    @Test fun ambientTransactionCannotKeepPublicationEvidenceUncommittedDuringProviderIo() =
        withFixture {
            val provider = Provider(this)
            assertFailsWith<IdentityLifecycleConflictException> {
                org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                    runBlocking { rotate(provider) }
                }
            }
            assertEquals(0, provider.submissions)
            assertEquals(false, history.hasPublicationAttempt(did))
            assertEquals(listOf(original), history.listByAccountId(owner.id))
        }

    private class Provider(
        val fixture: Fixture,
    ) : PlcDirectoryClient {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = IllegalStateException("Synthetic provider refusal")
        var submissions = 0
        var pause = false

        override suspend fun getDocument(did: AtprotoDid) = error("unused")

        override suspend fun getOperationLog(did: AtprotoDid) = error("unused")

        override suspend fun export(
            after: String?,
            count: Int?,
        ) = error("unused")

        override suspend fun getAuditLog(did: AtprotoDid): Result<List<PlcIndexedOperation>> =
            Result.success(
                listOf(
                    PlcIndexedOperation(
                        did,
                        fixture.privatePlc.storedGenesisOperation(fixture.original),
                        requireNotNull(fixture.original.cid),
                        false,
                        "2026-10-29T00:00:00Z",
                    ),
                ),
            )

        override suspend fun submit(
            did: AtprotoDid,
            entry: PlcLogEntry,
        ): Result<Unit> {
            submissions++
            entered.complete(Unit)
            if (pause) release.await()
            return Result.failure(failure)
        }
    }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
        val keys: SigningKeyService,
        val privatePlc: PlcIdentityService,
        val original: StoredHostedPlcOperation,
    ) {
        val did = requireNotNull(owner.did)
        val accounts = PostgreSQLAccountRepository()
        val history = PostgreSQLHostedPlcOperationRepository()
        val adoption = PostgreSQLIdentityAdoptionRepository(source)

        fun publisher(provider: Provider) =
            PlcIdentityService(
                keys,
                AtprotoIdentityConfig(publishHostedPlcOperations = true),
                provider,
                history,
            )

        suspend fun rotate(provider: Provider) =
            publisher(provider).rotateHostedDid(
                owner.id,
                AtprotoDid.require(did),
                requireNotNull(owner.handle),
            )

        suspend fun prepare(): IdentityAdoptionOperation {
            val binding = IdentityAdoptionBinding(owner.id, did, "did:web:target.test", "device", 0)
            return assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                adoption.begin(binding, Clock.System.now()),
            ).value
        }

        suspend fun fence(operation: IdentityAdoptionOperation) =
            adoption.transition(
                owner.id,
                operation.id,
                operation.revision,
                IdentityAdoptionPhase.FENCED,
                Clock.System.now(),
            )

        suspend fun idleTransactions(): Int =
            source.blueskyTransaction {
                prepareStatement(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() " +
                        "AND state LIKE 'idle in transaction%'",
                ).use {
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
            val owner = Account(id, id.toHexString(), "Private owner", createdAt = Clock.System.now())
            val accounts = PostgreSQLAccountRepository()
            assertTrue(accounts.create(owner))
            val keys = SigningKeyService(PostgreSQLSigningKeyRepository(), "synthetic-test-kek")
            val config = AtprotoIdentityConfig(handleDomain = "logdate.test", pdsServiceEndpoint = "https://logdate.test")
            val history = PostgreSQLHostedPlcOperationRepository()
            val privatePlc = PlcIdentityService(keys, config, hostedPlcOperationRepository = history)
            val provisioned = AtprotoIdentityService(accounts, keys, config, privatePlc).ensureIdentity(owner)
            val original = history.listByAccountId(id).single()
            assertNotNull(original.cid)
            Fixture(source, provisioned, keys, privatePlc, original).block()
        }
}
