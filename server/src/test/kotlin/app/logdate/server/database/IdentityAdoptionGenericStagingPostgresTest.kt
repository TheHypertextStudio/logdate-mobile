@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.space.recordCid
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonPrimitive
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityAdoptionGenericStagingPostgresTest {
    @Test fun legacyCopiesRetainExactOriginAndRealVersionWithoutCreatingACanonicalTarget() =
        withGenericAdoption {
            val request = request()
            val value = assertNotNull(put(request))
            assertEquals(request.source, value.origin.source)
            assertEquals(request.sourceDigest, value.origin.sourceDigest)
            assertEquals(7L, value.origin.sourceVersion)
            assertTrue(value.origin.targetSequence > 0)
            assertEquals(value, read(request))
            assertEquals(value, put(request))
            val bytes = recordCid(assertNotNull(request.record)).second.size.toLong()
            assertEquals(bytes + value.origin.storageBytes, used())
            val target = SpaceRef.personal(current.binding.targetDid).uri
            assertNull(PostgreSQLSpaceStore().get(target, current.binding.targetDid, request.rkey))
        }

    @Test fun legacyTombstonesPreserveKnownDeletionTimeAndUseADistinctTargetSequence() =
        withGenericAdoption(deleted = true) {
            val request = request(target = null)
            val value = assertNotNull(put(request))
            assertNull(value.record.record)
            assertEquals(7L, value.origin.sourceVersion)
            assertNotEquals(value.origin.sourceVersion, value.origin.targetSequence)
            assertEquals("1970-01-01T00:00:01.700Z", value.origin.sourceDeletedAt)
            val marker = assertNotNull(value.record.tombstone)
            assertEquals(JsonPrimitive(value.origin.targetSequence), marker["seq"])
            assertEquals(JsonPrimitive(value.origin.sourceDeletedAt), marker["deletedAt"])
            assertEquals(value, read(request))
        }

    @Test fun changedSourceEvidenceAndWrongDeletionStateRollBackTheChallenge() =
        withGenericAdoption {
            val before = auth.countConsumed()
            val source = request()
            val changed = assertFailsWith<IdentityAdoptionStagingException> { put(source.copy(sourceDigest = "0".repeat(64))) }
            assertEquals(IdentityAdoptionStagingFailure.SOURCE_CHANGED, changed.reason)
            assertFailsWith<IdentityAdoptionStagingException> { put(source.copy(record = null)) }
            assertEquals(before, auth.countConsumed())
        }

    @Test fun eachPreparedComponentKeepsImmutableCiphertext() =
        withGenericAdoption {
            val request = request()
            val original = assertNotNull(put(request))
            assertFailsWith<IdentityAdoptionStagingException> { put(request.copy(record = genericSealed("changed"))) }
            assertEquals(original, read(request))
        }

    @Test fun oneLegacySourceCanProduceSeparateEntryAndTranscriptComponents() =
        withGenericAdoption {
            val entry = request(rkey = "entry", target = genericSealed("entry"))
            val transcript = request(rkey = "transcript", target = genericSealed("transcript"))
            val first = assertNotNull(put(entry))
            val second = assertNotNull(put(transcript))
            assertEquals(first.origin.source, second.origin.source)
            assertEquals(first.origin.sourceDigest, second.origin.sourceDigest)
            assertNotEquals(first.origin.sourceRecord, second.origin.sourceRecord)
            assertNotEquals(first.origin.targetSequence, second.origin.targetSequence)
            assertEquals(first, read(entry))
            assertEquals(second, read(transcript))
            hold()
            assertEquals(first.record.targetDigest, assertNotNull(put(entry)).record.targetDigest)
            assertEquals(second.record.targetDigest, assertNotNull(put(transcript)).record.targetDigest)
        }

    @Test fun quotaIncludesTheDurableOriginAndFailureLeavesNoCandidateOrConsumedProof() =
        withGenericAdoption {
            val request = request()
            val bytes = recordCid(assertNotNull(request.record)).second.size.toLong()
            val failure = assertFailsWith<IdentityAdoptionStagingException> { put(request, bytes) }
            assertEquals(IdentityAdoptionStagingFailure.QUOTA, failure.reason)
            assertEquals(0L, used())
            assertEquals(0L, auth.countConsumed())
            assertNull(read(request))
        }

    @Test fun sourceEditsCanReplaceAnUnfencedCandidateAndQuotaRollbackKeepsTheOldCopy() =
        withGenericAdoption {
            val request = request()
            val original = assertNotNull(put(request))
            edit()
            val replacement = request(target = genericSealed("new-target"))
            val charged = used()
            val consumed = auth.countConsumed()
            assertFailsWith<IdentityAdoptionStagingException> { put(replacement, 0) }
            assertEquals(charged, used())
            assertEquals(consumed, auth.countConsumed())
            assertEquals(original, read(request))
            val copied = assertNotNull(put(replacement))
            assertEquals(8L, copied.origin.sourceVersion)
            assertEquals(copied, read(replacement))
        }

    @Test fun unchangedPrecopiesMoveUnderTheHoldWithoutDoubleCharging() =
        withGenericAdoption {
            val request = request()
            val original = assertNotNull(put(request))
            val charged = used()
            hold()
            val moved = assertNotNull(put(request))
            assertEquals(current.revision, moved.record.generation)
            assertEquals(original.record.targetDigest, moved.record.targetDigest)
            assertEquals(charged, used())
            assertEquals(moved, read(request))
        }

    @Test fun aForgedSignedBodyCannotStageAnythingOrConsumeTheTicket() =
        withGenericAdoption {
            val request = request()
            val digest = genericRequestDigest(request)
            val ticket = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
            val signature = auth.actionSignature(ticket, current)
            assertNull(
                store.put(current, ticket, signature, request.copy(record = genericSealed("forged")), Long.MAX_VALUE, auth.clock.now()),
            )
            assertEquals(0L, auth.countConsumed())
        }

    @Test fun differentLegacyFamiliesWithTheSameVersionReceiveDistinctTargetSequences() =
        withGenericAdoption {
            auth.source.blueskyTransaction {
                val account = current.binding.accountId.toJavaUUID()
                val statements =
                    listOf(
                        "INSERT INTO sync_journals(id,user_id,title,description,created_at,last_updated,server_version,device_id) " +
                            "VALUES ('journal',?,'opaque title','opaque description',1000,1700,7,'original-device')",
                        "INSERT INTO sync_associations(journal_id,content_id,user_id,created_at,server_version,device_id) " +
                            "VALUES ('journal','legacy-note',?,1000,7,'original-device')",
                        "INSERT INTO logdate_collection_records(user_id,collection,record_key,server_version,deleted) " +
                            "VALUES (?,'futureCollection','future',7,FALSE)",
                        "INSERT INTO location_history(user_id,id,record_type,payload,payload_schema_version," +
                            "device_id,device_version,server_version,deleted) " +
                            "VALUES (?,'location','SAMPLE','opaque location',1,'original-device',3,7,FALSE)",
                    )
                for (sql in statements) {
                    prepareStatement(sql).use {
                        it.setObject(1, account)
                        assertEquals(1, it.executeUpdate())
                    }
                }
            }
            val kinds =
                listOf(
                    IdentityAdoptionSourceKind.SYNC_CONTENT,
                    IdentityAdoptionSourceKind.SYNC_JOURNAL,
                    IdentityAdoptionSourceKind.SYNC_ASSOCIATION,
                    IdentityAdoptionSourceKind.COLLECTION_RECORD,
                    IdentityAdoptionSourceKind.LOCATION,
                )
            val sequences =
                kinds.map { kind ->
                    val request = request(rkey = "copy-" + kind.name.lowercase(), kind = kind)
                    val value = assertNotNull(put(request))
                    assertEquals(7L, value.origin.sourceVersion)
                    assertEquals(request.source, value.origin.source)
                    assertEquals(value, read(request))
                    value.origin.targetSequence
                }
            assertEquals(5, sequences.toSet().size)
        }

    @Test fun equivalentOverlappingSourcesRetainSeparateOriginsOnOneImmutableCiphertext() =
        withGenericAdoption {
            val firstRequest = request()
            val first = assertNotNull(put(firstRequest))
            auth.source.blueskyTransaction {
                createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO sync_content(id,user_id,type,content,media_uri,transcript,duration_ms," +
                            "created_at,last_updated,server_version,device_id,deleted,deleted_at) " +
                            "SELECT 'second-note',user_id,type,content,media_uri,transcript,duration_ms," +
                            "created_at,last_updated,server_version,device_id,deleted,deleted_at FROM sync_content WHERE id = 'legacy-note'",
                    )
                }
            }
            val secondRequest = request(sourceKey = listOf("second-note"))
            val second = assertNotNull(put(secondRequest))
            assertEquals(first.record, second.record)
            assertNotEquals(first.origin.source, second.origin.source)
            assertEquals(first, read(firstRequest))
            assertEquals(second, read(secondRequest))
            val bytes = recordCid(assertNotNull(firstRequest.record)).second.size
            assertEquals(bytes + first.origin.storageBytes + second.origin.storageBytes, used())
        }

    @Test fun immutableOriginRowsCannotBeRewrittenAndCandidateDeletionReleasesTheirCharge() =
        withGenericAdoption {
            val request = request()
            val value = assertNotNull(put(request))
            val failure =
                assertFailsWith<SQLException> {
                    auth.source.blueskyTransaction {
                        createStatement().use { it.executeUpdate("UPDATE identity_adoption_staged_origins SET body_bytes = body_bytes") }
                    }
                }
            assertEquals("23514", failure.sqlState)
            assertEquals(value, read(request))
            auth.source.blueskyTransaction {
                createStatement().use { it.executeUpdate("DELETE FROM identity_adoption_staged_records") }
            }
            assertEquals(0L, used())
            assertNull(read(request))
        }
}
