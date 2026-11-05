@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.space.SpaceRef
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class IdentityAdoptionCommittedStagingCleanupPostgresTest {
    @Test fun committedRecoveryRetainsItsFinalGenerationWhileBoundedGcReleasesSupersededCopies() =
        withAdoptionCommit {
            val before = fixture.copies.used()
            auth.source.blueskyTransaction {
                val generation =
                    prepareStatement("SELECT hold_generation FROM identity_adoption_operations WHERE id=?").use {
                        it.setString(1, operation.id)
                        it.executeQuery().use { rows ->
                            check(rows.next())
                            rows.getLong(1)
                        }
                    }
                assertTrue(generation > 1)
                for ((table, column) in GENERATIONS) cloneEarlier(table, column, operation.id, generation)
                prepareStatement(
                    "INSERT INTO identity_adoption_staged_records(operation_id,account_id,hold_generation,rkey,source_collection," +
                        "source_rkey,source_version,source_digest,target_digest,target_cid,target_record,blob_cids,deleted,created_at," +
                        "target_sequence) " +
                        "SELECT operation_id,account_id,hold_generation,rkey||'-unused',source_collection,source_rkey||'-unused'," +
                        "source_version,source_digest,target_digest,target_cid,target_record,blob_cids,deleted,created_at," +
                        "target_sequence+100 " +
                        "FROM identity_adoption_staged_records WHERE operation_id=? AND hold_generation=?",
                ).use {
                    it.setString(1, operation.id)
                    it.setLong(2, generation - 1)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val obsoleteBytes = fixture.copies.used() - before
            assertTrue(obsoleteBytes > 0)
            val proof = ticket()
            val signature = auth.actionSignature(proof, operation)
            val receipt = assertNotNull(service.commit(operation, proof, signature, request, Long.MAX_VALUE))
            val committedBytes = fixture.copies.used()
            val cleanup = PostgreSQLIdentityAdoptionStagingCleanup(auth.source, auth.clock, batchSize = 1)
            assertFalse(operation.id in cleanup.pending())
            assertFalse(cleanup.cleanup(operation.id))
            auth.clock.time += 8.days
            assertTrue(operation.id in cleanup.pending())
            assertFalse(cleanup.cleanup(operation.id))
            assertTrue(cleanup.cleanup(operation.id))
            assertEquals(committedBytes - obsoleteBytes, fixture.copies.used())
            assertFalse(operation.id in cleanup.pending())
            assertFalse(cleanup.cleanup(operation.id))
            assertEquals(1L, count("identity_adoption_receipts"))
            assertEquals(1L, count("identity_adoption_record_receipts"))
            assertEquals(1L, count("identity_adoption_origin_receipts"))
            assertEquals(1L, fixture.count())
            val target = SpaceRef.personal(operation.binding.targetDid).uri
            assertNotNull(PostgreSQLSpaceStore().get(target, operation.binding.targetDid, "converted-note"))
            assertEquals(receipt, service.commit(operation, proof, signature, request, Long.MAX_VALUE))
        }

    private fun Connection.cloneEarlier(
        table: String,
        generationColumn: String,
        operation: String,
        generation: Long,
    ) {
        val columns =
            prepareStatement("SELECT * FROM $table WHERE FALSE").use {
                it.executeQuery().use { rows -> (1..rows.metaData.columnCount).map(rows.metaData::getColumnName) }
            }
        val selections =
            columns.map {
                when (it) {
                    generationColumn, "source_revision", "verified_revision" -> "$it-1"
                    "challenge_id" -> "gen_random_uuid()"
                    else -> it
                }
            }
        prepareStatement(
            "INSERT INTO $table (${columns.joinToString()}) SELECT ${selections.joinToString()} " +
                "FROM $table WHERE operation_id=? AND $generationColumn=?",
        ).use {
            it.setString(1, operation)
            it.setLong(2, generation)
            assertTrue(it.executeUpdate() > 0, table)
        }
    }

    private companion object {
        val GENERATIONS =
            listOf(
                "identity_adoption_staged_records" to "hold_generation",
                "identity_adoption_staged_origins" to "copy_generation",
                "identity_adoption_source_mappings" to "copy_generation",
                "identity_adoption_verifications" to "hold_generation",
            )
    }
}
