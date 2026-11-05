@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.shared.model.AccountStorageMode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class IdentityAdoptionStorageAuthorityPostgresTest {
    @Test fun authorityIsAnOwnedNarrowRequiredSourceRatherThanAnAccountSecretDump() =
        withIdentityAdoptionAuthorization {
            createTestAccount(Uuid.random().toJavaUUID(), "did:web:foreign.example")
            authorize()
            val page =
                assertNotNull(
                    PostgreSQLIdentityAdoptionSourceInventory(source).page(
                        operation,
                        IdentityAdoptionSourceKind.STORAGE_AUTHORITY,
                        now = clock.now(),
                    ),
                )
            assertEquals(1L, page.count)
            assertEquals(true, page.present)
            assertNull(page.next)
            val row = page.items.single()
            assertEquals(listOf(operation.binding.accountId.toString()), row.key)
            assertEquals(JsonPrimitive(operation.binding.sourceDid), row.fields["repo_did"])
            assertEquals(JsonPrimitive(operation.binding.sourceEpoch), row.fields["identity_epoch"])
            assertEquals(JsonNull, row.fields["migration_id"])
            assertEquals(
                setOf(
                    "account_id",
                    "repo_did",
                    "identity_epoch",
                    "storage_mode",
                    "migration_id",
                    "migration_phase",
                    "migration_version",
                    "migration_identity_epoch",
                    "migration_switched_at",
                    "migration_switched_at_seq",
                    "migration_reverted_at",
                    "migration_cleaned_at",
                ),
                row.fields.keys,
            )
            assertFalse("signing_key_public" in row.fields)
            assertNull(row.binary)
        }

    @Test fun aMigrationReceiptChangesTheCensusWithoutChangingJournalCounts() =
        withIdentityAdoptionAuthorization {
            authorize()
            val census = PostgreSQLIdentityAdoptionSourceCensus(source)
            val before = assertNotNull(census.read(operation, clock.now()))
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET storage_mode = ? WHERE id = ?").use {
                    it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                    it.setObject(2, operation.binding.accountId.toJavaUUID())
                    check(it.executeUpdate() == 1)
                }
                prepareStatement(
                    "INSERT INTO space_migrations(account_id,migration_id,phase,version,started_at,updated_at," +
                        "switched_at,switched_at_seq,identity_epoch) VALUES (?,'synthetic-migration','switched',3,?,?,?,7,0)",
                ).use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    for (i in 2..4) it.setTimestamp(i, clock.now().timestamp())
                    check(it.executeUpdate() == 1)
                }
            }
            val after = assertNotNull(census.read(operation, clock.now()))
            assertEquals(before.sources.map { it.count }, after.sources.map { it.count })
            assertNotEquals(before.digest, after.digest)
            val row =
                assertNotNull(
                    PostgreSQLIdentityAdoptionSourceInventory(source).page(
                        operation,
                        IdentityAdoptionSourceKind.STORAGE_AUTHORITY,
                        now = clock.now(),
                    ),
                ).items.single()
            assertEquals(JsonPrimitive("switched"), row.fields["migration_phase"])
            assertEquals(JsonPrimitive(7), row.fields["migration_switched_at_seq"])
            assertEquals(JsonPrimitive(0), row.fields["migration_identity_epoch"])
        }
}
