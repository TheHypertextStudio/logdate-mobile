@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.shared.model.AccountStorageMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class IdentityAdoptionRetiredSourcesPostgresTest {
    @Test fun aSameEpochCompletedMoveKeepsLegacyJournalRowsAsHistoryWithoutResurrectingThem() {
        for (phase in listOf("switched", "cleaned")) {
            withGenericAdoption {
                auth.seedCompletedStorageMove(phase = phase)
                hold()
                auth.mapStorageAuthority(current)
                retainLegacySource()
                val proof = assertNotNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now()))
                assertEquals(2L, proof.mappingCount)
                assertEquals(0L, proof.targetRecords)
                assertEquals(0L, proof.originCount)
            }
        }
    }

    @Test fun modeAloneAndStaleOrRevertedReceiptsCannotRetireLegacyData() {
        for ((epoch, phase) in listOf(1L to "switched", 0L to "reverted", 0L to null)) {
            withGenericAdoption {
                auth.seedCompletedStorageMove(epoch = epoch, phase = phase)
                hold()
                auth.mapStorageAuthority(current)
                assertFailsWith<IdentityAdoptionStagingException> {
                    retainLegacySource()
                    PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now())
                }
            }
        }
    }

    @Test fun anUnknownLegacyContentTypeIsNotRetiredByAKnownJournalMigration() =
        withGenericAdoption {
            auth.seedCompletedStorageMove()
            auth.source.blueskyTransaction {
                createStatement().use { it.executeUpdate("UPDATE sync_content SET type = 'FUTURE_TYPE'") }
            }
            hold()
            auth.mapStorageAuthority(current)
            assertFailsWith<IdentityAdoptionStagingException> {
                retainLegacySource()
                PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now())
            }
        }

    private suspend fun IdentityAdoptionGenericFixture.retainLegacySource() {
        val source = request()
        val mapping =
            IdentityAdoptionMappingRequest(
                source.source,
                source.sourceDigest,
                IdentityAdoptionDisposition.RETAINED_HISTORY,
                emptyList(),
                emptyList(),
            )
        val ticket =
            assertNotNull(
                auth.actions.issue(
                    current,
                    IdentityAdoptionAction.STAGE_RECORDS,
                    prepareIdentityAdoptionMapping(mapping).bodyDigest,
                    auth.clock.now(),
                ),
            )
        assertNotNull(
            PostgreSQLIdentityAdoptionMappings(auth.source).put(
                current,
                ticket,
                auth.actionSignature(ticket, current),
                mapping,
                Long.MAX_VALUE,
                auth.clock.now(),
            ),
        )
    }
}

internal suspend fun IdentityAdoptionAuthorizedFixture.seedCompletedStorageMove(
    epoch: Long = 0,
    phase: String? = "switched",
) {
    source.blueskyTransaction {
        prepareStatement("UPDATE accounts SET storage_mode = ? WHERE id = ?").use {
            it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            check(it.executeUpdate() == 1)
        }
        if (phase != null) {
            prepareStatement(
                "INSERT INTO space_migrations(account_id,migration_id,phase,version,started_at,updated_at," +
                    "switched_at,switched_at_seq,identity_epoch,reverted_at,cleaned_at) VALUES (?,'synthetic-move',?,3,?,?,?,7,?,?,?) " +
                    "ON CONFLICT(account_id) DO UPDATE SET phase = EXCLUDED.phase,identity_epoch = EXCLUDED.identity_epoch",
            ).use {
                it.setObject(1, operation.binding.accountId.toJavaUUID())
                it.setString(2, phase)
                for (i in 3..5) it.setTimestamp(i, clock.now().timestamp())
                it.setLong(6, epoch)
                it.setTimestamp(7, if (phase == "reverted") clock.now().timestamp() else null)
                it.setTimestamp(8, if (phase == "cleaned") clock.now().timestamp() else null)
                check(it.executeUpdate() == 1)
            }
        }
    }
}
