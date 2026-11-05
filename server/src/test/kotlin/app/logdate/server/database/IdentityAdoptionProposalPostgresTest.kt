@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityAdoptionProposalPostgresTest {
    @Test
    fun retriesReturnTheSameProposalOperationAcrossInstances() =
        fixture { source, binding ->
            val proposal = Uuid.random()
            val one = PostgreSQLIdentityAdoptionRepository(source)
            val first = accepted(one.beginForProposal(binding, proposal, now))
            assertEquals(proposal.toString(), first.id)
            assertEquals(first, accepted(PostgreSQLIdentityAdoptionRepository(source).beginForProposal(binding, proposal, now)))
            assertEquals(1, count(source, "identity_adoption_operations"))
            assertEquals(1, count(source, "identity_adoption_reservations"))
        }

    @Test
    fun cancellationCannotBeResurrectedByTheSameBrowserProposal() =
        fixture { source, binding ->
            val proposal = Uuid.random()
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val operation = accepted(repository.beginForProposal(binding, proposal, now))
            accepted(repository.transition(binding.accountId, operation.id, operation.revision, IdentityAdoptionPhase.CANCELED, now))
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.INVALID_TRANSITION),
                repository.beginForProposal(binding, proposal, now),
            )
            assertEquals(1, count(source, "identity_adoption_operations"))
            assertEquals(0, count(source, "identity_adoption_reservations"))
        }

    @Test
    fun anOldProposalCannotReturnOrCancelTheReplacementOperation() =
        fixture { source, binding ->
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val old = Uuid.random()
            val first = accepted(repository.beginForProposal(binding, old, now))
            accepted(repository.transition(binding.accountId, first.id, first.revision, IdentityAdoptionPhase.CANCELED, now))
            val next = accepted(repository.beginForProposal(binding, Uuid.random(), now))
            assertIs<IdentityAdoptionOutcome.Refused>(repository.beginForProposal(binding, old, now))
            assertEquals(next, repository.get(binding.accountId, next.id))
            assertEquals(1, count(source, "identity_adoption_reservations"))
        }

    @Test
    fun aDifferentProposalCannotBorrowAnActiveOperationEvenWithTheSameIdentityFields() =
        fixture { source, binding ->
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val first = accepted(repository.beginForProposal(binding, Uuid.random(), now))
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.ACTIVE_OPERATION),
                repository.beginForProposal(binding, Uuid.random(), now),
            )
            assertEquals(first, repository.get(binding.accountId, first.id))
        }

    @Test
    fun concurrentRetriesOfOneProposalCreateExactlyOneOperation() =
        fixture { source, binding ->
            val proposal = Uuid.random()
            val results =
                coroutineScope {
                    (1..8)
                        .map {
                            async(Dispatchers.IO) {
                                accepted(PostgreSQLIdentityAdoptionRepository(source).beginForProposal(binding, proposal, now))
                            }
                        }.awaitAll()
                }
            assertEquals(setOf(proposal.toString()), results.map { it.id }.toSet())
            assertEquals(1, count(source, "identity_adoption_operations"))
            assertEquals(1, count(source, "identity_adoption_reservations"))
        }

    @Test
    fun concurrentDifferentProposalsHaveOnlyOneAcceptedWinner() =
        fixture { source, binding ->
            val results =
                coroutineScope {
                    (1..2)
                        .map {
                            async(Dispatchers.IO) {
                                PostgreSQLIdentityAdoptionRepository(source).beginForProposal(binding, Uuid.random(), now)
                            }
                        }.awaitAll()
                }
            assertEquals(1, results.count { it is IdentityAdoptionOutcome.Accepted })
            assertEquals(1, results.count { it == IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.ACTIVE_OPERATION) })
        }

    @Test
    fun anotherAccountCannotUseTheSameProposalIdentifier() =
        fixture { source, binding ->
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val proposal = Uuid.random()
            val first = accepted(repository.beginForProposal(binding, proposal, now))
            val other = owner(source, "other")
            assertIs<IdentityAdoptionOutcome.Refused>(repository.beginForProposal(other, proposal, now))
            assertEquals(first, repository.get(binding.accountId, first.id))
            assertEquals(1, count(source, "identity_adoption_operations"))
        }

    @Test
    fun aCommittedProposalReturnsItsOriginalOperationAfterTheCanonicalIdentityChanged() =
        fixture { source, binding ->
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val proposal = Uuid.random()
            val operation = accepted(repository.beginForProposal(binding, proposal, now))
            source.blueskyTransaction {
                prepareStatement("UPDATE identity_adoption_operations SET phase = 'COMMITTED', revision = revision + 1 WHERE id = ?").use {
                    it.setString(1, operation.id)
                    check(it.executeUpdate() == 1)
                }
                prepareStatement(
                    "INSERT INTO identity_adoption_receipts(operation_id, account_id, source_did, target_did, " +
                        "identity_epoch, committed_at) VALUES (?, ?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, operation.id)
                    it.setObject(2, binding.accountId.toJavaUUID())
                    it.setString(3, binding.sourceDid)
                    it.setString(4, binding.targetDid)
                    it.setTimestamp(5, now.timestamp())
                    check(it.executeUpdate() == 1)
                }
                prepareStatement(
                    "UPDATE accounts SET did = ?, identity_epoch = 1, identity_kind = 'external', " +
                        "storage_mode = 'personalSpaceV1' WHERE id = ?",
                ).use {
                    it.setString(1, binding.targetDid)
                    it.setObject(2, binding.accountId.toJavaUUID())
                    check(it.executeUpdate() == 1)
                }
            }
            assertEquals(operation.id, accepted(repository.beginForProposal(binding, proposal, now)).id)
            val receipt = assertNotNull(repository.receipt(binding.accountId, operation.id))
            assertEquals(binding.targetDid, receipt.targetDid)
            assertEquals(1L, receipt.identityEpoch)
            assertEquals(1, count(source, "identity_adoption_operations"))
        }

    @Test
    fun sameProposalCannotBeReboundToAnotherTargetOrDevice() =
        fixture { source, binding ->
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val proposal = Uuid.random()
            val first = accepted(repository.beginForProposal(binding, proposal, now))
            for (other in listOf(binding.copy(targetDid = "did:web:another.example"), binding.copy(deviceId = "another-device"))) {
                assertIs<IdentityAdoptionOutcome.Refused>(repository.beginForProposal(other, proposal, now))
            }
            assertEquals(first, repository.get(binding.accountId, first.id))
        }

    private fun fixture(block: suspend (DataSource, IdentityAdoptionBinding) -> Unit) =
        withBlueskyOAuthDatabase { source ->
            block(source, owner(source, "owner"))
        }

    private suspend fun owner(
        source: DataSource,
        name: String,
    ): IdentityAdoptionBinding {
        val account = createTestAccount(Uuid.random().toJavaUUID(), "did:plc:${name.padEnd(24, 'a')}")
        source.blueskyTransaction {
            prepareStatement(
                "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                    "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
            ).use {
                it.setObject(1, Uuid.random().toJavaUUID())
                it.setObject(2, account.id.toJavaUUID())
                it.setString(3, account.did)
                it.setTimestamp(4, now.timestamp())
                check(it.executeUpdate() == 1)
            }
        }
        return IdentityAdoptionBinding(account.id, requireNotNull(account.did), "did:web:$name.example", "device-a", 0)
    }

    private fun accepted(outcome: IdentityAdoptionOutcome<IdentityAdoptionOperation>): IdentityAdoptionOperation =
        assertNotNull((outcome as? IdentityAdoptionOutcome.Accepted)?.value)

    private suspend fun count(
        source: DataSource,
        table: String,
    ): Int =
        source.blueskyTransaction {
            createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use {
                    check(it.next())
                    it.getInt(1)
                }
            }
        }

    companion object {
        private val now = Instant.parse("2026-10-30T00:00:00Z")
    }
}
