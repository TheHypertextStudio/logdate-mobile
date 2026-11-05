package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LogDateCollectionKind
import app.logdate.server.logdate.ResourceKind
import app.logdate.server.logdate.ResourceRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import studio.hypertext.atproto.identity.AtprotoDid
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionCollectionWritesPostgresTest {
    @Test fun heldMetadataUpsertPreservesVersionAndRows() =
        withFixture {
            hold()
            assertFailsWith<IdentityWriteRefusedException> { metadata.upsert(id, canonical, LogDateCollectionKind.ENTRY, "entry") }
            assertOriginal()
        }

    @Test fun heldMetadataBatchCannotCreatePartialRows() =
        withFixture {
            hold()
            assertFailsWith<IdentityWriteRefusedException> {
                metadata.upsertBatch(id, canonical, LogDateCollectionKind.ENTRY, listOf("new-one", "new-two"))
            }
            assertNull(metadata.metadata(id, LogDateCollectionKind.ENTRY, "new-one"))
            assertNull(metadata.metadata(id, LogDateCollectionKind.ENTRY, "new-two"))
            assertOriginal()
        }

    @Test fun heldDeletionCannotChangeVersionOrCreateAnUnknownTombstone() =
        withFixture {
            hold()
            assertFailsWith<IdentityWriteRefusedException> {
                metadata.delete(id, canonical, LogDateCollectionKind.ENTRY, "unknown", 99, allowMissing = true)
            }
            assertNull(metadata.tombstone(id, LogDateCollectionKind.ENTRY, "unknown"))
            assertOriginal()
        }

    @Test fun heldMomentRetentionPreservesItsSourceTombstone() =
        withFixture {
            hold()
            assertFailsWith<IdentityWriteRefusedException> { metadata.purgeTombstones(id, 99) }
            assertOriginal()
        }

    @Test fun globalRetentionSkipsHeldOwnersAndStillPurgesEligibleOwners() =
        withFixture {
            val other = Uuid.random().toJavaUUID()
            createTestAccount(other)
            metadata.delete(other, canonical, LogDateCollectionKind.MOMENT, "other-moment", 2, allowMissing = true)
            hold()
            metadata.purgeAllTombstones(99)
            assertNull(metadata.tombstone(other, LogDateCollectionKind.MOMENT, "other-moment"))
            assertOriginal()
        }

    @Test fun unversionedMetadataCannotWriteAfterIdentityAdvance() =
        withFixture {
            advance()
            assertEquals(
                IdentityFenceOutcome.CHANGED,
                assertFailsWith<IdentityWriteRefusedException> {
                    metadata.upsert(id, canonical, LogDateCollectionKind.ENTRY, "entry")
                }.outcome,
            )
            assertOriginal()
        }

    @Test fun retiredCanonicalRepoCannotBeMutatedThroughAnotherPreferredDid() =
        withFixture {
            retire(canonical.toString())
            assertFailsWith<IdentityWriteRefusedException> {
                metadata.upsert(id, AtprotoDid.require(requireNotNull(owner.did)), LogDateCollectionKind.ENTRY, "entry")
            }
            assertOriginal()
        }

    @Test fun retiredPreferredDidCannotBeWrittenIntoAnExistingCollection() =
        withFixture {
            val retired = "did:plc:cccccccccccccccccccccccc"
            retire(retired)
            assertFailsWith<IdentityWriteRefusedException> {
                metadata.upsert(id, AtprotoDid.require(retired), LogDateCollectionKind.ENTRY, "entry")
            }
            assertOriginal()
        }

    @Test fun heldResourcePointerCannotChange() =
        withFixture {
            hold()
            assertFailsWith<IdentityWriteRefusedException> { pointers.upsert(pointer.copy(kind = ResourceKind.NOTE)) }
            assertEquals(pointer, pointers.find(pointer.resourceId))
        }

    @Test fun heldResourcePointerCannotBeDeleted() =
        withFixture {
            hold()
            assertFailsWith<IdentityWriteRefusedException> { pointers.delete(pointer.resourceId, id) }
            assertEquals(pointer, pointers.find(pointer.resourceId))
        }

    @Test fun pointerDeletionIsBoundToTheOriginatingAccount() =
        withFixture {
            val other = Uuid.random().toJavaUUID()
            createTestAccount(other)
            pointers.delete(pointer.resourceId, other)
            assertEquals(pointer, pointers.find(pointer.resourceId))
        }

    @Test fun pointerOwnershipCannotBeReassignedByUpsert() =
        withFixture {
            val other = Uuid.random().toJavaUUID()
            createTestAccount(other)
            assertFailsWith<IdentityWriteRefusedException> { pointers.upsert(pointer.copy(accountId = other)) }
            assertEquals(pointer, pointers.find(pointer.resourceId))
        }

    @Test fun pointerMutationCannotBorrowOrSilentlyUpgradeAnEpoch() =
        withFixture {
            assertFailsWith<IdentityWriteRefusedException> { pointers.upsert(pointer, identityEpoch = 1) }
            advance()
            assertFailsWith<IdentityWriteRefusedException> { pointers.delete(pointer.resourceId, id) }
            assertEquals(pointer, pointers.find(pointer.resourceId))
            pointers.delete(pointer.resourceId, id, identityEpoch = 1)
            assertNull(pointers.find(pointer.resourceId))
        }

    @Test fun competingPointerClaimsHaveOnlyOneOwner() =
        withFixture {
            val other = Uuid.random().toJavaUUID()
            createTestAccount(other)
            val candidates = listOf(pointer.copy(resourceId = "raced"), pointer.copy(resourceId = "raced", accountId = other))
            val results =
                coroutineScope {
                    candidates
                        .map { candidate ->
                            async(Dispatchers.IO) { candidate to runCatching { pointers.upsert(candidate) } }
                        }.awaitAll()
                }
            assertEquals(1, results.count { it.second.isSuccess })
            assertIs<IdentityWriteRefusedException>(results.single { it.second.isFailure }.second.exceptionOrNull())
            assertEquals(results.single { it.second.isSuccess }.first, pointers.find("raced"))
        }

    @Test fun accountLockPrecedesCollectionStateLock() =
        withFixture {
            coroutineScope {
                source.connection.use { control ->
                    control.autoCommit = false
                    control
                        .prepareStatement(
                            "SELECT user_id FROM logdate_collection_states " +
                                "WHERE user_id = ? FOR UPDATE",
                        ).use {
                            it.setObject(1, id)
                            it.executeQuery().close()
                        }
                    val writer =
                        async(Dispatchers.IO) { runCatching { metadata.upsert(id, canonical, LogDateCollectionKind.ENTRY, "next") } }
                    try {
                        withTimeout(5_000) { while (waitingOnState() == 0) delay(20) }
                        val refusal =
                            assertFailsWith<SQLException> {
                                source.blueskyTransaction {
                                    prepareStatement(
                                        "SELECT id FROM accounts WHERE id = ? " +
                                            "FOR UPDATE NOWAIT",
                                    ).use {
                                        it.setObject(1, id)
                                        it.executeQuery().close()
                                    }
                                }
                            }
                        assertEquals("55P03", refusal.sqlState)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            control.rollback()
                            writer.await()
                        }
                    }
                    writer.await().getOrThrow()
                    assertNotNull(metadata.metadata(id, LogDateCollectionKind.ENTRY, "next"))
                }
            }
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val canonical = AtprotoDid.require("did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
        val metadata = PostgreSQLLogDateCollectionsMetadataStore()
        val pointers = PostgreSQLResourceRouteRepository()
        val pointer = ResourceRoute("resource", id, ResourceKind.JOURNAL)
        private val adoption = PostgreSQLIdentityAdoptionRepository(source)
        private var state: app.logdate.server.logdate.LogDateCollectionsState? = null
        private var entry: app.logdate.server.logdate.LogDateCollectionMetadata? = null
        private var moment: app.logdate.server.logdate.LogDateCollectionMetadata? = null

        suspend fun seed() {
            entry = metadata.upsert(id, canonical, LogDateCollectionKind.ENTRY, "entry")
            moment = metadata.delete(id, canonical, LogDateCollectionKind.MOMENT, "moment", 2, allowMissing = true)
            state = metadata.state(id)
            pointers.upsert(pointer)
        }

        suspend fun assertOriginal() {
            assertEquals(state, metadata.state(id))
            assertEquals(entry, metadata.metadata(id, LogDateCollectionKind.ENTRY, "entry"))
            assertEquals(moment, metadata.tombstone(id, LogDateCollectionKind.MOMENT, "moment"))
        }

        suspend fun hold() {
            val operation =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    adoption.begin(
                        IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                        Clock.System.now(),
                    ),
                ).value
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                adoption.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now()),
            )
        }

        suspend fun advance() =
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }

        suspend fun retire(did: String) =
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, " +
                        "identity_epoch, retired_at) " +
                        "VALUES (?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, did)
                    it.setObject(2, id)
                    it.setString(3, Uuid.random().toString())
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }

        suspend fun waitingOnState(): Int =
            source.blueskyTransaction {
                prepareStatement(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() " +
                        "AND wait_event_type = 'Lock' AND query LIKE '%logdate_collection_states%' " +
                        "AND query NOT LIKE '%pg_stat_activity%'",
                ).use {
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getInt(1)
                    }
                }
            }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val accountId = Uuid.random()
            val owner =
                Account(
                    accountId,
                    accountId.toHexString(),
                    "Collections owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
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
            Fixture(source, assertNotNull(PostgreSQLAccountRepository().findById(owner.id))).apply { seed() }.block()
        }
}
