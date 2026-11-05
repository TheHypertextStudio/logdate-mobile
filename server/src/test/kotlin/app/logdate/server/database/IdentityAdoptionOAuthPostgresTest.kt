@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.bluesky.BlueskySignInFailure
import app.logdate.server.bluesky.BlueskySignInFailureKind
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionProposals
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import studio.hypertext.atproto.identity.AtprotoDid
import java.security.MessageDigest
import java.util.Base64
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityAdoptionOAuthPostgresTest {
    @Test
    fun browserReturnCreatesOnlyItsOwnIdempotentProposal() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val service = IdentityAdoptionProposals(store, repository, clock)
            val first = assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(service.exchange(binding, id, verifier)).value
            assertEquals(binding.proposalId.toString(), first.id)
            assertEquals(target.toString(), first.binding.targetDid)
            assertEquals(IdentityAdoptionPhase.PREPARING, first.phase)
            assertEquals(IdentityAdoptionOutcome.Accepted(first), service.exchange(binding, id, verifier))
            assertEquals(binding.sourceDid, PostgreSQLAccountRepository().findById(binding.accountId)?.did)
            assertEquals(0, count("identity_adoption_decisions"))
            assertEquals(1, count("identity_adoption_operations"))
        }

    @Test
    fun aDelayedBrowserReturnCannotReviveTheCanceledProposal() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            val repository = PostgreSQLIdentityAdoptionRepository(source)
            val service = IdentityAdoptionProposals(store, repository, clock)
            val operation =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    service.exchange(binding, id, verifier),
                ).value
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                repository.transition(binding.accountId, operation.id, operation.revision, IdentityAdoptionPhase.CANCELED, clock.now()),
            )
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.INVALID_TRANSITION),
                service.exchange(binding, id, verifier),
            )
            assertEquals(0, count("identity_adoption_reservations"))
        }

    @Test
    fun wrongAppProofCannotAllocateAnAdoptionOperation() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            val service = IdentityAdoptionProposals(store, PostgreSQLIdentityAdoptionRepository(source), clock)
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID),
                service.exchange(binding, id, "w".repeat(64)),
            )
            assertEquals(0, count("identity_adoption_operations"))
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(service.exchange(binding, id, verifier))
        }

    @Test
    fun proofExpiringAfterExchangeCannotAllocateAnOperation() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            val delayed =
                object : app.logdate.server.bluesky.BlueskySignInAttemptStore by store {
                    override suspend fun exchangeAdoption(
                        id: String,
                        verifier: String,
                        binding: IdentityAdoptionOAuthBinding,
                    ) = store.exchangeAdoption(id, verifier, binding).also { clock.time += 6.minutes }
                }
            val service = IdentityAdoptionProposals(delayed, PostgreSQLIdentityAdoptionRepository(source), clock)
            assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_EXPIRED), service.exchange(binding, id, verifier))
            assertEquals(0, count("identity_adoption_operations"))
        }

    @Test
    fun sourcePublicationAfterExchangeStillRefusesTheProposal() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            val delayed =
                object : app.logdate.server.bluesky.BlueskySignInAttemptStore by store {
                    override suspend fun exchangeAdoption(
                        id: String,
                        verifier: String,
                        binding: IdentityAdoptionOAuthBinding,
                    ) = store.exchangeAdoption(id, verifier, binding).also { publishHistory() }
                }
            val service = IdentityAdoptionProposals(delayed, PostgreSQLIdentityAdoptionRepository(source), clock)
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE),
                service.exchange(binding, id, verifier),
            )
            assertEquals(0, count("identity_adoption_operations"))
        }

    @Test
    fun verifiedAdoptionCannotEnterSignInOrReconnectAndRecoversALostExchange() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            assertNull(store.consume(id, verifier))
            assertNull(store.consumeReconnect(id, verifier, binding.accountId, target))
            assertNull(store.exchangeAdoption(id, "w".repeat(64), binding))
            val verified = assertNotNull(store.exchangeAdoption(id, verifier, binding))
            assertEquals(target, verified.did)
            assertEquals(binding, verified.binding)
            assertEquals(verified, another().exchangeAdoption(id, verifier, binding))
            assertEquals(0, count("identity_adoption_operations"))
            assertEquals(0, count("identity_adoption_reservations"))
        }

    @Test
    fun adoptionCannotConsumeAnOrdinarySignInAttempt() =
        fixture {
            store.create(id, challenge(verifier), clock.now() + 5.minutes)
            assertTrue(store.complete(id, target))
            assertNull(store.exchangeAdoption(id, verifier, binding))
            assertEquals(target, assertNotNull(store.consume(id, verifier)).did)
        }

    @Test
    fun adoptionCannotConsumeAReconnectAttempt() =
        fixture {
            val foreign =
                Account(
                    Uuid.random(),
                    "external_owner",
                    "External test",
                    did = target.toString(),
                    createdAt = clock.now(),
                    identityKind = AccountIdentityKind.EXTERNAL,
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                )
            assertTrue(PostgreSQLAccountRepository().create(foreign))
            store.createReconnect(id, challenge(verifier), clock.now() + 5.minutes, foreign.id, target)
            assertTrue(store.complete(id, target))
            assertNull(store.exchangeAdoption(id, verifier, binding))
            assertEquals(target, assertNotNull(store.consumeReconnect(id, verifier, foreign.id, target)).did)
        }

    @Test
    fun everySourceAndProposalBindingMustMatchWithoutConsumingTheRealReturn() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            for (other in listOf(
                binding.copy(accountId = Uuid.random()),
                binding.copy(sourceDid = "did:web:other.example"),
                binding.copy(sourceEpoch = 1),
                binding.copy(deviceId = "other-device"),
                binding.copy(proposalId = Uuid.random()),
            )) {
                assertNull(store.exchangeAdoption(id, verifier, other))
            }
            assertNotNull(store.exchangeAdoption(id, verifier, binding))
        }

    @Test
    fun changedEpochBeforeCallbackCannotBeConfirmed() =
        fixture {
            start()
            changeEpoch()
            assertFalse(store.complete(id, target))
            assertNull(store.exchangeAdoption(id, verifier, binding))
        }

    @Test
    fun changedEpochAfterCallbackCannotBeExchangedForTheNewAuthority() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            changeEpoch()
            assertNull(store.exchangeAdoption(id, verifier, binding))
            assertNull(store.consume(id, verifier))
        }

    @Test
    fun deletingTheOriginalAccountPreventsCallbackAndExchange() =
        fixture {
            start()
            assertTrue(PostgreSQLAccountRepository().deleteAccount(binding.accountId))
            assertFalse(store.complete(id, target))
            assertNull(store.exchangeAdoption(id, verifier, binding))
        }

    @Test
    fun publicationHistoryRefusesNewAdoptionBeforeAnAttemptIsWritten() =
        fixture {
            publishHistory()
            assertFailsWith<IdentityWriteRefusedException> { start() }
            assertEquals(0, count("bluesky_sign_in_attempts"))
        }

    @Test
    fun publicationBetweenCallbackAndExchangeRefusesTheStoredIdentity() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            publishHistory()
            assertNull(store.exchangeAdoption(id, verifier, binding))
        }

    @Test
    fun privateSourceItselfIsNotAnExternalAdoptionTarget() =
        fixture {
            start()
            assertFalse(store.complete(id, AtprotoDid.require(binding.sourceDid)))
            assertTrue(store.complete(id, target))
            assertEquals(target, assertNotNull(store.exchangeAdoption(id, verifier, binding)).did)
        }

    @Test
    fun encryptedExpiryCannotBeExtendedByDatabaseMetadataOrRetries() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            assertNotNull(store.exchangeAdoption(id, verifier, binding))
            source.blueskyTransaction {
                createStatement().use { it.executeUpdate("UPDATE bluesky_sign_in_attempts SET expires_at = expires_at + INTERVAL '1 day'") }
            }
            clock.time += 6.minutes
            assertNull(store.exchangeAdoption(id, verifier, binding))
        }

    @Test
    fun denialIsBoundToItsProposalAndSpentOnce() =
        fixture {
            start()
            assertTrue(store.fail(id, BlueskySignInFailureKind.DECLINED))
            assertNull(store.exchangeAdoption(id, verifier, binding.copy(proposalId = Uuid.random())))
            assertEquals(
                BlueskySignInFailureKind.DECLINED,
                assertFailsWith<BlueskySignInFailure> { store.exchangeAdoption(id, verifier, binding) }.kind,
            )
            assertNull(store.exchangeAdoption(id, verifier, binding))
        }

    @Test
    fun privateSourceAndProposalAreEncryptedAndRedacted() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            val verified = assertNotNull(store.exchangeAdoption(id, verifier, binding))
            source.blueskyTransaction {
                createStatement().use { statement ->
                    statement.executeQuery("SELECT encrypted_payload FROM bluesky_sign_in_attempts").use {
                        assertTrue(it.next())
                        val encrypted = it.getString(1)
                        for (secret in listOf(
                            binding.sourceDid,
                            binding.accountId.toString(),
                            binding.deviceId,
                            binding.proposalId.toString(),
                            verifier,
                            challenge(verifier),
                        )) {
                            assertFalse(encrypted.contains(secret))
                            assertFalse(verified.toString().contains(secret))
                            assertFalse(binding.toString().contains(secret))
                        }
                    }
                }
            }
        }

    @Test
    fun aVerifiedProofKeepsTheOriginalDeadlineAcrossRetry() =
        fixture {
            start()
            assertTrue(store.complete(id, target))
            val first = assertNotNull(store.exchangeAdoption(id, verifier, binding))
            assertEquals(clock.now() + 5.minutes, first.expiresAt)
            clock.time += 2.minutes
            assertEquals(first, another().exchangeAdoption(id, verifier, binding))
            clock.time += 4.minutes
            assertNull(store.exchangeAdoption(id, verifier, binding))
        }

    private fun fixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val owner = createTestAccount(Uuid.random().toJavaUUID(), "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, clock.now().timestamp())
                    check(it.executeUpdate() == 1)
                }
            }
            val binding = IdentityAdoptionOAuthBinding(owner.id, requireNotNull(owner.did), 0, "private-device", Uuid.random())
            Fixture(source, clock, binding).block()
        }

    private class Fixture(
        val source: DataSource,
        val clock: TestClock,
        val binding: IdentityAdoptionOAuthBinding,
    ) {
        val store = another()

        fun another() = PostgreSQLBlueskySignInAttemptStore(source, AtRestKeyCipher("synthetic-adoption-secret"), clock)

        suspend fun start() = store.createAdoption(id, challenge(verifier), clock.now() + 5.minutes, binding)

        suspend fun changeEpoch() =
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = identity_epoch + 1 WHERE id = ?").use {
                    it.setObject(1, binding.accountId.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }

        suspend fun publishHistory() {
            val history = PostgreSQLHostedPlcOperationRepository()
            assertTrue(history.markPublicationAttempted(history.listByAccountId(binding.accountId).single().id))
        }

        suspend fun count(table: String): Int =
            source.blueskyTransaction {
                createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM $table").use {
                        check(it.next())
                        it.getInt(1)
                    }
                }
            }
    }

    private class TestClock(
        var time: Instant = Instant.parse("2026-10-30T00:00:00Z"),
    ) : Clock {
        override fun now(): Instant = time
    }

    companion object {
        private val id = "a".repeat(43)
        private val verifier = "app-proof-verifier-that-is-long-enough-for-pkce-123456789"
        private val target = AtprotoDid.require("did:web:external.example")

        private fun challenge(value: String) =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))
    }
}
