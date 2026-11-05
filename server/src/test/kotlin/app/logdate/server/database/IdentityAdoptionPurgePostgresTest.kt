package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpacePurgeResult
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.space.SpaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionPurgePostgresTest {
    @Test
    fun heldTombstonesKeepTheVerifiedChangeInventory() =
        withFixture {
            val input = write()
            store.put(input)
            store.delete(input.accountId, space, input.author, input.rkey, null)
            val inventory = store.changes(space, input.author, 0, 10)
            val operation = begin()
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                repository.transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
            assertEquals(SpacePurgeResult(0, 0), store.purge(later, later) { error("Held blobs cannot be deleted") })
            assertEquals(inventory, store.changes(space, input.author, 0, 10))
            assertEquals(1, store.changes(space, input.author, 1, 10).changes.size)
        }

    @Test
    fun reservationMaintenanceChecksTheAccountHold() =
        withFixture {
            val reservation =
                store.reserveUpload(
                    owner.id.toJavaUUID(),
                    16,
                    app.logdate.server.space
                        .SpaceQuota(1000, 0),
                )
            source.blueskyTransaction {
                prepareStatement("UPDATE space_upload_reservations SET expires_at = ? WHERE id = ?").use {
                    it.setTimestamp(
                        1,
                        kotlin.time.Instant
                            .fromEpochSeconds(0)
                            .timestamp(),
                    )
                    it.setObject(2, reservation.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val operation = begin()
            val held =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    repository.transition(
                        owner.id,
                        operation.id,
                        operation.revision,
                        IdentityAdoptionPhase.FENCED,
                        Clock.System.now(),
                    ),
                ).value
            store.purge(later, later) { error("Held blobs cannot be deleted") }
            assertEquals(1L, reservations())
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                repository.transition(owner.id, held.id, held.revision, IdentityAdoptionPhase.CANCELED, Clock.System.now()),
            )
            store.purge(later, later) { }
            assertEquals(0L, reservations())
        }

    @Test
    fun purgeCannotDeleteBlobsDuringFinalVerification() =
        withFixture {
            val operation = begin()
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                repository.transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
            val deleted = mutableListOf<String>()
            assertEquals(SpacePurgeResult(0, 0), store.purge(later, later) { deleted += it })
            assertEquals(emptyList(), deleted)
            assertEquals(blob, store.getBlob(space, blob.cid))
            assertEquals(3L, store.storedBytes(owner.id.toJavaUUID()))
        }

    @Test
    fun holdCannotOvertakeUnresolvedExternalDeletion() =
        withFixture {
            val operation = begin()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            coroutineScope {
                val purge =
                    async(Dispatchers.IO) {
                        store.purge(later, later) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    val refused =
                        withTimeout(3_000) {
                            repository.transition(
                                owner.id,
                                operation.id,
                                operation.revision,
                                IdentityAdoptionPhase.FENCED,
                                Clock.System.now(),
                            )
                        }
                    assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), refused)
                } finally {
                    release.countDown()
                }
                assertEquals(SpacePurgeResult(1, 0), purge.await())
            }
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                repository.transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
        }

    @Test
    fun objectDeletionKeepsNoDatabaseTransactionOpen() =
        withFixture {
            var openTransactions = -1
            assertEquals(
                SpacePurgeResult(1, 0),
                store.purge(later, later) {
                    source.connection.use { observer ->
                        observer
                            .prepareStatement(
                                "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() " +
                                    "AND backend_type = 'client backend' AND pid <> pg_backend_pid() AND xact_start IS NOT NULL",
                            ).use { query ->
                                query.executeQuery().use { rows ->
                                    rows.next()
                                    openTransactions = rows.getInt(1)
                                }
                            }
                    }
                },
            )
            assertEquals(0, openTransactions)
        }

    @Test
    fun retiredNamespaceCannotBeMutatedOrPurgedWithNewEpoch() =
        withFixture {
            val write = write()
            val original = store.put(write)
            store.put(write.copy(rkey = "deleted"))
            store.delete(write.accountId, space, write.author, "deleted", null)
            val inventory = store.changes(space, write.author, 0, 10)
            val usage = store.storedBytes(write.accountId)
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, identity_epoch, retired_at) " +
                        "VALUES (?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, owner.did)
                    it.setObject(2, write.accountId)
                    it.setString(3, Uuid.random().toString())
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
                prepareStatement(
                    "UPDATE accounts SET did = 'did:web:adopted.test', identity_kind = 'external', identity_epoch = 1 WHERE id = ?",
                ).use {
                    it.setObject(1, write.accountId)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val outcomes =
                listOf(
                    runCatching { store.put(write.copy(identityEpoch = 1)) },
                    runCatching { store.delete(write.accountId, space, write.author, write.rkey, null, identityEpoch = 1) },
                    runCatching { store.putBlob(write.accountId, space, blob.copy(cid = "bafy-new"), identityEpoch = 1) },
                )
            assertEquals(
                listOf(IdentityFenceOutcome.CHANGED, IdentityFenceOutcome.CHANGED, IdentityFenceOutcome.CHANGED),
                outcomes.map { (it.exceptionOrNull() as? IdentityWriteRefusedException)?.outcome },
            )
            val deleted = mutableListOf<String>()
            assertEquals(SpacePurgeResult(0, 0), store.purge(later, later) { deleted += it })
            assertEquals(emptyList(), deleted)
            assertEquals(original, store.get(space, write.author, write.rkey))
            assertEquals(inventory, store.changes(space, write.author, 0, 10))
            assertEquals(blob, store.getBlob(space, blob.cid))
            assertEquals(usage, store.storedBytes(write.accountId))
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val store = PostgreSQLSpaceStore(objectDeleteTimeout = 5.seconds)
        val repository = PostgreSQLIdentityAdoptionRepository(source)
        val space = SpaceRef.personal(requireNotNull(owner.did)).uri
        val blob = SpaceBlob("bafy-held", "application/octet-stream", 3, "synthetic/source")
        val later = Clock.System.now() + 1.hours

        suspend fun reservations(): Long =
            source.blueskyTransaction {
                prepareStatement("SELECT COUNT(*) FROM space_upload_reservations WHERE account_id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }

        suspend fun begin(): IdentityAdoptionOperation {
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) " +
                        "VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            return assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                repository.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            ).value
        }

        fun write(): SpaceRecordWrite =
            SpaceRecordWrite(
                owner.id.toJavaUUID(),
                space,
                requireNotNull(owner.did),
                "record",
                buildJsonObject {
                    put("\$type", "app.logdate.crypto.sealed")
                    put("version", 1)
                    put("keyId", "abcdefghijklm")
                    putJsonObject("nonce") { put("\$bytes", Base64.encode(ByteArray(12))) }
                    putJsonObject("ciphertext") { put("\$bytes", Base64.encode(ByteArray(528))) }
                },
                emptyList(),
                null,
                false,
            )
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "Test account",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            val fixture = Fixture(source, assertNotNull(PostgreSQLAccountRepository().findById(id)))
            fixture.store.putBlob(id.toJavaUUID(), fixture.space, fixture.blob)
            fixture.block()
        }
}
