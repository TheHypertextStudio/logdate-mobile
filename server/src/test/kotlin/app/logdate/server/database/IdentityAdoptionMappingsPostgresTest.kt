@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceMapping
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionStagingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionTargetReference
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.space.recordCid
import app.logdate.shared.model.AccountStorageMode
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import studio.hypertext.atproto.repo.DagCborCodec
import java.sql.SQLException
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IdentityAdoptionMappingsPostgresTest {
    @Test fun aMappingBindsActualSourceEvidenceAndPreparedTargetAndSurvivesReadback() =
        fixture {
            val before = spaces.storedBytes(current.binding.accountId.toJavaUUID())
            val value = assertNotNull(put())
            assertEquals(current.revision, value.generation)
            assertEquals(request, value.request)
            assertEquals(value, put())
            assertEquals(value, read())
            assertEquals(before + value.storageBytes, spaces.storedBytes(current.binding.accountId.toJavaUUID()))
            assertEquals(1L, count())
        }

    @Test fun structuralStateHasAnExplicitTargetlessDispositionAndNeverInventsAnEmptyJournal() =
        fixture {
            val state =
                mapping(
                    IdentityAdoptionSourceKind.SPACE_STATE,
                    listOf(space, current.binding.sourceDid),
                    IdentityAdoptionDisposition.STRUCTURAL,
                    emptyList(),
                )
            assertNotNull(put(state))
            assertEquals(1L, count())
            assertEquals(
                1,
                assertNotNull(
                    PostgreSQLIdentityAdoptionSourceInventory(auth.source)
                        .page(current, IdentityAdoptionSourceKind.SPACE_RECORD, now = auth.clock.now()),
                ).items.size,
            )
        }

    @Test fun activeRecordsCannotUseStructuralOrRetainedHistoryOrAnEmptyConversion() =
        fixture {
            for (disposition in listOf(
                IdentityAdoptionDisposition.STRUCTURAL,
                IdentityAdoptionDisposition.RETAINED_HISTORY,
                IdentityAdoptionDisposition.CONVERTED,
            )) {
                val consumed = auth.countConsumed()
                val failure =
                    assertFailsWith<IdentityAdoptionStagingException> {
                        put(request.copy(disposition = disposition, targets = emptyList()))
                    }
                assertEquals(IdentityAdoptionStagingFailure.INVALID_REQUEST, failure.reason)
                assertEquals(consumed, auth.countConsumed())
            }
            assertEquals(0L, count())
        }

    @Test fun aChangedOrMissingSourceCannotBeClaimedByAPreviousDigest() =
        fixture {
            val failure = assertFailsWith<IdentityAdoptionStagingException> { put(request.copy(sourceDigest = "0".repeat(64))) }
            assertEquals(IdentityAdoptionStagingFailure.SOURCE_CHANGED, failure.reason)
            val missing = request.copy(source = request.source.copy(key = listOf(space, current.binding.sourceDid, "missing")))
            assertFailsWith<IdentityAdoptionStagingException> { put(missing) }
            assertEquals(0L, auth.countConsumed() - baselineConsumed)
            assertEquals(0L, count())
        }

    @Test fun aMissingOrChangedPreparedDestinationCannotSatisfyAMapping() =
        fixture {
            for (target in listOf(
                IdentityAdoptionTargetReference("missing", request.targets.single().digest),
                request.targets.single().copy(digest = "0".repeat(64)),
            )) {
                val failure = assertFailsWith<IdentityAdoptionStagingException> { put(request.copy(targets = listOf(target))) }
                assertEquals(IdentityAdoptionStagingFailure.CONFLICT, failure.reason)
            }
            assertEquals(0L, count())
        }

    @Test fun mappingQuotaFailureDoesNotSpendTheNonceOrStoreAnything() =
        fixture {
            val used = spaces.storedBytes(current.binding.accountId.toJavaUUID())
            val bytes = prepareIdentityAdoptionMapping(request).storageBytes
            val failure = assertFailsWith<IdentityAdoptionStagingException> { put(limit = used + bytes - 1) }
            assertEquals(IdentityAdoptionStagingFailure.QUOTA, failure.reason)
            assertEquals(baselineConsumed, auth.countConsumed())
            assertEquals(used, spaces.storedBytes(current.binding.accountId.toJavaUUID()))
            assertEquals(0L, count())
        }

    @Test fun onlyTheOwningDeviceAndCurrentRevisionCanReadOrWriteTheMapping() =
        fixture {
            val value = assertNotNull(put())
            val owner = current
            current = owner.copy(binding = owner.binding.copy(deviceId = "wrong-device"))
            assertNull(
                auth.actions.issue(
                    current,
                    IdentityAdoptionAction.STAGE_RECORDS,
                    prepareIdentityAdoptionMapping(request).bodyDigest,
                    auth.clock.now(),
                ),
            )
            current = owner.copy(revision = owner.revision + 1)
            assertNull(auth.actions.issue(current, IdentityAdoptionAction.READ_STAGED, readDigest(request.source), auth.clock.now()))
            current = owner
            assertEquals(value, read())
        }

    @Test fun holdReconciliationMovesAnUnchangedMappingWithoutDoubleCharging() =
        fixture {
            val value = assertNotNull(put())
            val used = spaces.storedBytes(current.binding.accountId.toJavaUUID())
            val owner = current
            current =
                (
                    auth.operations.transition(
                        owner.binding.accountId,
                        owner.id,
                        owner.revision,
                        IdentityAdoptionPhase.FENCED,
                        auth.clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted
                ).value
            val ticket = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, reuseDigest(), auth.clock.now()))
            assertNotNull(
                PostgreSQLIdentityAdoptionStaging(auth.source).reuse(
                    current,
                    ticket,
                    auth.actionSignature(ticket, current),
                    owner.revision,
                    "note",
                    request.targets.single().digest,
                    auth.clock.now(),
                ),
            )
            val moved = assertNotNull(put())
            assertEquals(value.copy(generation = current.revision), moved)
            assertEquals(used, spaces.storedBytes(current.binding.accountId.toJavaUUID()))
            assertEquals(1L, count())
        }

    @Test fun sameSourceEvidenceCannotBeReboundToANewDispositionInTheSameGeneration() =
        fixture {
            assertNotNull(put())
            val failure = assertFailsWith<IdentityAdoptionStagingException> { put(request.copy(media = listOf("not-verified-media"))) }
            assertEquals(IdentityAdoptionStagingFailure.CONFLICT, failure.reason)
            assertEquals(1L, count())
        }

    @Test fun wrongSignedBodyCannotConsumeTheTicketOrCreateAMapping() =
        fixture {
            val ticket =
                assertNotNull(
                    auth.actions.issue(
                        current,
                        IdentityAdoptionAction.STAGE_RECORDS,
                        prepareIdentityAdoptionMapping(request).bodyDigest,
                        auth.clock.now(),
                    ),
                )
            val changed = request.copy(sourceDigest = "0".repeat(64))
            val result = store.put(current, ticket, auth.actionSignature(ticket, current), changed, Long.MAX_VALUE, auth.clock.now())
            assertNull(result)
            assertEquals(baselineConsumed, auth.countConsumed())
            assertEquals(0L, count())
        }

    @Test fun sourceEditsReplaceTheMappingOnlyAfterTheNewTargetIsStaged() =
        fixture {
            val original = assertNotNull(put())
            val old = request
            val newer = editAndStage("updated")
            val before = spaces.storedBytes(current.binding.accountId.toJavaUUID())
            val replaced = assertNotNull(put(newer))
            assertEquals(newer, replaced.request)
            assertEquals(1L, count())
            val expected = before - original.storageBytes + replaced.storageBytes
            assertEquals(expected, spaces.storedBytes(current.binding.accountId.toJavaUUID()))
            val failure = assertFailsWith<IdentityAdoptionStagingException> { put(old) }
            assertEquals(IdentityAdoptionStagingFailure.SOURCE_CHANGED, failure.reason)
            request = newer
            assertEquals(replaced, read())
        }

    @Test fun failedReplacementPreservesTheOriginalMappingAndNonce() =
        fixture {
            val original = assertNotNull(put())
            val newer = editAndStage("updated-again")
            val used = spaces.storedBytes(current.binding.accountId.toJavaUUID())
            val consumed = auth.countConsumed()
            val growth = prepareIdentityAdoptionMapping(newer).storageBytes - original.storageBytes
            val failure = assertFailsWith<IdentityAdoptionStagingException> { put(newer, used + growth - 1) }
            assertEquals(IdentityAdoptionStagingFailure.QUOTA, failure.reason)
            assertEquals(consumed, auth.countConsumed())
            assertEquals(used, spaces.storedBytes(current.binding.accountId.toJavaUUID()))
            assertEquals(original, read())
            assertEquals(1L, count())
        }

    @Test fun storedMappingsRejectDirectUpdatesAndOperationDeletionReleasesTheirCharge() =
        fixture {
            val original = assertNotNull(put())
            val before = spaces.storedBytes(current.binding.accountId.toJavaUUID())
            assertFailsWith<SQLException> {
                auth.source.blueskyTransaction {
                    createStatement().use {
                        it.executeUpdate("UPDATE identity_adoption_source_mappings SET disposition = 'STRUCTURAL'")
                    }
                }
            }
            assertEquals(original, read())
            auth.source.blueskyTransaction {
                prepareStatement("DELETE FROM identity_adoption_operations WHERE id = ? AND account_id = ?").use {
                    it.setString(1, current.id)
                    it.setObject(2, current.binding.accountId.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(0L, count())
            val targetBytes = recordCid(sealed("target")).second.size
            assertEquals(before - original.storageBytes - targetBytes, spaces.storedBytes(current.binding.accountId.toJavaUUID()))
        }

    private fun fixture(block: suspend Setup.() -> Unit) =
        withIdentityAdoptionAuthorization {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET storage_mode = ? WHERE id = ?").use {
                    it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                    it.setObject(2, operation.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            val spaces = PostgreSQLSpaceStore()
            val space = SpaceRef.personal(operation.binding.sourceDid).uri
            val original =
                spaces.put(
                    SpaceRecordWrite(
                        operation.binding.accountId.toJavaUUID(),
                        space,
                        operation.binding.sourceDid,
                        "note",
                        sealed("source"),
                        emptyList(),
                        null,
                        false,
                    ),
                )
            authorize()
            val sourceDigest = stagingHash(recordCid(original.record).second)
            val stage = IdentityAdoptionStagingRequest("note", original.seq, sourceDigest, sealed("target"), emptyList())
            val digest = prepareIdentityAdoptionRecord(stage).bodyDigest
            val ticket = assertNotNull(actions.issue(operation, IdentityAdoptionAction.STAGE_RECORDS, digest, clock.now()))
            val records = PostgreSQLIdentityAdoptionStaging(source)
            val prepared = assertNotNull(records.put(operation, ticket, actionSignature(ticket), stage, Long.MAX_VALUE, clock.now()))
            val setup = Setup(this, spaces, space, countConsumed())
            setup.request =
                setup.mapping(
                    IdentityAdoptionSourceKind.SPACE_RECORD,
                    listOf(space, operation.binding.sourceDid, "note"),
                    IdentityAdoptionDisposition.CONVERTED,
                    listOf(IdentityAdoptionTargetReference("note", prepared.targetDigest)),
                )
            setup.block()
        }

    private class Setup(
        val auth: IdentityAdoptionAuthorizedFixture,
        val spaces: PostgreSQLSpaceStore,
        val space: String,
        val baselineConsumed: Long,
    ) {
        var current: IdentityAdoptionOperation = auth.operation
        lateinit var request: IdentityAdoptionMappingRequest
        val store = PostgreSQLIdentityAdoptionMappings(auth.source)

        suspend fun editAndStage(label: String): IdentityAdoptionMappingRequest {
            val changed =
                spaces.put(
                    SpaceRecordWrite(
                        current.binding.accountId.toJavaUUID(),
                        space,
                        current.binding.sourceDid,
                        "note",
                        sealed(label),
                        emptyList(),
                        null,
                        false,
                    ),
                )
            val stage =
                IdentityAdoptionStagingRequest(
                    "note",
                    changed.seq,
                    stagingHash(recordCid(changed.record).second),
                    sealed("target-$label"),
                    emptyList(),
                )
            val digest = prepareIdentityAdoptionRecord(stage).bodyDigest
            val ticket = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
            val signature = auth.actionSignature(ticket, current)
            val records = PostgreSQLIdentityAdoptionStaging(auth.source)
            val staged = assertNotNull(records.put(current, ticket, signature, stage, Long.MAX_VALUE, auth.clock.now()))
            return mapping(
                IdentityAdoptionSourceKind.SPACE_RECORD,
                request.source.key,
                IdentityAdoptionDisposition.CONVERTED,
                listOf(IdentityAdoptionTargetReference("note", staged.targetDigest)),
            )
        }

        suspend fun mapping(
            kind: IdentityAdoptionSourceKind,
            key: List<String>,
            disposition: IdentityAdoptionDisposition,
            targets: List<IdentityAdoptionTargetReference>,
        ): IdentityAdoptionMappingRequest {
            val page = assertNotNull(PostgreSQLIdentityAdoptionSourceInventory(auth.source).page(current, kind, now = auth.clock.now()))
            val item = page.items.single { it.key == key }
            return IdentityAdoptionMappingRequest(
                IdentityAdoptionSourceReference(kind, key),
                HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item)),
                disposition,
                targets,
                emptyList(),
            )
        }

        suspend fun put(
            value: IdentityAdoptionMappingRequest = request,
            limit: Long = Long.MAX_VALUE,
        ): IdentityAdoptionSourceMapping? {
            val ticket =
                assertNotNull(
                    auth.actions.issue(
                        current,
                        IdentityAdoptionAction.STAGE_RECORDS,
                        prepareIdentityAdoptionMapping(value).bodyDigest,
                        auth.clock.now(),
                    ),
                )
            return store.put(current, ticket, auth.actionSignature(ticket, current), value, limit, auth.clock.now())
        }

        suspend fun read(): IdentityAdoptionSourceMapping? {
            val digest = readDigest(request.source)
            val ticket = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.READ_STAGED, digest, auth.clock.now()))
            return store.read(current, ticket, auth.actionSignature(ticket, current), request.source, auth.clock.now())
        }

        suspend fun count(): Long =
            auth.source.blueskyTransaction {
                createStatement().use {
                    it.executeQuery("SELECT count(*) FROM identity_adoption_source_mappings").use { rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }
                }
            }

        fun reuseDigest() =
            stagingHash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("sourceGeneration", current.revision - 1)
                        put("rkey", "note")
                        put("targetDigest", request.targets.single().digest)
                    },
                ),
            )
    }

    private companion object {
        fun sealed(id: String) =
            buildJsonObject {
                put("\$type", "app.logdate.crypto.sealed")
                put("version", 1)
                put("keyId", id)
                putJsonObject("nonce") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(12))) }
                putJsonObject("ciphertext") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(528))) }
            }

        fun readDigest(source: IdentityAdoptionSourceReference) =
            stagingHash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("purpose", "app.logdate.identity-adoption.source-mapping-read")
                        put("kind", source.kind.name)
                        put("key", JsonArray(source.key.map { JsonPrimitive(it) }))
                    },
                ),
            )
    }
}
