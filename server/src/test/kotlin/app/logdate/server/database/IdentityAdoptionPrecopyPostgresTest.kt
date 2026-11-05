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
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.space.StoredSpaceRecord
import app.logdate.server.space.recordCid
import app.logdate.shared.model.AccountStorageMode
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import studio.hypertext.atproto.repo.DagCborCodec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityAdoptionPrecopyPostgresTest {
    @Test fun preparingCopiesRemainPrivateWhileOrdinarySourceWritesContinue() =
        fixture {
            val copied = assertNotNull(stage())
            assertEquals(current.revision, copied.generation)
            assertEquals(IdentityAdoptionPhase.PREPARING, current.phase)
            assertEquals(copied, read())
            val edited = edit("changed")
            assertTrue(edited.seq > original.seq)
            assertEquals(edited, spaces.get(sourceSpace, current.binding.sourceDid, original.rkey))
            assertNull(spaces.get(targetSpace, current.binding.targetDid, original.rkey))
            assertEquals(copied, read())
        }

    @Test fun anUnchangedCandidateMovesIntoTheHoldWithoutAnotherStorageCharge() =
        fixture {
            val copied = assertNotNull(stage())
            val charged = spaces.storedBytes(current.binding.accountId.toJavaUUID())
            hold()
            val reused = assertNotNull(reuse(copied))
            assertEquals(copied.copy(generation = current.revision), reused)
            assertEquals(reused, read())
            assertEquals(reused, reuse(copied))
            assertEquals(charged, spaces.storedBytes(current.binding.accountId.toJavaUUID()))
            assertEquals(1L, count())
            assertNull(spaces.get(targetSpace, current.binding.targetDid, original.rkey))
        }

    @Test fun aSourceEditAfterCopyCannotBeReusedUnderTheFinalHold() =
        fixture {
            val copied = assertNotNull(stage())
            edit("changed")
            hold()
            val consumed = auth.countConsumed()
            val failure = assertFailsWith<IdentityAdoptionStagingException> { reuse(copied) }
            assertEquals(IdentityAdoptionStagingFailure.SOURCE_CHANGED, failure.reason)
            assertEquals(consumed, auth.countConsumed())
            assertEquals(1L, count())
            assertNull(read())
        }

    @Test fun aDeletionAfterCopyCannotResurrectTheOldLiveRecord() =
        fixture {
            val copied = assertNotNull(stage())
            spaces.delete(current.binding.accountId.toJavaUUID(), sourceSpace, current.binding.sourceDid, original.rkey, null)
            hold()
            val failure = assertFailsWith<IdentityAdoptionStagingException> { reuse(copied) }
            assertEquals(IdentityAdoptionStagingFailure.SOURCE_CHANGED, failure.reason)
            assertNull(read())
            assertNull(spaces.get(targetSpace, current.binding.targetDid, original.rkey))
        }

    @Test fun preparingCanReplaceOnlyANewerSourceVersionAndKeepsExactAccounting() =
        fixture {
            assertNotNull(stage())
            val newer = edit("newer-source")
            val replacement = request(newer, sealed("new-target", size = 1040))
            val copied = assertNotNull(stage(replacement))
            assertEquals(newer.seq, copied.sourceVersion)
            assertContentEquals(
                recordCid(assertNotNull(replacement.record)).second,
                recordCid(assertNotNull(copied.record)).second,
            )
            assertEquals(1L, count())
            assertEquals(
                recordCid(newer.record).second.size.toLong() + recordCid(assertNotNull(replacement.record)).second.size,
                spaces.storedBytes(current.binding.accountId.toJavaUUID()),
            )
            val failure = assertFailsWith<IdentityAdoptionStagingException> { stage() }
            assertEquals(IdentityAdoptionStagingFailure.SOURCE_CHANGED, failure.reason)
            assertEquals(copied, read())
        }

    @Test fun theSameSourceVersionCannotChooseANewCiphertextEvenBeforeTheHold() =
        fixture {
            val copied = assertNotNull(stage())
            val failure = assertFailsWith<IdentityAdoptionStagingException> { stage(request.copy(record = sealed("changed"))) }
            assertEquals(IdentityAdoptionStagingFailure.CONFLICT, failure.reason)
            assertEquals(copied, read())
        }

    @Test fun insufficientReplacementQuotaRollsBackTheOldCandidateAndTheNonce() =
        fixture {
            val copied = assertNotNull(stage())
            val newer = edit("newer")
            val replacement = request(newer, sealed("larger", size = 2064))
            val sourceBytes = recordCid(newer.record).second.size.toLong()
            val targetBytes = recordCid(assertNotNull(replacement.record)).second.size.toLong()
            val charged = spaces.storedBytes(current.binding.accountId.toJavaUUID())
            val consumed = auth.countConsumed()
            val failure = assertFailsWith<IdentityAdoptionStagingException> { stage(replacement, sourceBytes + targetBytes - 1) }
            assertEquals(IdentityAdoptionStagingFailure.QUOTA, failure.reason)
            assertEquals(consumed, auth.countConsumed())
            assertEquals(charged, spaces.storedBytes(current.binding.accountId.toJavaUUID()))
            assertEquals(copied, read())
        }

    @Test fun reusingRequiresAFreshHoldAndTheOriginallyAuthorizedDevice() =
        fixture {
            val copied = assertNotNull(stage())
            assertNull(reuse(copied))
            hold()
            val owner = current
            current = owner.copy(binding = owner.binding.copy(deviceId = "another-device"))
            assertNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, reuseDigest(copied), auth.clock.now()))
            current = owner
            assertNotNull(reuse(copied))
        }

    private fun fixture(block: suspend Setup.() -> Unit) =
        withIdentityAdoptionAuthorization {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET storage_mode = ? WHERE id = ?").use {
                    it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                    it.setObject(2, operation.binding.accountId.toJavaUUID())
                    check(it.executeUpdate() == 1)
                }
            }
            val spaces = PostgreSQLSpaceStore()
            val original = spaces.put(write(operation, "note", sealed("source")))
            authorize()
            Setup(this, spaces, original).block()
        }

    private class Setup(
        val auth: IdentityAdoptionAuthorizedFixture,
        val spaces: PostgreSQLSpaceStore,
        val original: StoredSpaceRecord,
    ) {
        var current = auth.operation
        val sourceSpace = SpaceRef.personal(current.binding.sourceDid).uri
        val targetSpace = SpaceRef.personal(current.binding.targetDid).uri
        val request = request(original, sealed("target"))
        val store = PostgreSQLIdentityAdoptionStaging(auth.source)

        suspend fun stage(
            value: IdentityAdoptionStagingRequest = request,
            limit: Long = Long.MAX_VALUE,
        ): IdentityAdoptionStagedRecord? {
            val ticket =
                assertNotNull(
                    auth.actions.issue(
                        current,
                        IdentityAdoptionAction.STAGE_RECORDS,
                        prepareIdentityAdoptionRecord(value).bodyDigest,
                        auth.clock.now(),
                    ),
                )
            return store.put(current, ticket, auth.actionSignature(ticket, current), value, limit, auth.clock.now())
        }

        suspend fun read(): IdentityAdoptionStagedRecord? {
            val digest =
                stagingHash(
                    DagCborCodec.encode(
                        buildJsonObject {
                            put("version", 1)
                            put("rkey", original.rkey)
                        },
                    ),
                )
            val ticket = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.READ_STAGED, digest, auth.clock.now()))
            return store.read(current, ticket, auth.actionSignature(ticket, current), original.rkey, auth.clock.now())
        }

        fun reuseDigest(value: IdentityAdoptionStagedRecord): String =
            stagingHash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("sourceGeneration", value.generation)
                        put("rkey", value.rkey)
                        put("targetDigest", value.targetDigest)
                    },
                ),
            )

        suspend fun reuse(value: IdentityAdoptionStagedRecord): IdentityAdoptionStagedRecord? {
            val ticket =
                assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, reuseDigest(value), auth.clock.now()))
            return store.reuse(
                current,
                ticket,
                auth.actionSignature(ticket, current),
                value.generation,
                value.rkey,
                value.targetDigest,
                auth.clock.now(),
            )
        }

        suspend fun hold() {
            current =
                (
                    auth.operations.transition(
                        current.binding.accountId,
                        current.id,
                        current.revision,
                        IdentityAdoptionPhase.FENCED,
                        auth.clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted
                ).value
        }

        suspend fun edit(label: String): StoredSpaceRecord = spaces.put(write(current, original.rkey, sealed(label)))

        suspend fun count(): Long =
            auth.source.blueskyTransaction {
                createStatement().use {
                    it.executeQuery("SELECT count(*) FROM identity_adoption_staged_records").use { rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }
                }
            }
    }

    private companion object {
        fun write(
            operation: IdentityAdoptionOperation,
            rkey: String,
            value: JsonObject,
        ) = SpaceRecordWrite(
            operation.binding.accountId.toJavaUUID(),
            SpaceRef.personal(operation.binding.sourceDid).uri,
            operation.binding.sourceDid,
            rkey,
            value,
            emptyList(),
            null,
            false,
        )

        fun request(
            source: StoredSpaceRecord,
            target: JsonObject,
        ) = IdentityAdoptionStagingRequest(
            source.rkey,
            source.seq,
            stagingHash(recordCid(source.record).second),
            target,
            emptyList(),
        )

        fun sealed(
            id: String,
            size: Int = 528,
        ): JsonObject =
            buildJsonObject {
                put("\$type", "app.logdate.crypto.sealed")
                put("version", 1)
                put("keyId", id)
                putJsonObject("nonce") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(12))) }
                putJsonObject("ciphertext") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(size))) }
            }
    }
}
