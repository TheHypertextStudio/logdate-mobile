@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionGenericCopy
import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionTargetReference
import app.logdate.server.space.recordCid
import kotlinx.serialization.json.JsonArray
import studio.hypertext.atproto.repo.DagCborCodec
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class IdentityAdoptionGenericCoveragePostgresTest {
    @Test fun aLegacyEntryAndItsTranscriptBothParticipateInCompleteCoverage() =
        withGenericAdoption {
            hold()
            auth.mapStorageAuthority(current)
            val entry = request(rkey = "entry", target = genericSealed("entry"))
            val transcript = request(rkey = "transcript", target = genericSealed("transcript"))
            val first = assertNotNull(put(entry))
            val second = assertNotNull(put(transcript))
            val mapping =
                IdentityAdoptionMappingRequest(
                    entry.source,
                    entry.sourceDigest,
                    IdentityAdoptionDisposition.CONVERTED,
                    listOf(
                        IdentityAdoptionTargetReference(entry.rkey, first.record.targetDigest),
                        IdentityAdoptionTargetReference(transcript.rkey, second.record.targetDigest),
                    ),
                    emptyList(),
                )
            val digest = prepareIdentityAdoptionMapping(mapping).bodyDigest
            val challenge = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
            val signature = auth.actionSignature(challenge, current)
            assertNotNull(
                PostgreSQLIdentityAdoptionMappings(
                    auth.source,
                ).put(current, challenge, signature, mapping, Long.MAX_VALUE, auth.clock.now()),
            )
            val coverage = assertNotNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now()))
            assertEquals(2L, coverage.mappingCount)
            assertEquals(2L, coverage.targetRecords)
            assertEquals(2L, coverage.originCount)
            auth.source.blueskyTransaction {
                IdentityAdoptionGenericRows(this, current, current.revision).deleteRecord(transcript.rkey)
            }
            assertFailsWith<IdentityAdoptionStagingException> {
                PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now())
            }
        }

    @Test fun removingRequiredOriginEvidenceInvalidatesAnOtherwiseMatchingCopy() =
        withGenericAdoption {
            hold()
            auth.mapStorageAuthority(current)
            val request = request()
            val copy = assertNotNull(put(request))
            mapGeneric(request, copy)
            assertNotNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now()))
            auth.source.blueskyTransaction {
                createStatement().use { it.executeUpdate("DELETE FROM identity_adoption_staged_origins") }
            }
            assertFailsWith<IdentityAdoptionStagingException> {
                PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now())
            }
        }

    @Test fun originSequenceMustMatchTheActualDestinationRow() =
        withGenericAdoption {
            hold()
            auth.mapStorageAuthority(current)
            val request = request()
            val copy = assertNotNull(put(request))
            mapGeneric(request, copy)
            auth.source.blueskyTransaction {
                val rows = IdentityAdoptionGenericRows(this, current, current.revision)
                rows.deleteOrigin(copy.origin)
                rows.insertOrigin(prepareIdentityAdoptionOrigin(copy.origin.copy(targetSequence = copy.origin.targetSequence + 1)))
            }
            assertFailsWith<IdentityAdoptionStagingException> {
                PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now())
            }
        }

    @Test fun coverageBindsAllocatedSequencesEvenWhenSourceAndCiphertextAreUnchanged() =
        withGenericAdoption {
            hold()
            auth.mapStorageAuthority(current)
            val request = request()
            val copy = assertNotNull(put(request))
            mapGeneric(request, copy)
            val reader = PostgreSQLIdentityAdoptionCoverage(auth.source)
            val before = assertNotNull(reader.read(current, auth.clock.now()))
            auth.source.blueskyTransaction {
                val rows = IdentityAdoptionGenericRows(this, current, current.revision)
                rows.deleteRecord(copy.record.rkey)
                val sequence = copy.origin.targetSequence + 1
                val record = copy.record.copy(targetSequence = sequence)
                val bytes = recordCid(assertNotNull(record.record)).second
                rows.insertRecord(record, bytes, DagCborCodec.encode(JsonArray(emptyList())), auth.clock.now())
                rows.insertOrigin(prepareIdentityAdoptionOrigin(copy.origin.copy(targetSequence = sequence)))
            }
            val after = assertNotNull(reader.read(current, auth.clock.now()))
            assertEquals(before.source.digest, after.source.digest)
            assertEquals(before.mappingsDigest, after.mappingsDigest)
            assertNotEquals(before.digest, after.digest)
        }

    @Test fun legacyDataCannotBorrowAnUnrelatedPersonalCandidateWithoutItsOwnOrigin() =
        withAdoptionCoverage {
            mapAll()
            auth.source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO sync_content(id,user_id,type,content,created_at,last_updated,server_version,device_id) " +
                        "VALUES ('extra-legacy',?,'TEXT','different content',1,2,3,'device')",
                ).use {
                    it.setObject(1, held.binding.accountId.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val source = PostgreSQLIdentityAdoptionSourceInventory(auth.source)
            val kind = IdentityAdoptionSourceKind.SYNC_CONTENT
            val item = assertNotNull(source.page(held, kind, now = auth.clock.now())).items.single()
            val request =
                IdentityAdoptionMappingRequest(
                    IdentityAdoptionSourceReference(kind, item.key),
                    HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item)),
                    IdentityAdoptionDisposition.CONVERTED,
                    listOf(IdentityAdoptionTargetReference("note", targetDigests.getValue("note"))),
                    emptyList(),
                )
            val digest = prepareIdentityAdoptionMapping(request).bodyDigest
            val ticket = assertNotNull(auth.actions.issue(held, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
            val signature = auth.actionSignature(ticket, held)
            assertNotNull(
                PostgreSQLIdentityAdoptionMappings(auth.source).put(held, ticket, signature, request, Long.MAX_VALUE, auth.clock.now()),
            )
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }

    @Test fun personalCopiesWithGenericOriginsVerifyAgainstTheirActualSourceEvidence() =
        withAdoptionCoverage {
            auth.source.blueskyTransaction {
                createStatement().use { it.executeUpdate("DELETE FROM identity_adoption_staged_records") }
            }
            val kind = IdentityAdoptionSourceKind.SPACE_RECORD
            val reader = PostgreSQLIdentityAdoptionSourceInventory(auth.source)
            val item = assertNotNull(reader.page(held, kind, now = auth.clock.now())).items.single()
            val request =
                IdentityAdoptionGenericRequest(
                    IdentityAdoptionSourceReference(kind, item.key),
                    HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item)),
                    "note",
                    genericSealed("generic-personal"),
                    emptyList(),
                )
            val digest = genericRequestDigest(request)
            val ticket = assertNotNull(auth.actions.issue(held, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
            val signature = auth.actionSignature(ticket, held)
            val store = PostgreSQLIdentityAdoptionGenericStaging(auth.source)
            val copy = assertNotNull(store.put(held, ticket, signature, request, Long.MAX_VALUE, auth.clock.now()))
            IdentityAdoptionCoverageFixture(auth, held, mapOf("note" to copy.record.targetDigest), null).mapAll()
            assertNotNull(coverage())
        }

    private suspend fun IdentityAdoptionGenericFixture.mapGeneric(
        request: IdentityAdoptionGenericRequest,
        copy: IdentityAdoptionGenericCopy,
    ) {
        val value =
            IdentityAdoptionMappingRequest(
                request.source,
                request.sourceDigest,
                IdentityAdoptionDisposition.CONVERTED,
                listOf(IdentityAdoptionTargetReference(request.rkey, copy.record.targetDigest)),
                request.blobs,
            )
        val digest = prepareIdentityAdoptionMapping(value).bodyDigest
        val ticket = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
        val signature = auth.actionSignature(ticket, current)
        val store = PostgreSQLIdentityAdoptionMappings(auth.source)
        assertNotNull(store.put(current, ticket, signature, value, Long.MAX_VALUE, auth.clock.now()))
    }
}
