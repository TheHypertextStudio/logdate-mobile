@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.JournalMergeOperation
import app.logdate.server.logdate.ObjectUploadKind
import app.logdate.space.SpaceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class IdentityAdoptionJournalMergePostgresTest {
    @Test fun heldMergeCannotSaveOrReplaceItsMembershipInventory() =
        fixture {
            store.save(id, original)
            hold()
            refuses(IdentityFenceOutcome.HELD) { store.save(id, original.copy(contentIds = listOf("changed"))) }
            refuses(IdentityFenceOutcome.HELD) {
                store.replaceIncomplete(id, original.operationId, original.copy(operationId = "replacement"))
            }
            assertEquals(listOf(original), store.operations(id))
        }

    @Test fun heldMergeCannotRemoveAnUnstartedOperation() =
        fixture {
            store.save(id, original)
            hold()
            refuses(IdentityFenceOutcome.HELD) { store.removeUnstarted(id, original.operationId) }
            assertEquals(listOf(original), store.operations(id))
        }

    @Test fun heldMergeTombstoneCannotBePurged() =
        fixture {
            val completed = original.copy(started = true, completed = true, deletedAt = 10, tombstoneVersion = 11)
            store.save(id, completed)
            hold()
            refuses(IdentityFenceOutcome.HELD) { store.purgeTombstones(id, 20) }
            assertEquals(listOf(completed), store.operations(id))
        }

    @Test fun globalMergeRetentionSkipsHeldOwnerAndStillPurgesAnotherOwner() =
        fixture {
            val other = createOwner("did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
            val completed = original.copy(started = true, completed = true, deletedAt = 10, tombstoneVersion = 11)
            store.save(id, completed)
            store.save(other.id.toJavaUUID(), completed)
            hold()
            store.purgeTombstones(null, 20)
            assertEquals(listOf(completed), store.operations(id))
            assertEquals(listOf(completed.copy(tombstoneVersion = null)), store.operations(other.id.toJavaUUID()))
        }

    @Test fun epochAdvanceCannotAuthorizeLegacyMergeMetadata() =
        fixture {
            store.save(id, original)
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { store.save(id, original.copy(started = true)) }
            refuses(IdentityFenceOutcome.CHANGED) { store.removeUnstarted(id, original.operationId) }
            assertEquals(listOf(original), store.operations(id))
        }

    @Test fun aDeletedAccountCannotResurrectMergeMetadata() =
        fixture {
            source.blueskyTransaction {
                prepareStatement("DELETE FROM accounts WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.DELETED) { store.save(id, original) }
            assertTrue(store.operations(id).isEmpty())
        }

    @Test fun aPreviouslyAcquiredMergeLockCannotBypassANewerHold() =
        fixture {
            withTimeout(10_000) {
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val writer =
                        async {
                            runCatching {
                                store.withAccountLock(id) {
                                    entered.complete(Unit)
                                    release.await()
                                    store.save(id, original)
                                }
                            }
                        }
                    entered.await()
                    try {
                        hold()
                    } finally {
                        release.complete(Unit)
                    }
                    val failure = assertIs<IdentityWriteRefusedException>(writer.await().exceptionOrNull())
                    assertEquals(IdentityFenceOutcome.HELD, failure.outcome)
                    assertTrue(store.operations(id).isEmpty())
                }
            }
        }

    @Test fun rawMergeTransactionCannotAdmitProviderWork() =
        fixture {
            assertFailsWith<IdentityLifecycleConflictException> {
                store.withAccountLock(id) {
                    PostgreSQLObjectUploads().begin(
                        IdentityWriteExpectation(owner.id, 0),
                        null,
                        "synthetic/forbidden-provider-copy",
                        ObjectUploadKind.SPACE,
                        SpaceRef.personal(requireNotNull(owner.did)).uri,
                    )
                }
            }
            source.blueskyTransaction {
                prepareStatement("SELECT COUNT(*) FROM object_upload_intents WHERE account_id = ?").use {
                    it.setObject(1, id)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0L, rows.getLong(1))
                    }
                }
            }
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val store = PostgreSQLJournalMergeStore(source)
        val original = JournalMergeOperation("operation", "source", "destination", listOf("note"), listOf("note"))

        suspend fun refuses(
            outcome: IdentityFenceOutcome,
            action: suspend () -> Unit,
        ) {
            assertEquals(outcome, assertFailsWith<IdentityWriteRefusedException> { action() }.outcome)
        }

        suspend fun hold() {
            val adoption = PostgreSQLIdentityAdoptionRepository(source)
            val proposal =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    adoption.begin(
                        IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                        Clock.System.now(),
                    ),
                ).value
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                adoption.transition(owner.id, proposal.id, proposal.revision, IdentityAdoptionPhase.FENCED, Clock.System.now()),
            )
        }
    }

    private fun fixture(action: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val owner = createOwner("did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            Fixture(source, owner).action()
        }

    private companion object {
        suspend fun createOwner(did: String): Account {
            val id = Uuid.random()
            val owner = Account(id, id.toHexString(), "Synthetic merge owner", did = did, createdAt = Clock.System.now())
            assertTrue(PostgreSQLAccountRepository().create(owner))
            return owner
        }
    }
}
