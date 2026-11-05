@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionCommitRequest
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionVerificationRequest
import app.logdate.space.SpaceRef
import app.logdate.space.adoption.IdentityAdoptionBinding
import app.logdate.space.adoption.IdentityAdoptionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import studio.hypertext.atproto.lexicon.data.Bytes
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class IdentityAdoptionPromotionPostgresTest {
    @Test fun tombstonesKeepTheirDeletionTimeAndIndependentDestinationSequence() =
        withAdoptionCommit(deleted = true) {
            assertNotNull(commit())
            auth.source.blueskyTransaction {
                createStatement().use {
                    it.executeQuery("SELECT seq,cid,record,size_bytes,deleted,deleted_at FROM space_records").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1L, rows.getLong("seq"))
                        assertNull(rows.getString("cid"))
                        assertNull(rows.getBytes("record"))
                        assertEquals(0L, rows.getLong("size_bytes"))
                        assertTrue(rows.getBoolean("deleted"))
                        assertEquals("1970-01-01T00:00:01.700Z", rows.getTimestamp("deleted_at").connectionInstant().toString())
                    }
                    it.executeQuery("SELECT last_seq FROM space_states").use { rows ->
                        check(rows.next())
                        assertEquals(1L, rows.getLong(1))
                    }
                }
            }
            assertEquals(1L, count("identity_adoption_record_receipts"))
            assertEquals(1L, count("identity_adoption_origin_receipts"))
        }

    @Test fun simultaneousIdenticalSignedRequestsReturnOneImmutableReceipt() =
        withAdoptionCommit {
            val ticket = ticket()
            val signature = auth.actionSignature(ticket, operation)
            val results =
                coroutineScope {
                    val first = async(Dispatchers.IO) { service.commit(operation, ticket, signature, request, Long.MAX_VALUE) }
                    val second = async(Dispatchers.IO) { service.commit(operation, ticket, signature, request, Long.MAX_VALUE) }
                    listOf(first.await(), second.await())
                }
            assertNotNull(results.first())
            assertEquals(results.first(), results.last())
            assertEquals(1L, count("identity_adoption_receipts"))
            assertEquals(1L, count("retired_identity_dids"))
        }

    @Test fun expiryAndEpochChangesRefuseUnconsumedCommitAuthority() =
        withAdoptionCommit {
            val ticket = ticket()
            val signature = auth.actionSignature(ticket, operation)
            val consumed = auth.countConsumed()
            auth.clock.time += 121.seconds
            assertNull(service.commit(operation, ticket, signature, request, Long.MAX_VALUE))
            auth.clock.time -= 121.seconds
            auth.source.blueskyTransaction { createStatement().use { it.executeUpdate("UPDATE accounts SET identity_epoch = 1") } }
            assertNull(service.commit(operation, ticket, signature, request, Long.MAX_VALUE))
            assertEquals(consumed, auth.countConsumed())
            assertEquals(0L, count("retired_identity_dids"))
        }

    @Test fun aPreexistingTargetNamespaceIsNeverOverwritten() =
        withAdoptionCommit {
            auth.source.blueskyTransaction {
                prepareStatement("INSERT INTO space_states(space_uri,author_did,account_id,last_seq) VALUES (?,?,?,42)").use {
                    it.setString(1, SpaceRef.personal(operation.binding.targetDid).uri)
                    it.setString(2, operation.binding.targetDid)
                    it.setObject(3, operation.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            assertFailsWith<IdentityAdoptionStagingException> { commit() }
            assertEquals(operation.binding.sourceDid, accountState().first())
            assertEquals(1L, count("identity_adoption_staged_records"))
        }

    @Test fun receiptsAreImmutableAndRetirementSurvivesAccountDeletion() =
        withAdoptionCommit {
            assertNotNull(commit())
            for (table in listOf("identity_adoption_receipts", "identity_adoption_record_receipts", "identity_adoption_origin_receipts")) {
                assertFailsWith<SQLException> {
                    auth.source.blueskyTransaction {
                        createStatement().use {
                            it.executeUpdate(
                                "UPDATE $table SET operation_id = operation_id",
                            )
                        }
                    }
                }
            }
            auth.source.blueskyTransaction {
                prepareStatement("DELETE FROM accounts WHERE id = ?").use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
                assertFalse(identityClaimAvailable(operation.binding.sourceDid))
            }
            assertEquals(1L, count("retired_identity_dids"))
            assertEquals(0L, count("identity_adoption_receipts"))
            assertEquals(0L, count("identity_adoption_record_receipts"))
            assertEquals(0L, count("identity_adoption_origin_receipts"))
        }

    @Test fun exactReplayDoesNotAuthorizeAnAccountAtAnotherCanonicalEpoch() =
        withAdoptionCommit {
            val ticket = ticket()
            val signature = auth.actionSignature(ticket, operation)
            assertNotNull(service.commit(operation, ticket, signature, request, Long.MAX_VALUE))
            auth.source.blueskyTransaction { createStatement().use { it.executeUpdate("UPDATE accounts SET identity_epoch = 2") } }
            assertNull(service.commit(operation, ticket, signature, request, Long.MAX_VALUE))
            assertEquals(1L, count("retired_identity_dids"))
        }

    @Test fun promotedMediaRetainsOnePhysicalPathAndChargesBothRetainedLogicalCopies() =
        withAdoptionCoverage(withMedia = true) {
            val media = assertNotNull(media)
            media.verify(auth, held)
            mapAll()
            val coverage = assertNotNull(coverage())
            val manifest =
                auth.source.blueskyTransaction {
                    val inventory = IdentityAdoptionManifestInventory(this, held, coverage.source.generation)
                    IdentityAdoptionManifest(
                        IdentityAdoptionBinding(
                            Uuid.parse(held.id),
                            held.binding.accountId,
                            auth.device.origin,
                            held.binding.deviceId,
                            SpaceRef.personal(held.binding.sourceDid),
                            SpaceRef.personal(held.binding.targetDid),
                            0,
                            inventory.sourceSequence(),
                        ),
                        inventory.records(),
                        inventory.blobs(),
                        Bytes(ByteArray(32) { 17 }),
                        Bytes(ByteArray(32) { 19 }),
                    )
                }
            val verifier = PostgreSQLIdentityAdoptionVerification(auth.source, auth.clock)
            val verifyRequest = IdentityAdoptionVerificationRequest(manifest.canonicalBytes(), coverage.digest)
            val verifyTicket =
                assertNotNull(auth.actions.issue(held, IdentityAdoptionAction.VERIFY, verificationDigest(verifyRequest), auth.clock.now()))
            val verified =
                assertNotNull(verifier.verify(held, verifyTicket, auth.actionSignature(verifyTicket, held), verifyRequest, Long.MAX_VALUE))
            val request = IdentityAdoptionCommitRequest(verified.manifestDigest, verified.coverageDigest)
            val ticket =
                assertNotNull(
                    auth.actions.issue(verified.operation, IdentityAdoptionAction.COMMIT, adoptionCommitDigest(request), auth.clock.now()),
                )
            val receipt =
                assertNotNull(
                    PostgreSQLIdentityAdoptionCommit(auth.source, auth.clock).commit(
                        verified.operation,
                        ticket,
                        auth.actionSignature(ticket, verified.operation),
                        request,
                        Long.MAX_VALUE,
                    ),
                )
            val target = SpaceRef.personal(receipt.targetDid).uri
            val blob = assertNotNull(PostgreSQLSpaceStore().getBlob(target, media.blob.cid))
            assertEquals(media.blob.storagePath, blob.storagePath)
            assertEquals(media.blob.size, blob.size)
            auth.source.blueskyTransaction {
                assertTrue(identityAdoptionRetainsObject(media.blob.storagePath))
                createStatement().use {
                    it.executeQuery("SELECT count(*),count(DISTINCT storage_path),SUM(size_bytes) FROM space_blobs").use { rows ->
                        check(rows.next())
                        assertEquals(2L, rows.getLong(1))
                        assertEquals(1L, rows.getLong(2))
                        assertEquals(media.blob.size * 2, rows.getLong(3))
                    }
                    it.executeQuery(COMPUTED_SPACE_USAGE).use { rows ->
                        check(rows.next())
                        assertEquals(rows.getLong(1), rows.getLong(2))
                    }
                }
            }
        }

    private companion object {
        val COMPUTED_SPACE_USAGE =
            "SELECT (SELECT space_bytes FROM account_storage_usage), " +
                "(SELECT COALESCE(SUM(size_bytes),0) FROM space_records WHERE NOT deleted) + " +
                "(SELECT COALESCE(SUM(size_bytes),0) FROM space_blobs) + " +
                "(SELECT COALESCE(SUM(octet_length(source_key)+octet_length(body_bytes)),0) FROM identity_adoption_source_mappings) + " +
                "(SELECT COALESCE(SUM(octet_length(manifest_bytes)),0) FROM identity_adoption_verifications) + " +
                "(SELECT COALESCE(SUM(octet_length(body_bytes)),0) FROM identity_adoption_record_receipts) + " +
                "(SELECT COALESCE(SUM(octet_length(body_bytes)),0) FROM identity_adoption_origin_receipts)"
    }
}
