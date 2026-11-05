@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityAdoptionSourceCensusPostgresTest {
    @Test fun everyRepresentationAndEveryPageParticipateInARepeatableCommitment() =
        withIdentityAdoptionAuthorization {
            seed()
            authorize()
            val reader = PostgreSQLIdentityAdoptionSourceCensus(source)
            val first = assertNotNull(reader.read(operation, clock.now()))
            val second = assertNotNull(reader.read(operation, clock.now()))
            assertEquals(IdentityAdoptionSourceKind.entries, first.sources.map { it.kind })
            assertEquals(35L, first.sources.single { it.kind == IdentityAdoptionSourceKind.COLLECTION_RECORD }.count)
            assertEquals(first, second)
            assertTrue(first.digest.matches(Regex("[0-9a-f]{64}")))
            assertFalse(first.fenced)
            assertEquals(0L, first.generation)
        }

    @Test fun unknownMetadataChangesAlterTheCommitmentWithoutChangingAnyCount() =
        withIdentityAdoptionAuthorization {
            seed()
            authorize()
            val reader = PostgreSQLIdentityAdoptionSourceCensus(source)
            val first = assertNotNull(reader.read(operation, clock.now()))
            source.blueskyTransaction {
                prepareStatement(
                    "UPDATE logdate_collection_records SET census_extension = ?::jsonb " +
                        "WHERE user_id = ? AND record_key = 'k34'",
                ).use {
                    it.setString(1, "{\"decimal\":1.25,\"future\":[true,null,\"kept\"]}")
                    it.setObject(2, operation.binding.accountId.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val changed = assertNotNull(reader.read(operation, clock.now()))
            assertEquals(first.sources.map { it.count }, changed.sources.map { it.count })
            assertNotEquals(first.digest, changed.digest)
        }

    @Test fun sqlNullAndJsonNullRemainDistinctEvenWhenTheirJsonFieldValuesMatch() =
        withIdentityAdoptionAuthorization {
            seed()
            authorize()
            val pages = PostgreSQLIdentityAdoptionSourceInventory(source)
            val beforePage = assertNotNull(pages.page(operation, IdentityAdoptionSourceKind.COLLECTION_RECORD, now = clock.now()))
            val before = beforePage.items.first()
            assertEquals(JsonNull, before.fields["census_extension"])
            assertTrue("census_extension" in before.sqlNullColumns)
            val reader = PostgreSQLIdentityAdoptionSourceCensus(source)
            val first = assertNotNull(reader.read(operation, clock.now()))
            source.blueskyTransaction {
                prepareStatement(
                    "UPDATE logdate_collection_records SET census_extension = 'null'::jsonb " +
                        "WHERE user_id = ? AND record_key = 'k00'",
                ).use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val afterPage = assertNotNull(pages.page(operation, IdentityAdoptionSourceKind.COLLECTION_RECORD, now = clock.now()))
            val after = afterPage.items.first()
            assertEquals(before.fields, after.fields)
            assertFalse("census_extension" in after.sqlNullColumns)
            assertNotEquals(first.digest, assertNotNull(reader.read(operation, clock.now())).digest)
        }

    @Test fun aFencedCensusIsBoundToTheExactHoldAndStaleReadersCannotObtainIt() =
        withIdentityAdoptionAuthorization {
            seed()
            val reader = PostgreSQLIdentityAdoptionSourceCensus(source)
            assertNull(reader.read(operation, clock.now()))
            authorize()
            val preparing = assertNotNull(reader.read(operation, clock.now()))
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
            assertNull(reader.read(operation, clock.now()))
            val census = assertNotNull(reader.read(held, clock.now()))
            assertTrue(census.fenced)
            assertEquals(held.revision, census.generation)
            assertNotEquals(preparing.digest, census.digest)
        }

    private suspend fun IdentityAdoptionAuthorizedFixture.seed() {
        source.blueskyTransaction {
            createStatement().use { it.execute("ALTER TABLE logdate_collection_records ADD COLUMN IF NOT EXISTS census_extension JSONB") }
            for (i in 0..34) {
                prepareStatement(
                    "INSERT INTO logdate_collection_records(user_id,collection,record_key," +
                        "server_version,deleted,deleted_at) " +
                        "VALUES (?,'futureCollection',?,?,TRUE,456)",
                ).use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    it.setString(2, "k" + i.toString().padStart(2, '0'))
                    it.setLong(3, i.toLong())
                    it.executeUpdate()
                }
            }
        }
    }
}
