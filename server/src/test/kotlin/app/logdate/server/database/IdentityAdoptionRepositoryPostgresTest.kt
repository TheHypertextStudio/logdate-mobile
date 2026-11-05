package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.PasskeyInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionRepositoryPostgresTest {
    @Test
    fun targetReservationExcludesSignup() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val binding = binding(owner)
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(repository.begin(binding, now))
            val incoming = external("incoming")
            val passkey = passkey("incoming-key")
            assertFalse(PostgreSQLBlueskyAccountEnrollmentRepository(source).create(incoming, passkey))
            assertNull(PostgreSQLAccountRepository().findById(incoming.id))
            assertNull(PostgreSQLPasskeyRepository().getPasskeyByCredentialId(passkey.credentialId))
            assertEquals(owner, PostgreSQLAccountRepository().findById(owner.id))
        }

    @Test
    fun competingSignupAndReservationHaveOneWinner() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val incoming = external("incoming")
            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) {
                            PostgreSQLIdentityAdoptionRepository(source).begin(binding(owner), now) is IdentityAdoptionOutcome.Accepted<*>
                        },
                        async(Dispatchers.IO) {
                            PostgreSQLBlueskyAccountEnrollmentRepository(source).create(incoming, passkey("racing-key"))
                        },
                    ).awaitAll()
                }
            assertEquals(1, results.count { it })
            assertEquals(results[1], PostgreSQLAccountRepository().findById(incoming.id) != null)
            assertEquals(results[1], PostgreSQLPasskeyRepository().getPasskeyByCredentialId("racing-key") != null)
            assertEquals(owner, PostgreSQLAccountRepository().findById(owner.id))
        }

    @Test
    fun staleCancellationPreservesNewReservation() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val first = assertIs<IdentityAdoptionOutcome.Accepted<*>>(repository.begin(binding(owner), now)).value
            val operation = assertIs<app.logdate.server.identity.adoption.IdentityAdoptionOperation>(first)
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                repository.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.CANCELED, now),
            )
            val next = assertIs<IdentityAdoptionOutcome.Accepted<*>>(repository.begin(binding(owner), now)).value
            val replacement = assertIs<app.logdate.server.identity.adoption.IdentityAdoptionOperation>(next)
            assertIs<IdentityAdoptionOutcome.Refused>(
                repository.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.CANCELED, now),
            )
            assertEquals(replacement, repository.get(owner.id, replacement.id))
            assertFalse(PostgreSQLBlueskyAccountEnrollmentRepository(source).create(external("late"), passkey("late-key")))
            assertNull(PostgreSQLPasskeyRepository().getPasskeyByCredentialId("late-key"))
        }

    @Test
    fun onlyOneActiveOperationPerAccount() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val results =
                coroutineScope {
                    listOf("did:web:first.example", "did:web:second.example")
                        .map { target ->
                            async(
                                Dispatchers.IO,
                            ) { PostgreSQLIdentityAdoptionRepository(source).begin(binding(owner).copy(targetDid = target), now) }
                        }.awaitAll()
                }
            assertEquals(1, results.count { it is IdentityAdoptionOutcome.Accepted<*> })
            assertEquals(1, results.count { it == IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.ACTIVE_OPERATION) })
        }

    @Test
    fun receiptSurvivesRestart() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val value =
                assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                    PostgreSQLIdentityAdoptionRepository(source).begin(binding(owner), now),
                ).value
            val operation = assertIs<app.logdate.server.identity.adoption.IdentityAdoptionOperation>(value)
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO identity_adoption_receipts(operation_id, account_id, source_did, target_did, " +
                        "identity_epoch, committed_at) " +
                        "VALUES (?, ?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, operation.id)
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setString(4, TARGET_DID)
                    it.setTimestamp(5, now.timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val receipt = assertNotNull(PostgreSQLIdentityAdoptionRepository(source).receipt(owner.id, operation.id))
            assertEquals(owner.id, receipt.accountId)
            assertEquals(TARGET_DID, receipt.targetDid)
            assertEquals(1L, receipt.identityEpoch)
            assertEquals(now, receipt.committedAt)
            assertNull(PostgreSQLIdentityAdoptionRepository(source).receipt(Uuid.random(), operation.id))
        }

    @Test
    fun publishedOrUnknownHistorySourceIsIneligible() =
        withBlueskyOAuthDatabase { source ->
            for ((name, publicationAttempted) in listOf("published" to true, "unknown" to null)) {
                val owner = hosted(source, name, publicationAttempted)
                assertEquals(
                    IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE),
                    PostgreSQLIdentityAdoptionRepository(source).begin(binding(owner), now),
                )
                assertEquals(owner, PostgreSQLAccountRepository().findById(owner.id))
            }
            val missing = hosted(source, "missing")
            source.blueskyTransaction {
                prepareStatement("DELETE FROM hosted_plc_operations WHERE account_id = ?").use {
                    it.setObject(1, missing.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE),
                PostgreSQLIdentityAdoptionRepository(source).begin(binding(missing), now),
            )
        }

    @Test
    fun profileSaveCannotResetIdentityEpoch() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 2 WHERE id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val saved = PostgreSQLAccountRepository().save(owner.copy(displayName = "Updated"))
            assertEquals(2L, saved.identityEpoch)
            assertEquals(2L, assertNotNull(PostgreSQLAccountRepository().findById(owner.id)).identityEpoch)
        }

    @Test
    fun anotherAccountCannotReadOrCancelAnOperation() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val other = hosted(source, "other")
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val value = assertIs<IdentityAdoptionOutcome.Accepted<*>>(repository.begin(binding(owner), now)).value
            val operation = assertIs<app.logdate.server.identity.adoption.IdentityAdoptionOperation>(value)
            assertNull(repository.get(other.id, operation.id))
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.NOT_FOUND),
                repository.transition(other.id, operation.id, operation.revision, IdentityAdoptionPhase.CANCELED, now),
            )
            assertEquals(operation, repository.get(owner.id, operation.id))
        }

    @Test
    fun retiredDidCannotBeClaimedBySignup() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, identity_epoch, retired_at) " +
                        "VALUES (?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, TARGET_DID)
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, Uuid.random().toString())
                    it.setTimestamp(4, now.timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val account = external("retired")
            assertFalse(PostgreSQLBlueskyAccountEnrollmentRepository(source).create(account, passkey("retired-key")))
            assertNull(PostgreSQLAccountRepository().findById(account.id))
            assertNull(PostgreSQLPasskeyRepository().getPasskeyByCredentialId("retired-key"))
        }

    @Test
    fun aNewAccountCannotChooseAnAdvancedEpoch() =
        withBlueskyOAuthDatabase { source ->
            for (repository in listOf(PostgreSQLAccountRepository(), InMemoryAccountRepository())) {
                val account = external("forged").copy(id = Uuid.random(), identityEpoch = 9)
                assertFailsWith<IllegalArgumentException> { repository.create(account) }
                assertNull(repository.findById(account.id))
            }
        }

    @Test
    fun retirementHistoryCannotBeEditedOrRemoved() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, identity_epoch, retired_at) " +
                        "VALUES (?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, TARGET_DID)
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, Uuid.random().toString())
                    it.setTimestamp(4, now.timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            for (sql in listOf(
                "DELETE FROM retired_identity_dids WHERE source_did = ?",
                "UPDATE retired_identity_dids SET identity_epoch = 2 WHERE source_did = ?",
            )) {
                val failure =
                    assertFailsWith<java.sql.SQLException> {
                        source.blueskyTransaction {
                            prepareStatement(sql).use {
                                it.setString(1, TARGET_DID)
                                it.executeUpdate()
                            }
                        }
                    }
                assertEquals("23514", failure.sqlState)
            }
        }

    @Test
    fun publicationEvidenceSurvivesRestartAndCannotBeDowngraded() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val repository = PostgreSQLHostedPlcOperationRepository()
            val operation = repository.listByAccountId(owner.id).single()
            assertEquals(false, operation.publicationAttempted)
            assertTrue(repository.markPublicationAttempted(operation.id))
            assertEquals(true, PostgreSQLHostedPlcOperationRepository().listByAccountId(owner.id).single().publicationAttempted)
            val failure =
                assertFailsWith<java.sql.SQLException> {
                    source.blueskyTransaction {
                        prepareStatement("UPDATE hosted_plc_operations SET publication_attempted = FALSE WHERE id = ?").use {
                            it.setObject(1, operation.id.toJavaUUID())
                            it.executeUpdate()
                        }
                    }
                }
            assertEquals("23514", failure.sqlState)
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE),
                PostgreSQLIdentityAdoptionRepository(source).begin(binding(owner), now),
            )
        }

    @Test
    fun identicalGenesisRetriesRemainEligible() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, cid, prev_cid, operation_type, " +
                        "operation_json, created_at, publication_attempted) " +
                        "SELECT ?, account_id, did, cid, prev_cid, operation_type, operation_json, created_at, publication_attempted " +
                        "FROM hosted_plc_operations WHERE account_id = ?",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(PostgreSQLIdentityAdoptionRepository(source).begin(binding(owner), now))
        }

    @Test
    fun holdRenewalRequiresTheCurrentRevisionAndDoesNotChangeIdentity() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val prepared = assertNotNull((repository.begin(binding(owner), now) as? IdentityAdoptionOutcome.Accepted)?.value)
            val held =
                assertNotNull(
                    (
                        repository.transition(owner.id, prepared.id, prepared.revision, IdentityAdoptionPhase.FENCED, now)
                            as? IdentityAdoptionOutcome.Accepted
                    )?.value,
                )
            assertEquals(2L, held.revision)
            assertEquals(now + 120.seconds, held.leaseExpiresAt)
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.STALE_REVISION),
                repository.transition(owner.id, prepared.id, prepared.revision, IdentityAdoptionPhase.FENCED, now + 60.seconds),
            )
            val renewed =
                assertNotNull(
                    (
                        repository.transition(
                            owner.id,
                            held.id,
                            held.revision,
                            IdentityAdoptionPhase.FENCED,
                            now + 60.seconds,
                        )
                            as? IdentityAdoptionOutcome.Accepted
                    )?.value,
                )
            assertEquals(3L, renewed.revision)
            assertEquals(now + 180.seconds, renewed.leaseExpiresAt)
            assertEquals(owner, PostgreSQLAccountRepository().findById(owner.id))
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.INVALID_TRANSITION),
                repository.transition(owner.id, renewed.id, renewed.revision, IdentityAdoptionPhase.COMMITTED, now + 61.seconds),
            )
        }

    @Test
    fun anExpiredVerifiedHoldLosesItsVerificationAndOldRevision() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val prepared = assertNotNull((repository.begin(binding(owner), now) as? IdentityAdoptionOutcome.Accepted)?.value)
            source.blueskyTransaction {
                prepareStatement("UPDATE identity_adoption_operations SET phase = 'VERIFIED' WHERE id = ?").use {
                    it.setString(1, prepared.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.LEASE_EXPIRED),
                repository.transition(owner.id, prepared.id, prepared.revision, IdentityAdoptionPhase.FENCED, now + 120.seconds),
            )
            val expired = assertNotNull(repository.get(owner.id, prepared.id))
            assertEquals(IdentityAdoptionPhase.PREPARING, expired.phase)
            assertEquals(2L, expired.revision)
            assertEquals(owner, PostgreSQLAccountRepository().findById(owner.id))
            assertFalse(PostgreSQLBlueskyAccountEnrollmentRepository(source).create(external("expired"), passkey("expired-key")))
        }

    @Test
    fun sourceIdentityChangeBeforeHoldDoesNotPauseTheNewIdentity() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val prepared = assertNotNull((repository.begin(binding(owner), now) as? IdentityAdoptionOutcome.Accepted)?.value)
            source.blueskyTransaction {
                prepareStatement(
                    "UPDATE accounts SET identity_epoch = 1, identity_kind = 'external', " +
                        "did = 'did:web:changed.example' WHERE id = ?",
                ).use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE),
                repository.transition(owner.id, prepared.id, prepared.revision, IdentityAdoptionPhase.FENCED, now),
            )
            assertEquals(prepared, repository.get(owner.id, prepared.id))
            val current = assertNotNull(PostgreSQLAccountRepository().findById(owner.id))
            assertEquals(1L, current.identityEpoch)
            assertEquals("did:web:changed.example", current.did)
        }

    @Test
    fun deletingAndRecreatingAnAccountCannotErasePublicationEvidence() =
        withBlueskyOAuthDatabase { source ->
            val owner = hosted(source)
            val history = PostgreSQLHostedPlcOperationRepository()
            val operation = history.listByAccountId(owner.id).single()
            assertTrue(history.markPublicationAttempted(operation.id))
            assertTrue(PostgreSQLAccountRepository().deleteAccount(owner.id))
            assertTrue(history.listByAccountId(owner.id).isEmpty())
            assertEquals(true, PostgreSQLHostedPlcOperationRepository().hasPublicationAttempt(requireNotNull(owner.did)))
            for (sql in listOf(
                "DELETE FROM identity_publication_attempts WHERE did = ?",
                "UPDATE identity_publication_attempts SET attempted_at = CURRENT_TIMESTAMP WHERE did = ?",
            )) {
                val failure =
                    assertFailsWith<java.sql.SQLException> {
                        source.blueskyTransaction {
                            prepareStatement(sql).use {
                                it.setString(1, owner.did)
                                it.executeUpdate()
                            }
                        }
                    }
                assertEquals("23514", failure.sqlState)
            }
            val replacement = hosted(source, "replacement", did = requireNotNull(owner.did))
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE),
                PostgreSQLIdentityAdoptionRepository(source).begin(binding(replacement), now),
            )
        }

    private suspend fun hosted(
        source: DataSource,
        name: String = "owner",
        publicationAttempted: Boolean? = false,
        did: String = "did:plc:${name.padEnd(24, 'a')}",
    ): Account {
        val account = Account(Uuid.random(), name, name, did = did, createdAt = now)
        assertTrue(PostgreSQLAccountRepository().create(account))
        source.blueskyTransaction {
            prepareStatement(
                "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                    "created_at, publication_attempted) " +
                    "VALUES (?, ?, ?, 'plc_operation', '{}', ?, ?)",
            ).use {
                it.setObject(1, Uuid.random().toJavaUUID())
                it.setObject(2, account.id.toJavaUUID())
                it.setString(3, account.did)
                it.setTimestamp(4, now.timestamp())
                it.setObject(5, publicationAttempted)
                assertEquals(1, it.executeUpdate())
            }
        }
        return assertNotNull(PostgreSQLAccountRepository().findById(account.id))
    }

    private fun binding(account: Account) = IdentityAdoptionBinding(account.id, requireNotNull(account.did), TARGET_DID, "device-a", 0)

    private fun external(name: String) =
        Account(
            Uuid.random(),
            name,
            name,
            did = TARGET_DID,
            createdAt = now,
            identityKind = AccountIdentityKind.EXTERNAL,
            storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
        )

    private fun passkey(credential: String) =
        WebAuthnPasskeyService.VerifiedRegistration(
            credential,
            byteArrayOf(1, 2, 3),
            0,
            PasskeyInfo(Uuid.random(), credential, "Test device", "platform", now, null),
        )

    private companion object {
        val now = Instant.parse("2026-10-29T00:00:00Z")
        const val TARGET_DID = "did:web:external.example"
    }
}
