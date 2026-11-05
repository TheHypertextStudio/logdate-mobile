@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.hasSupportedSessionIdentity
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.shared.model.AccountStorageMode
import app.logdate.space.SpaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IdentityAdoptionCommitPostgresTest {
    @Test fun commitPromotesVerifiedBytesAndRetiresOnlyTheOldPrivateAuthority() =
        withAdoptionCommit {
            seedAccountMaterial()
            val source = operation.binding.sourceDid
            val receipt = assertNotNull(commit())
            assertEquals(operation.id, receipt.operationId)
            assertEquals(operation.binding.accountId, receipt.accountId)
            assertEquals(source, receipt.sourceDid)
            assertEquals(operation.binding.targetDid, receipt.targetDid)
            assertEquals(1L, receipt.identityEpoch)
            assertTrue(assertNotNull(PostgreSQLAccountRepository().findById(receipt.accountId)).hasSupportedSessionIdentity())
            val state = accountState()
            assertEquals(
                listOf(
                    operation.binding.targetDid,
                    "external",
                    AccountStorageMode.PERSONAL_SPACE_V1.wireValue,
                    "1",
                    null,
                    null,
                    null,
                    null,
                ),
                state,
            )
            assertEquals(IdentityAdoptionPhase.COMMITTED, assertNotNull(auth.operations.get(receipt.accountId, receipt.operationId)).phase)
            val target = SpaceRef.personal(receipt.targetDid).uri
            val record = assertNotNull(PostgreSQLSpaceStore().get(target, receipt.targetDid, "converted-note"))
            assertEquals(1L, record.seq)
            assertEquals(genericSealed("target"), record.record)
            assertEquals(1L, count("retired_identity_dids"))
            assertEquals(1L, count("identity_adoption_receipts"))
            assertEquals(1L, count("identity_adoption_record_receipts"))
            assertEquals(1L, count("identity_adoption_origin_receipts"))
            assertEquals(0L, count("identity_adoption_staged_records"))
            assertEquals(0L, count("identity_adoption_reservations"))
            auth.source.blueskyTransaction {
                assertFalse(identityClaimAvailable(source))
                createStatement().use {
                    it.executeQuery("SELECT count(*) FROM signing_keys WHERE purpose = 'atproto' AND revoked_at IS NULL").use { rows ->
                        check(rows.next())
                        assertEquals(0L, rows.getLong(1))
                    }
                    it
                        .executeQuery(
                            "SELECT count(*) FROM signing_keys " +
                                "WHERE purpose = 'device-transport' AND revoked_at IS NULL",
                        ).use { rows ->
                            check(rows.next())
                            assertEquals(1L, rows.getLong(1))
                        }
                    it.executeQuery("SELECT ciphertext FROM account_key_envelopes").use { rows ->
                        check(rows.next())
                        assertEquals("a".repeat(132), rows.getString(1))
                    }
                    it.executeQuery("SELECT count(*) FROM passkeys WHERE is_active").use { rows ->
                        check(rows.next())
                        assertEquals(1L, rows.getLong(1))
                    }
                    it.executeQuery("SELECT count(*) FROM account_devices").use { rows ->
                        check(rows.next())
                        assertEquals(1L, rows.getLong(1))
                    }
                    it.executeQuery("SELECT content FROM sync_content WHERE id = 'legacy-note'").use { rows ->
                        check(rows.next())
                        assertEquals("opaque source", rows.getString(1))
                    }
                }
            }
        }

    @Test fun aLostResponseCanReplayOnlyTheExactOriginalSignedCommitEvenAfterLeaseExpiry() =
        withAdoptionCommit {
            val ticket = ticket()
            val signature = auth.actionSignature(ticket, operation)
            val first = assertNotNull(service.commit(operation, ticket, signature, request, Long.MAX_VALUE))
            val consumed = auth.countConsumed()
            auth.clock.time += 3600.seconds
            assertEquals(first, service.commit(operation, ticket, signature, request, Long.MAX_VALUE))
            assertNull(service.commit(operation, ticket, invalidSignature(), request, Long.MAX_VALUE))
            assertEquals(consumed, auth.countConsumed())
            assertEquals(1L, count("identity_adoption_receipts"))
            assertEquals(first, auth.operations.receipt(first.accountId, first.operationId))
        }

    @Test fun wrongManifestAndCoverageCommitmentsCannotSwitchTheAccount() =
        withAdoptionCommit {
            val before = accountState()
            val consumed = auth.countConsumed()
            for (value in listOf(request.copy(manifestDigest = "0".repeat(64)), request.copy(coverageDigest = "0".repeat(64)))) {
                assertFailsWith<IdentityAdoptionStagingException> { commit(value) }
            }
            assertEquals(before, accountState())
            assertEquals(consumed, auth.countConsumed())
        }

    @Test fun aMissingVerificationRecordCannotBeReplacedByAnOperationPhaseAlone() =
        withAdoptionCommit {
            auth.source.blueskyTransaction { createStatement().use { it.executeUpdate("DELETE FROM identity_adoption_verifications") } }
            assertFailsWith<IdentityAdoptionStagingException> { commit() }
            assertEquals(operation.binding.sourceDid, accountState().first())
        }

    @Test fun changedSourceEvidenceAfterVerificationPreventsPromotion() =
        withAdoptionCommit {
            val consumed = auth.countConsumed()
            fixture.copies.edit()
            assertFailsWith<IdentityAdoptionStagingException> { commit() }
            assertEquals(consumed, auth.countConsumed())
            assertEquals(operation.binding.sourceDid, accountState().first())
        }

    @Test fun aPendingProviderMutationPreventsCutoverEvenWhenRecordCoverageMatches() =
        withAdoptionCommit {
            auth.source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO object_upload_intents(id,account_id,identity_epoch,upload_kind,storage_path,phase) " +
                        "VALUES ('pending',?,0,'MEDIA','synthetic-pending','WRITING')",
                ).use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            assertFailsWith<IdentityAdoptionStagingException> { commit() }
            assertEquals(operation.binding.sourceDid, accountState().first())
        }

    @Test fun insufficientQuotaRollsBackEveryPromotedRowReceiptAndConsumedNonce() =
        withAdoptionCommit {
            val before = accountState()
            val consumed = auth.countConsumed()
            val ticket = ticket()
            val signature = auth.actionSignature(ticket, operation)
            assertFailsWith<IdentityAdoptionStagingException> { service.commit(operation, ticket, signature, request, 0) }
            assertEquals(before, accountState())
            assertEquals(consumed, auth.countConsumed())
            assertEquals(0L, count("identity_adoption_receipts"))
            assertEquals(0L, count("identity_adoption_record_receipts"))
            assertEquals(1L, count("identity_adoption_staged_records"))
            assertNotNull(service.commit(operation, ticket, signature, request, Long.MAX_VALUE))
        }

    @Test fun invalidSignaturesAndStaleDeviceBindingsNeverConsumeCommitAuthority() =
        withAdoptionCommit {
            val ticket = ticket()
            val consumed = auth.countConsumed()
            assertNull(service.commit(operation, ticket, invalidSignature(), request, Long.MAX_VALUE))
            val changed = operation.copy(binding = operation.binding.copy(deviceId = "other-device"))
            assertNull(service.commit(changed, ticket, auth.actionSignature(ticket, operation), request, Long.MAX_VALUE))
            assertEquals(consumed, auth.countConsumed())
            assertEquals(operation.binding.sourceDid, accountState().first())
        }

    @Test fun publishedOrEpochChangedSourcesCannotBeRetiredByOldAuthorization() =
        withAdoptionCommit {
            val ticket = ticket()
            val signature = auth.actionSignature(ticket, operation)
            auth.source.blueskyTransaction {
                prepareStatement("INSERT INTO identity_publication_attempts(did,attempted_at) VALUES (?,?)").use {
                    it.setString(1, operation.binding.sourceDid)
                    it.setTimestamp(2, auth.clock.now().timestamp())
                    it.executeUpdate()
                }
            }
            assertNull(service.commit(operation, ticket, signature, request, Long.MAX_VALUE))
            assertEquals(operation.binding.sourceDid, accountState().first())
        }

    @Test fun expiredOrRenewedHoldsCannotUseAnOldVerifiedCommit() =
        withAdoptionCommit {
            val ticket = ticket()
            val signature = auth.actionSignature(ticket, operation)
            val next =
                auth.operations.transition(
                    operation.binding.accountId,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    auth.clock.now(),
                ) as IdentityAdoptionOutcome.Accepted
            assertEquals(IdentityAdoptionPhase.FENCED, next.value.phase)
            assertNull(service.commit(operation, ticket, signature, request, Long.MAX_VALUE))
        }

    @Test fun expiryDuringPromotionRollsBackWithoutLeavingNewCanonicalData() =
        withAdoptionCommit {
            val ticket = ticket()
            val signature = auth.actionSignature(ticket, operation)
            val consumed = auth.countConsumed()
            val start = auth.clock.now()
            val observed =
                IdentityAdoptionAfterStatementFixture(auth.source, "INSERT INTO space_records", "executeUpdate") {
                    auth.clock.time = start + 121.seconds
                }
            assertFailsWith<IdentityAdoptionStagingException> {
                PostgreSQLIdentityAdoptionCommit(observed, auth.clock).commit(operation, ticket, signature, request, Long.MAX_VALUE)
            }
            assertTrue(observed.executed)
            assertEquals(consumed, auth.countConsumed())
            assertEquals(operation.binding.sourceDid, accountState().first())
            assertEquals(0L, count("identity_adoption_receipts"))
            val target = SpaceRef.personal(operation.binding.targetDid).uri
            assertNull(PostgreSQLSpaceStore().get(target, operation.binding.targetDid, "converted-note"))
        }
}
