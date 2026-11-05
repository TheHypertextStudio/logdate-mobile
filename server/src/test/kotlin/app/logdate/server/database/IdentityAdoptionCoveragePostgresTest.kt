@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityAdoptionCoveragePostgresTest {
    @Test fun completeCoverageIsRepeatableAndDoesNotGrantCommitAuthority() =
        withAdoptionCoverage {
            mapAll()
            val reader = PostgreSQLIdentityAdoptionCoverage(auth.source)
            val value = assertNotNull(reader.read(held, auth.clock.now()))
            assertEquals(value, reader.read(held, auth.clock.now()))
            assertTrue(value.source.fenced)
            assertEquals(3L, value.mappingCount)
            assertEquals(1L, value.targetRecords)
            assertEquals(0L, value.targetMedia)
            assertTrue(value.digest.matches(Regex("[0-9a-f]{64}")))
            assertNotEquals(value.source.digest, value.digest)
            assertEquals(IdentityAdoptionPhase.FENCED, assertNotNull(auth.operations.get(held.binding.accountId, held.id)).phase)
        }

    @Test fun anUnfencedOrStaleOrForeignOperationCannotProduceFinalCoverage() =
        withAdoptionCoverage {
            mapAll()
            val reader = PostgreSQLIdentityAdoptionCoverage(auth.source)
            assertNull(reader.read(auth.operation, auth.clock.now()))
            assertNull(reader.read(held.copy(revision = held.revision + 1), auth.clock.now()))
            val changed = held.copy(binding = held.binding.copy(deviceId = "other-device"))
            assertNull(reader.read(changed, auth.clock.now()))
            assertNotNull(reader.read(held, auth.clock.now()))
        }

    @Test fun missingStructuralMappingsCannotBeExcusedByACompleteRecordCopy() =
        withAdoptionCoverage {
            mapRecords()
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }

    @Test fun aNewLegacyRepresentationCannotDisappearBehindThePersonalSpaceCopy() =
        withAdoptionCoverage {
            mapAll()
            auth.source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO logdate_collection_records(user_id,collection,record_key,server_version,deleted,deleted_at) " +
                        "VALUES (?,'futureCollection','unmapped',1,TRUE,456)",
                ).use {
                    it.setObject(1, held.binding.accountId.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }

    @Test fun changedUnknownSourceMetadataInvalidatesCoverageEvenWithTheSameCounts() =
        withAdoptionCoverage {
            mapAll()
            assertNotNull(coverage())
            auth.source.blueskyTransaction {
                createStatement().use { it.execute("ALTER TABLE space_records ADD COLUMN coverage_extension TEXT") }
                createStatement().use { it.executeUpdate("UPDATE space_records SET coverage_extension = 'changed'") }
            }
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }

    @Test fun missingActualDestinationBytesInvalidateTheMappedDigest() =
        withAdoptionCoverage {
            mapAll()
            auth.source.blueskyTransaction {
                createStatement().use { it.executeUpdate("DELETE FROM identity_adoption_staged_records") }
            }
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }

    @Test fun orphanedMappingsAreRefusedAfterTheSourceRowDisappears() =
        withAdoptionCoverage {
            mapAll()
            auth.source.blueskyTransaction {
                createStatement().use { it.executeUpdate("DELETE FROM space_records") }
            }
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }

    @Test fun everySourcePageRequiresMappingsIncludingTheLastItem() =
        withAdoptionCoverage(recordCount = 35) {
            mapAll(exceptRecord = "note34")
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
            mapRecords()
            val value = assertNotNull(coverage())
            assertEquals(37L, value.mappingCount)
            assertEquals(35L, value.targetRecords)
        }

    @Test fun extraUnmappedDestinationRecordsCannotEnterTheVerifiedSet() =
        withAdoptionCoverage {
            mapAll()
            auth.source.blueskyTransaction {
                createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO identity_adoption_staged_records SELECT operation_id,account_id,hold_generation," +
                            "'extra',source_collection,'extra',source_version,source_digest,target_digest,target_cid," +
                            "target_record,blob_cids,deleted,created_at FROM identity_adoption_staged_records",
                    )
                }
            }
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }

    @Test fun swappingTwoPreparedCopiesCannotPreserveCoverageByKeepingTheSameCounts() =
        withAdoptionCoverage(recordCount = 2) {
            mapAll(targetFor = { key -> if (key == "note") "note01" else "note" })
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }

    @Test fun actualVerifiedMediaAndRecordReferencesParticipateInCoverage() =
        withAdoptionCoverage(withMedia = true) {
            assertNotNull(media).verify(auth, held)
            mapAll()
            val value = assertNotNull(coverage())
            assertEquals(5L, value.mappingCount)
            assertEquals(1L, value.targetRecords)
            assertEquals(1L, value.targetMedia)
            assertEquals(value, coverage())
        }

    @Test fun aMissingVerifiedMediaPinInvalidatesThePreviouslyCompleteCopy() =
        withAdoptionCoverage(withMedia = true) {
            assertNotNull(media).verify(auth, held)
            mapAll()
            assertNotNull(coverage())
            auth.source.blueskyTransaction {
                createStatement().use { it.executeUpdate("DELETE FROM identity_adoption_staged_blobs") }
            }
            assertFailsWith<IdentityAdoptionStagingException> { coverage() }
        }
}
