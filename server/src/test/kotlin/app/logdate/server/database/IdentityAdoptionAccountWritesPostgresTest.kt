package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentityClaimConflictException
import app.logdate.server.auth.AccountModeration
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.PasskeyInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.sql.SQLException
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionAccountWritesPostgresTest {
    @Test
    fun deletedAccountIdExcludesExternalEnrollmentAndPasskeyInsertion() =
        withFixture {
            assertTrue(accounts.deleteAccount(owner.id))
            val candidate =
                owner.copy(
                    did = TARGET_DID,
                    handle = null,
                    signingKeyPublic = null,
                    identityKind = AccountIdentityKind.EXTERNAL,
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                )
            val credential = "synthetic-new-credential"
            val passkey =
                WebAuthnPasskeyService.VerifiedRegistration(
                    credential,
                    byteArrayOf(1, 2, 3),
                    0,
                    PasskeyInfo(Uuid.random(), credential, "Test device", "platform", Clock.System.now(), null),
                )
            assertFalse(PostgreSQLBlueskyAccountEnrollmentRepository(source).create(candidate, passkey))
            assertNull(accounts.findById(owner.id))
            assertNull(PostgreSQLPasskeyRepository().getPasskeyByCredentialId(credential))
        }

    @Test
    fun inMemoryDeletedIdCannotBeRecreatedOrUpserted() =
        runBlocking {
            val repository = InMemoryAccountRepository()
            val id = Uuid.random()
            val original = Account(id, id.toHexString(), "Test owner", createdAt = Clock.System.now())
            assertTrue(repository.create(original))
            assertTrue(repository.deleteAccount(id))
            assertFalse(repository.create(original))
            assertFalse(repository.createOnly(original))
            assertFailsWith<AccountIdentityClaimConflictException> { repository.save(original) }
            assertNull(repository.findById(id))
        }

    @Test fun deletedAccountIdExcludesCreate() = deletedId(Factory.CREATE)

    @Test fun deletedAccountIdExcludesCreateOnly() = deletedId(Factory.CREATE_ONLY)

    @Test fun deletedAccountIdExcludesSaveInsertion() = deletedId(Factory.SAVE)

    @Test
    fun staleProfileSaveCannotRecreateDeletedAccount() =
        withFixture {
            assertTrue(accounts.deleteAccount(owner.id))
            assertFailsWith<AccountIdentityClaimConflictException> { accounts.save(owner.copy(displayName = "Stale profile")) }
            assertNull(accounts.findById(owner.id))
        }

    @Test
    fun inMemoryInactiveAuthorityCannotChangeOrBeReactivatedByProfileSave() =
        runBlocking {
            val repository = InMemoryAccountRepository()
            val id = Uuid.random()
            val original =
                Account(
                    id,
                    id.toHexString(),
                    "Test owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    signingKeyPublic = "zOriginal",
                    createdAt = Clock.System.now(),
                )
            assertTrue(repository.create(original))
            assertTrue(repository.deactivateAccount(id))
            assertNull(repository.updateIdentityIfUnchanged(original, original.copy(signingKeyPublic = "zNext")))
            val saved = repository.save(original.copy(displayName = "Profile update"))
            assertFalse(saved.isActive)
            assertEquals("zOriginal", saved.signingKeyPublic)
        }

    @Test fun reservedTargetExcludesCreate() = reservation(Factory.CREATE)

    @Test fun reservedTargetExcludesCreateOnly() = reservation(Factory.CREATE_ONLY)

    @Test fun reservedTargetExcludesSaveInsertion() = reservation(Factory.SAVE)

    @Test fun retirementExcludesCreateAfterDeletion() = retirement(Factory.CREATE)

    @Test fun retirementExcludesCreateOnlyAfterDeletion() = retirement(Factory.CREATE_ONLY)

    @Test fun retirementExcludesSaveInsertionAfterDeletion() = retirement(Factory.SAVE)

    @Test
    fun genericCreationAndReservationHaveExactlyOneWinner() =
        withFixture {
            val candidate = candidate(TARGET_DID)
            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) { accounts.create(candidate) },
                        async(Dispatchers.IO) { adoption.begin(binding(), Clock.System.now()) is IdentityAdoptionOutcome.Accepted<*> },
                    ).awaitAll()
                }
            assertEquals(1, results.count { it })
            assertEquals(results[0], accounts.findById(candidate.id) != null)
            assertEquals(if (results[1]) 1L else 0L, reservations())
            assertEquals(owner, accounts.findById(owner.id))
        }

    @Test
    fun reservedTargetCannotBeAssignedByHostedCas() =
        withFixture {
            begin()
            val waiting = candidate(null)
            assertTrue(accounts.create(waiting))
            val stored = assertNotNull(accounts.findById(waiting.id))
            assertNull(accounts.updateIdentityIfUnchanged(stored, stored.copy(did = TARGET_DID)))
            assertEquals(stored, accounts.findById(waiting.id))
            assertEquals(1L, reservations())
        }

    @Test
    fun heldHostedCasLeavesIdentityUnchanged() =
        withFixture {
            hold(begin())
            assertNull(accounts.updateIdentityIfUnchanged(owner, owner.copy(signingKeyPublic = "zNext")))
            assertEquals(owner, accounts.findById(owner.id))
        }

    @Test
    fun oldEpochCannotChangeHostedIdentityEvenWhenOtherFieldsMatch() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertNull(accounts.updateIdentityIfUnchanged(owner, owner.copy(signingKeyPublic = "zNext")))
            val stored = assertNotNull(accounts.findById(owner.id))
            assertEquals(1L, stored.identityEpoch)
            assertEquals(owner.signingKeyPublic, stored.signingKeyPublic)
        }

    @Test
    fun deactivatedHostedAuthorityCannotBeChangedOrReactivatedByProfileSave() =
        withFixture {
            assertTrue(accounts.deactivateAccount(owner.id))
            assertNull(accounts.updateIdentityIfUnchanged(owner, owner.copy(signingKeyPublic = "zNext")))
            val profile = accounts.save(owner.copy(displayName = "Profile update"))
            assertFalse(profile.isActive)
            assertEquals(owner.signingKeyPublic, profile.signingKeyPublic)
        }

    @Test
    fun moderatedHostedAuthorityCannotBeChanged() =
        withFixture {
            assertTrue(accounts.setModeration(owner.id, AccountModeration.SUSPENDED))
            assertNull(accounts.updateIdentityIfUnchanged(owner, owner.copy(signingKeyPublic = "zNext")))
            assertEquals(AccountModeration.SUSPENDED, accounts.findById(owner.id)?.moderation)
            assertEquals(owner.signingKeyPublic, accounts.findById(owner.id)?.signingKeyPublic)
        }

    @Test
    fun genericSaveCannotChangeHostedAuthorityDuringVerification() =
        withFixture {
            hold(begin())
            assertEquals(owner, accounts.save(owner.copy(signingKeyPublic = "zNext")))
            assertEquals(owner, accounts.findById(owner.id))
        }

    @Test
    fun genericSaveCannotAssignAnotherAccountsReservedTarget() =
        withFixture {
            begin()
            val waiting = candidate(null)
            assertTrue(accounts.create(waiting))
            val stored = assertNotNull(accounts.findById(waiting.id))
            assertEquals(stored, accounts.save(stored.copy(did = TARGET_DID)))
            assertEquals(stored, accounts.findById(waiting.id))
        }

    @Test
    fun hostedCasRejectsAnUpdatedObjectFromAnotherAccount() =
        withFixture {
            assertNull(accounts.updateIdentityIfUnchanged(owner, owner.copy(id = Uuid.random(), signingKeyPublic = "zNext")))
            assertEquals(owner, accounts.findById(owner.id))
        }

    @Test
    fun deletionOwnsTheAccountBeforeWaitingOnDependentRows() =
        withFixture {
            coroutineScope {
                source.connection.use { control ->
                    control.autoCommit = false
                    control.prepareStatement("SELECT id FROM hosted_plc_operations WHERE account_id = ? FOR UPDATE").use {
                        it.setObject(1, owner.id.toJavaUUID())
                        it.executeQuery().close()
                    }
                    val deleting = async(Dispatchers.IO) { accounts.deleteAccount(owner.id) }
                    try {
                        withTimeout(5_000) {
                            while (waitingOnHostedHistory() == 0L) delay(25)
                        }
                        val failure =
                            assertFailsWith<SQLException> {
                                source.blueskyTransaction {
                                    prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT").use {
                                        it.setObject(1, owner.id.toJavaUUID())
                                        it.executeQuery().close()
                                    }
                                }
                            }
                        assertEquals("55P03", failure.sqlState)
                    } finally {
                        control.rollback()
                        withContext(NonCancellable) { assertTrue(withTimeout(5_000) { deleting.await() }) }
                    }
                }
            }
            assertNull(accounts.findById(owner.id))
            assertEquals(0L, reservations())
        }

    private enum class Factory { CREATE, CREATE_ONLY, SAVE }

    private fun deletedId(factory: Factory) =
        withFixture {
            assertTrue(accounts.deleteAccount(owner.id))
            val incoming = owner.copy(username = "recreated", did = null, handle = null, signingKeyPublic = null)
            rejected(factory, incoming)
        }

    private fun reservation(factory: Factory) =
        withFixture {
            begin()
            rejected(factory, candidate(TARGET_DID))
            assertEquals(1L, reservations())
            assertEquals(owner, accounts.findById(owner.id))
        }

    private fun retirement(factory: Factory) =
        withFixture {
            retire()
            assertTrue(accounts.deleteAccount(owner.id))
            rejected(factory, candidate(requireNotNull(owner.did)))
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val accounts = PostgreSQLAccountRepository()
        val adoption = PostgreSQLIdentityAdoptionRepository(source)

        fun candidate(did: String?): Account {
            val id = Uuid.random()
            return Account(id, id.toHexString(), "Test candidate", did = did, createdAt = Clock.System.now())
        }

        fun binding() = IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), TARGET_DID, "device", 0)

        suspend fun begin(): IdentityAdoptionOperation =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(adoption.begin(binding(), Clock.System.now())).value

        suspend fun hold(operation: IdentityAdoptionOperation) {
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                adoption.transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
        }

        suspend fun rejected(
            factory: Factory,
            candidate: Account,
        ) {
            when (factory) {
                Factory.CREATE -> assertFalse(accounts.create(candidate))
                Factory.CREATE_ONLY -> assertFalse(accounts.createOnly(candidate))
                Factory.SAVE -> assertFailsWith<AccountIdentityClaimConflictException> { accounts.save(candidate) }
            }
            assertNull(accounts.findById(candidate.id))
        }

        suspend fun reservations(): Long =
            source.blueskyTransaction {
                prepareStatement("SELECT COUNT(*) FROM identity_adoption_reservations WHERE account_id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }

        suspend fun retire() =
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, identity_epoch, retired_at) " +
                        "VALUES (?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, owner.did)
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, Uuid.random().toString())
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }

        suspend fun waitingOnHostedHistory(): Long =
            source.blueskyTransaction {
                prepareStatement(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() " +
                        "AND wait_event_type = 'Lock' AND query LIKE '%hosted_plc_operations%'",
                ).use {
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "Test owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    handle = "owner.logdate.test",
                    signingKeyPublic = "zOriginal",
                    createdAt = Clock.System.now(),
                )
            val accounts = PostgreSQLAccountRepository()
            assertTrue(accounts.create(owner))
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, " +
                        "operation_json, created_at, publication_attempted) " +
                        "VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            Fixture(source, assertNotNull(accounts.findById(id))).block()
        }

    private companion object {
        const val TARGET_DID = "did:web:reserved.test"
    }
}
