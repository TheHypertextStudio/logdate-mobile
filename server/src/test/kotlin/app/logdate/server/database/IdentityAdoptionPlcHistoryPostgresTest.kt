package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountModeration
import app.logdate.server.identity.StoredHostedPlcOperation
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionPlcHistoryPostgresTest {
    @Test fun heldHistoryCannotAppend() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { history.save(next()) }
        }

    @Test fun heldHistoryCannotMarkPublication() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { history.markPublicationAttempted(original.id) }
        }

    @Test fun heldHistoryCannotReplaceCid() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { history.recordPublishedCid(original.id, "changed-cid") }
        }

    @Test fun oldHistoryCannotAppendAfterEpochAdvance() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { history.save(next()) }
        }

    @Test fun inactiveHistoryCannotMarkPublication() =
        withFixture {
            assertTrue(accounts.deactivateAccount(owner.id))
            refuses(IdentityFenceOutcome.DELETED) { history.markPublicationAttempted(original.id) }
        }

    @Test fun moderatedHistoryCannotAppend() =
        withFixture {
            assertTrue(accounts.setModeration(owner.id, AccountModeration.SUSPENDED))
            refuses(IdentityFenceOutcome.DELETED) { history.save(next()) }
        }

    @Test fun retiredOperationDidCannotBeWrittenByAnotherHostedAccount() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, " +
                        "identity_epoch, retired_at) " +
                        "VALUES ('did:web:retired.test', ?, ?, 1, ?)",
                ).use {
                    it.setObject(1, owner.id.toJavaUUID())
                    it.setString(2, Uuid.random().toString())
                    it.setTimestamp(3, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { history.save(next().copy(did = "did:web:retired.test")) }
        }

    @Test fun externalIdentityCannotAppendHostedHistory() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_kind = 'external' WHERE id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { history.save(next()) }
        }

    @Test fun newAccountEpochCannotRelabelOldHistoryCallback() =
        withFixture {
            advanceEpoch()
            refuses(IdentityFenceOutcome.CHANGED) {
                history.recordPublishedCid(original.id, "changed-cid", identityEpoch = 1)
            }
            refuses(IdentityFenceOutcome.CHANGED) { history.markPublicationAttempted(original.id, identityEpoch = 1) }
        }

    @Test fun originatingEpochIsStoredAndRequiredForPublicationMetadata() =
        withFixture {
            advanceEpoch()
            val update = next().copy(identityEpoch = 1)
            assertEquals(update, history.save(update))
            assertEquals(listOf(original, update), history.listByAccountId(owner.id))
            assertFailsWith<IdentityWriteRefusedException> { history.markPublicationAttempted(update.id) }
            assertTrue(history.markPublicationAttempted(update.id, identityEpoch = 1))
            assertTrue(history.recordPublishedCid(update.id, "published-cid", identityEpoch = 1))
            val published = update.copy(cid = "published-cid", publicationAttempted = true)
            assertEquals(published, history.listByDid(original.did).last())
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
        val original: StoredHostedPlcOperation,
    ) {
        val accounts = PostgreSQLAccountRepository()
        val history = PostgreSQLHostedPlcOperationRepository()

        fun next() = original.copy(id = Uuid.random(), prevCid = original.cid, cid = "next-cid", operationJson = "next")

        suspend fun refuses(
            outcome: IdentityFenceOutcome,
            mutate: suspend () -> Any,
        ) {
            assertEquals(outcome, assertFailsWith<IdentityWriteRefusedException> { mutate() }.outcome)
            assertEquals(listOf(original), history.listByAccountId(owner.id))
            assertEquals(false, history.hasPublicationAttempt(original.did))
        }

        suspend fun advanceEpoch() =
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }

        suspend fun hold() {
            val adoption = PostgreSQLIdentityAdoptionRepository(source)
            val binding = IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0)
            val operation =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    adoption.begin(binding, Clock.System.now()),
                ).value
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                adoption.transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
        }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "History owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            val original =
                StoredHostedPlcOperation(
                    Uuid.random(),
                    id,
                    requireNotNull(owner.did),
                    cid = "original-cid",
                    operationType = "plc_operation",
                    operationJson = "{}",
                    publicationAttempted = false,
                )
            PostgreSQLHostedPlcOperationRepository().save(original)
            Fixture(source, owner, original).block()
        }
}
