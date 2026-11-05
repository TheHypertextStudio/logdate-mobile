@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionVerificationRequest
import app.logdate.space.SpaceRef
import app.logdate.space.adoption.IdentityAdoptionRecordInventory
import studio.hypertext.atproto.lexicon.data.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class IdentityAdoptionVerificationPostgresTest {
    @Test fun aSignedCompleteManifestCreatesDurableVerificationWithoutSwitchingIdentity() =
        withAdoptionVerification {
            val value = assertNotNull(verify())
            assertEquals(IdentityAdoptionPhase.VERIFIED, value.operation.phase)
            assertEquals(operation.revision + 1, value.operation.revision)
            assertEquals(operation.revision, value.sourceRevision)
            assertEquals(operation.revision, value.generation)
            assertEquals(stagingHash(manifest.canonicalBytes()), value.manifestDigest)
            assertEquals(coverage, value.coverageDigest)
            assertEquals(7L, value.sourceSequence)
            assertEquals(value, verifier.get(value.operation))
            assertNull(verifier.get(operation))
            assertEquals(1L, count())
            auth.source.blueskyTransaction {
                prepareStatement("SELECT did,identity_epoch FROM accounts WHERE id = ?").use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        assertEquals(operation.binding.sourceDid, rows.getString(1))
                        assertEquals(0L, rows.getLong(2))
                    }
                }
            }
            val target = SpaceRef.personal(operation.binding.targetDid).uri
            assertNull(PostgreSQLSpaceStore().get(target, operation.binding.targetDid, "converted-note"))
        }

    @Test fun emptyOrForgedRecordInventoriesCannotVerifyRealDestinationData() =
        withAdoptionVerification {
            val before = auth.countConsumed()
            val empty = IdentityAdoptionRecordInventory().use { it.finish() }
            val forged = manifest.records.copy(digest = Bytes(ByteArray(32)))
            for (records in listOf(empty, forged)) {
                assertFailsWith<IdentityAdoptionStagingException> { verify(request(manifest.copy(records = records))) }
            }
            assertEquals(before, auth.countConsumed())
        }

    @Test fun everyManifestBindingFieldMustMatchTheAuthorizedOperation() =
        withAdoptionVerification {
            val b = manifest.binding
            val bindings =
                listOf(
                    b.copy(operationId = Uuid.random()),
                    b.copy(accountId = Uuid.random()),
                    b.copy(deviceId = "other-device"),
                    b.copy(backendOrigin = "https://other.example"),
                    b.copy(sourceEpoch = 1),
                    b.copy(sourceSequence = 8),
                    b.copy(source = SpaceRef.personal("did:web:other-source.example")),
                    b.copy(target = SpaceRef.personal("did:web:other-target.example")),
                )
            val before = auth.countConsumed()
            for (binding in bindings) {
                assertFailsWith<IdentityAdoptionStagingException> { verify(request(manifest.copy(binding = binding))) }
            }
            assertEquals(before, auth.countConsumed())
        }

    @Test fun aWrongCoverageCommitmentCannotAuthorizeVerification() =
        withAdoptionVerification {
            val before = auth.countConsumed()
            assertFailsWith<IdentityAdoptionStagingException> { verify(request(coverageDigest = "0".repeat(64))) }
            assertEquals(before, auth.countConsumed())
        }

    @Test fun sameCountSourceChangesAfterTheManifestInvalidateVerification() =
        withAdoptionVerification {
            val before = auth.countConsumed()
            copies.edit()
            assertFailsWith<IdentityAdoptionStagingException> { verify() }
            assertEquals(before, auth.countConsumed())
        }

    @Test fun changedEnvelopeBytesCannotUseAPreviouslyIssuedSignature() =
        withAdoptionVerification {
            val request = request()
            val ticket = ticket(request)
            val signature = auth.actionSignature(ticket, operation)
            val changed = request(manifest.copy(pendingCommitment = Bytes(ByteArray(32) { 99 })))
            val before = auth.countConsumed()
            assertNull(verifier.verify(operation, ticket, signature, changed, Long.MAX_VALUE))
            assertEquals(before, auth.countConsumed())
        }

    @Test fun expiredChallengesAndWrongActionPurposesCannotVerify() =
        withAdoptionVerification {
            val request = request()
            val ticket = ticket(request)
            val signature = auth.actionSignature(ticket, operation)
            val wrong =
                assertNotNull(
                    auth.actions.issue(
                        operation,
                        IdentityAdoptionAction.READ_STAGED,
                        verificationDigest(request),
                        auth.clock.now(),
                    ),
                )
            assertNull(verifier.verify(operation, wrong, auth.actionSignature(wrong, operation), request, Long.MAX_VALUE))
            val before = auth.countConsumed()
            auth.clock.time += 121.seconds
            assertNull(verifier.verify(operation, ticket, signature, request, Long.MAX_VALUE))
            assertEquals(before, auth.countConsumed())
        }

    @Test fun aLeaseThatExpiresDuringVerificationRollsBackAllAuthorityAndNonceChanges() =
        withAdoptionVerification {
            val request = request()
            val ticket = ticket(request)
            val before = auth.countConsumed()
            val start = auth.clock.now()
            val observed =
                IdentityAdoptionAfterStatementFixture(auth.source, "SELECT * FROM identity_adoption_staged_records", "executeQuery") {
                    auth.clock.time = start + 121.seconds
                }
            val service = PostgreSQLIdentityAdoptionVerification(observed, auth.clock)
            assertFailsWith<IdentityAdoptionStagingException> {
                service.verify(operation, ticket, auth.actionSignature(ticket, operation), request, Long.MAX_VALUE)
            }
            assertTrue(observed.executed)
            assertEquals(before, auth.countConsumed())
        }

    @Test fun verificationHistoryConsumesQuotaAndAFailedWriteRollsBackTheTransition() =
        withAdoptionVerification {
            val before = auth.countConsumed()
            val used = copies.used()
            val request = request()
            assertFailsWith<IdentityAdoptionStagingException> { verify(request, used + request.manifest.size - 1) }
            assertEquals(used, copies.used())
            assertEquals(before, auth.countConsumed())
            assertEquals(IdentityAdoptionPhase.FENCED, assertNotNull(auth.operations.get(operation.binding.accountId, operation.id)).phase)
            assertNotNull(verify(request, used + request.manifest.size))
            assertEquals(used + request.manifest.size, copies.used())
        }

    @Test fun malformedAndNoncanonicalManifestBytesCannotCreateVerification() =
        withAdoptionVerification {
            val before = auth.countConsumed()
            for (bytes in listOf(byteArrayOf(0x80.toByte()), manifest.canonicalBytes() + byteArrayOf(0))) {
                assertFailsWith<IdentityAdoptionStagingException> { verify(IdentityAdoptionVerificationRequest(bytes, coverage)) }
            }
            assertEquals(before, auth.countConsumed())
        }

    @Test fun renewingAVerifiedHoldRevokesThatVerificationUntilAFreshSignedCheck() =
        withAdoptionVerification {
            val value = assertNotNull(verify())
            copies.current =
                (
                    auth.operations.transition(
                        operation.binding.accountId,
                        operation.id,
                        value.operation.revision,
                        IdentityAdoptionPhase.FENCED,
                        auth.clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted
                ).value
            assertNull(verifier.get(value.operation))
            assertNull(verifier.get(operation))
            assertFailsWith<IdentityAdoptionStagingException> { verify() }
            val fresh = assertNotNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(operation, auth.clock.now()))
            val verified = assertNotNull(verify(request(coverageDigest = fresh.digest)))
            assertEquals(IdentityAdoptionPhase.VERIFIED, verified.operation.phase)
            assertEquals(2L, count())
        }

    @Test fun currentVerifiedEvidenceCanBeComparedWithItsOriginalSignedContext() =
        withAdoptionVerification {
            val value = assertNotNull(verify())
            val current = assertNotNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(value.operation, auth.clock.now()))
            assertNotEquals(value.coverageDigest, current.digest)
            val rebound = rebindIdentityAdoptionCoverage(current, value.operation, value.sourceRevision)
            assertEquals(value.coverageDigest, rebound.digest)
            assertEquals(value.sourceRevision, rebound.source.revision)
            assertNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(operation, auth.clock.now()))
            val changed = current.copy(targetInventoryDigest = "0".repeat(64))
            assertNotEquals(value.coverageDigest, rebindIdentityAdoptionCoverage(changed, value.operation, value.sourceRevision).digest)
        }

    @Test fun rebindingCannotSupplyAnArbitraryRevisionOrTreatAnUnverifiedOperationAsCurrent() =
        withAdoptionVerification {
            val value = assertNotNull(verify())
            val current = assertNotNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(value.operation, auth.clock.now()))
            assertFailsWith<IdentityAdoptionStagingException> {
                rebindIdentityAdoptionCoverage(current, value.operation, value.sourceRevision - 1)
            }
            assertFailsWith<IdentityAdoptionStagingException> {
                rebindIdentityAdoptionCoverage(current, operation, value.sourceRevision)
            }
        }
}
