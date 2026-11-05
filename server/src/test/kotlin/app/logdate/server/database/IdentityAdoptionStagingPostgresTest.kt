@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionStagingRequest
import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.SpaceQuotaExceededException
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.space.StoredSpaceRecord
import app.logdate.server.space.recordCid
import app.logdate.shared.model.AccountStorageMode
import app.logdate.space.SpaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import studio.hypertext.atproto.repo.DagCborCodec
import java.security.MessageDigest
import java.sql.SQLException
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IdentityAdoptionStagingPostgresTest {
    @Test fun aPrivateCandidateSurvivesRestartWithoutAppearingInTheCanonicalSpace() =
        fixture {
            val staged = assertNotNull(put())
            assertEquals(held.revision, staged.generation)
            assertEquals(request.record, staged.record)
            assertEquals("app.logdate.crypto.sealed", staged.sourceCollection)
            assertEquals(original.rkey, staged.sourceRkey)
            assertNull(spaces.get(target, held.binding.targetDid, request.rkey))
            assertEquals(original, spaces.get(sourceSpace, held.binding.sourceDid, request.rkey))
            assertEquals(sourceBytes + targetBytes, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
            assertEquals(staged, assertNotNull(put()))
            assertEquals(1L, stageCount())
            assertEquals(sourceBytes + targetBytes, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
        }

    @Test fun aNewCiphertextCannotReplaceAnAlreadyStagedCandidateInTheSameHold() =
        fixture {
            val staged = assertNotNull(put())
            val failure =
                assertFailsWith<IdentityAdoptionStagingException> {
                    put(request.copy(record = sealed("different")))
                }
            assertEquals(IdentityAdoptionStagingFailure.CONFLICT, failure.reason)
            assertEquals(staged, put())
            val sqlFailure =
                assertFailsWith<SQLException> {
                    auth.source.blueskyTransaction {
                        val sql = "UPDATE identity_adoption_staged_records SET source_version = 99"
                        createStatement().use { it.execute(sql) }
                    }
                }
            assertEquals("23514", sqlFailure.sqlState)
        }

    @Test fun staleSourceEvidenceAndForgedBodyCannotSpendTheNonceOrStageBytes() =
        fixture {
            val failure =
                assertFailsWith<IdentityAdoptionStagingException> {
                    put(request.copy(sourceVersion = original.seq + 1))
                }
            assertEquals(IdentityAdoptionStagingFailure.SOURCE_CHANGED, failure.reason)
            val digest = bodyDigest(request)
            val ticket = assertNotNull(auth.actions.issue(held, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
            val store = PostgreSQLIdentityAdoptionStaging(auth.source)
            val forged = request.copy(record = sealed("forged"))
            val signature = auth.actionSignature(ticket, held)
            assertNull(store.put(held, ticket, signature, forged, Long.MAX_VALUE, auth.clock.now()))
            assertEquals(0L, stageCount())
            assertEquals(0L, auth.countConsumed())
            assertEquals(sourceBytes, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
        }

    @Test fun thePrivateCopyCannotExceedTheAccountQuota() =
        fixture {
            val failure = assertFailsWith<IdentityAdoptionStagingException> { put(limit = sourceBytes + targetBytes - 1) }
            assertEquals(IdentityAdoptionStagingFailure.QUOTA, failure.reason)
            assertEquals(0L, stageCount())
            assertEquals(0L, auth.countConsumed())
            assertEquals(sourceBytes, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
        }

    @Test fun renewalsReuseTheCandidateButRestartedHoldsUseANewGeneration() =
        fixture {
            val first = assertNotNull(put())
            held =
                (
                    auth.operations.transition(
                        held.binding.accountId,
                        held.id,
                        held.revision,
                        IdentityAdoptionPhase.FENCED,
                        auth.clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted
                ).value
            assertEquals(first, put())
            auth.clock.time += 121.seconds
            assertNull(auth.actions.issue(held, IdentityAdoptionAction.HOLD, bodyDigest(request), auth.clock.now()))
            val preparing = assertNotNull(auth.operations.get(held.binding.accountId, held.id))
            held =
                (
                    auth.operations.transition(
                        preparing.binding.accountId,
                        preparing.id,
                        preparing.revision,
                        IdentityAdoptionPhase.FENCED,
                        auth.clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted
                ).value
            val second = assertNotNull(put(request.copy(record = sealed("second-generation"))))
            assertEquals(held.revision, second.generation)
            assertEquals(first.generation + 3, second.generation)
            assertEquals(2L, stageCount())
        }

    @Test fun canceledCopiesRemainChargedWhenNormalCloudWritesResume() =
        fixture {
            assertNotNull(put())
            auth.operations.transition(
                held.binding.accountId,
                held.id,
                held.revision,
                IdentityAdoptionPhase.CANCELED,
                auth.clock.now(),
            )
            assertFailsWith<SpaceQuotaExceededException> {
                val write =
                    SpaceRecordWrite(
                        held.binding.accountId.toJavaUUID(),
                        sourceSpace,
                        held.binding.sourceDid,
                        "new-record",
                        sealed("more"),
                        emptyList(),
                        null,
                        true,
                        SpaceQuota(sourceBytes + targetBytes, 0),
                    )
                spaces.put(write)
            }
            assertEquals(1L, stageCount())
            assertEquals(sourceBytes + targetBytes, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
        }

    @Test fun simultaneousRetriesStoreAndChargeOneImmutableCandidate() =
        fixture {
            val values = coroutineScope { (1..6).map { async(Dispatchers.IO) { assertNotNull(put()) } }.awaitAll() }
            assertEquals(1, values.toSet().size)
            assertEquals(1L, stageCount())
            assertEquals(sourceBytes + targetBytes, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
        }

    @Test fun accountDeletionRemovesItsPrivateCopiesWithoutRecreatingUsage() =
        fixture {
            assertNotNull(put())
            PostgreSQLAccountRepository().deleteAccount(held.binding.accountId)
            assertEquals(0L, stageCount())
            assertEquals(0L, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
        }

    @Test fun tombstonesKeepTheirCompleteMarkerAndCannotBecomeLiveRecords() =
        fixture(tombstone = true) {
            val staged = assertNotNull(put())
            assertNull(staged.record)
            assertNull(staged.cid)
            assertEquals(request.sourceDigest, staged.targetDigest)
            val change = spaces.changes(sourceSpace, held.binding.sourceDid, 0, 10).changes.single()
            assertTrue(change.deleted)
            assertEquals(request.sourceVersion, change.seq)
            assertEquals(targetBytes, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
            val failure =
                assertFailsWith<IdentityAdoptionStagingException> {
                    put(request.copy(record = sealed("resurrected")))
                }
            assertEquals(IdentityAdoptionStagingFailure.SOURCE_CHANGED, failure.reason)
        }

    @Test
    fun onlyTheBoundDeviceCanReadACandidateWithAFreshSingleUseProof() =
        fixture {
            val expected = assertNotNull(put())
            val ticket = readTicket()
            val store = PostgreSQLIdentityAdoptionStaging(auth.source)
            val signature = auth.actionSignature(ticket, held)
            assertNull(store.read(held, ticket, signature, "another-key", auth.clock.now()))
            val foreign = held.copy(binding = held.binding.copy(deviceId = "other-device"))
            assertNull(store.read(foreign, ticket, auth.actionSignature(ticket, foreign), request.rkey, auth.clock.now()))
            assertEquals(expected, store.read(held, ticket, signature, request.rkey, auth.clock.now()))
            assertNull(store.read(held, ticket, signature, request.rkey, auth.clock.now()))
            assertEquals(2L, auth.countConsumed())
        }

    @Test
    fun aFreshHoldCannotReadAnEarlierGenerationsPrivateCopy() =
        fixture {
            assertNotNull(put())
            auth.clock.time += 121.seconds
            assertNull(auth.actions.issue(held, IdentityAdoptionAction.HOLD, bodyDigest(request), auth.clock.now()))
            val preparing = assertNotNull(auth.operations.get(held.binding.accountId, held.id))
            held =
                (
                    auth.operations.transition(
                        preparing.binding.accountId,
                        preparing.id,
                        preparing.revision,
                        IdentityAdoptionPhase.FENCED,
                        auth.clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted
                ).value
            val ticket = readTicket()
            val store = PostgreSQLIdentityAdoptionStaging(auth.source)
            assertNull(store.read(held, ticket, auth.actionSignature(ticket, held), request.rkey, auth.clock.now()))
            assertEquals(1L, stageCount())
            assertNull(spaces.get(target, held.binding.targetDid, request.rkey))
        }

    @Test
    fun damagedReadbackIsRefusedWithoutSpendingTheDeviceProof() =
        fixture {
            assertNotNull(put())
            val ticket = readTicket()
            val bytes = recordCid(sealed("damaged-readback")).second
            auth.source.blueskyTransaction {
                val disable = "ALTER TABLE identity_adoption_staged_records DISABLE TRIGGER identity_adoption_staged_record_immutable"
                val enable = "ALTER TABLE identity_adoption_staged_records ENABLE TRIGGER identity_adoption_staged_record_immutable"
                createStatement().use { it.execute(disable) }
                prepareStatement("UPDATE identity_adoption_staged_records SET target_record = ?").use {
                    it.setBytes(1, bytes)
                    check(it.executeUpdate() == 1)
                }
                createStatement().use { it.execute(enable) }
            }
            val failure =
                assertFailsWith<IdentityAdoptionStagingException> {
                    PostgreSQLIdentityAdoptionStaging(auth.source).read(
                        held,
                        ticket,
                        auth.actionSignature(ticket, held),
                        request.rkey,
                        auth.clock.now(),
                    )
                }
            assertEquals(IdentityAdoptionStagingFailure.CONFLICT, failure.reason)
            assertEquals(1L, auth.countConsumed())
            assertEquals(1L, stageCount())
        }

    @Test
    fun tombstoneReadbackReturnsTheCompleteMarkerForIndependentDeviceVerification() =
        fixture(tombstone = true) {
            val written = assertNotNull(put())
            val ticket = readTicket()
            val store = PostgreSQLIdentityAdoptionStaging(auth.source)
            val read = assertNotNull(store.read(held, ticket, auth.actionSignature(ticket, held), request.rkey, auth.clock.now()))
            assertEquals(written, read)
            val marker = assertNotNull(read.tombstone)
            assertEquals(setOf("deleted", "rkey", "seq", "deletedAt"), marker.keys)
            assertEquals(request.sourceDigest, hash(DagCborCodec.encode(marker)))
            assertEquals(read.targetDigest, hash(DagCborCodec.encode(marker)))
            assertNull(read.record)
            assertNull(read.cid)
        }

    private fun fixture(
        tombstone: Boolean = false,
        block: suspend Setup.() -> Unit,
    ) = withIdentityAdoptionAuthorization {
        source.blueskyTransaction {
            prepareStatement("UPDATE accounts SET storage_mode = ? WHERE id = ?").use {
                it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                it.setObject(2, operation.binding.accountId.toJavaUUID())
                check(it.executeUpdate() == 1)
            }
        }
        val spaces = PostgreSQLSpaceStore()
        val uri = SpaceRef.personal(operation.binding.sourceDid).uri
        val write =
            SpaceRecordWrite(
                operation.binding.accountId.toJavaUUID(),
                uri,
                operation.binding.sourceDid,
                "source-note",
                sealed("source"),
                emptyList(),
                null,
                true,
            )
        val original = spaces.put(write)
        var marker: ByteArray? = null
        val candidate =
            if (tombstone) {
                val seq =
                    spaces.delete(
                        operation.binding.accountId.toJavaUUID(),
                        uri,
                        operation.binding.sourceDid,
                        original.rkey,
                        null,
                    )
                marker =
                    source.blueskyTransaction {
                        prepareStatement("SELECT deleted_at FROM space_records WHERE account_id = ? AND rkey = ?").use {
                            it.setObject(1, operation.binding.accountId.toJavaUUID())
                            it.setString(2, original.rkey)
                            it.executeQuery().use { rows ->
                                check(rows.next())
                                DagCborCodec.encode(
                                    buildJsonObject {
                                        put("deleted", true)
                                        put("rkey", original.rkey)
                                        put("seq", seq)
                                        put("deletedAt", rows.getTimestamp(1).connectionInstant().toString())
                                    },
                                )
                            }
                        }
                    }
                IdentityAdoptionStagingRequest(original.rkey, seq, hash(assertNotNull(marker)), null, emptyList())
            } else {
                null
            }
        authorize()
        val held =
            (
                operations.transition(
                    operation.binding.accountId,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    clock.now(),
                ) as IdentityAdoptionOutcome.Accepted
            ).value
        Setup(this, spaces, original, held, candidate, marker?.size?.toLong()).block()
    }

    private class Setup(
        val auth: IdentityAdoptionAuthorizedFixture,
        val spaces: PostgreSQLSpaceStore,
        val original: StoredSpaceRecord,
        var held: IdentityAdoptionOperation,
        candidate: IdentityAdoptionStagingRequest? = null,
        tombstoneBytes: Long? = null,
    ) {
        val sourceSpace = SpaceRef.personal(held.binding.sourceDid).uri
        val target = SpaceRef.personal(held.binding.targetDid).uri
        val request =
            candidate ?: IdentityAdoptionStagingRequest(
                original.rkey,
                original.seq,
                hash(recordCid(original.record).second),
                sealed("target"),
                emptyList(),
            )
        val sourceBytes = if (candidate == null) recordCid(original.record).second.size.toLong() else 0L
        val targetBytes = tombstoneBytes ?: recordCid(assertNotNull(request.record)).second.size.toLong()

        suspend fun put(
            value: IdentityAdoptionStagingRequest = request,
            limit: Long = Long.MAX_VALUE,
        ): IdentityAdoptionStagedRecord? {
            val digest = bodyDigest(value)
            val ticket = assertNotNull(auth.actions.issue(held, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
            val store = PostgreSQLIdentityAdoptionStaging(auth.source)
            return store.put(held, ticket, auth.actionSignature(ticket, held), value, limit, auth.clock.now())
        }

        suspend fun readTicket() =
            assertNotNull(
                auth.actions.issue(held, IdentityAdoptionAction.READ_STAGED, readDigest(request.rkey), auth.clock.now()),
            )

        suspend fun stageCount(): Long =
            auth.source.blueskyTransaction {
                val sql = "SELECT count(*) FROM identity_adoption_staged_records"
                createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }
                }
            }
    }

    companion object {
        private fun sealed(id: String): JsonObject =
            buildJsonObject {
                put("\$type", "app.logdate.crypto.sealed")
                put("version", 1)
                put("keyId", id)
                putJsonObject("nonce") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(12))) }
                putJsonObject("ciphertext") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(528))) }
            }

        private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun readDigest(key: String): String =
            hash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("rkey", key)
                    },
                ),
            )

        private fun bodyDigest(request: IdentityAdoptionStagingRequest): String =
            hash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("rkey", request.rkey)
                        put("sourceVersion", request.sourceVersion)
                        put("sourceDigest", request.sourceDigest)
                        put("record", request.record ?: JsonNull)
                        put("blobs", JsonArray(request.blobs.map(::JsonPrimitive)))
                    },
                ),
            )
    }
}
