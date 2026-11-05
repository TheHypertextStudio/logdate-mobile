package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.database.PostgreSqlRepositoryTestSupport.identityService
import app.logdate.server.database.PostgreSqlRepositoryTestSupport.insertRepository
import app.logdate.server.database.PostgreSqlRepositoryTestSupport.sampleAccount
import app.logdate.server.database.PostgreSqlRepositoryTestSupport.withAccountTables
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.shared.model.AccountStorageMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSqlRepositoriesTest {
    @Test
    fun `account repository supports full lifecycle`() {
        withAccountTables {
            val repository = PostgreSQLAccountRepository()
            val account =
                Account(
                    id = Uuid.random(),
                    username = "alice",
                    displayName = "Alice",
                    did = "did:web:alice.logdate.app",
                    handle = "alice.logdate.app",
                    signingKeyPublic = "zAliceKey",
                    email = "alice@example.com",
                    emailVerified = true,
                    bio = "bio",
                    createdAt = Clock.System.now(),
                    preferences = "{}",
                )

            repository.save(account)
            assertNotNull(repository.findById(account.id))
            assertNotNull(repository.findByDid("did:web:alice.logdate.app"))
            assertNotNull(repository.findByHandle("alice.logdate.app"))
            assertNotNull(repository.findByUsername("alice"))
            assertNotNull(repository.findByEmail("alice@example.com"))
            assertTrue(repository.usernameExists("alice"))
            assertTrue(repository.emailExists("alice@example.com"))
            assertEquals(1, repository.findByVerifiedEmail("alice@example.com").size)

            assertTrue(repository.updateLastSignIn(account.id))
            assertFalse(repository.updateLastSignIn(Uuid.random()))

            val renamed = account.copy(username = "alice2")
            repository.save(renamed)
            assertNull(repository.findByUsername("alice"))
            assertNotNull(repository.findByUsername("alice2"))

            val allActive = repository.getAllAccounts()
            assertEquals(1, allActive.size)
            assertTrue(repository.getAccountsCreatedAfter(account.createdAt.minus(1.seconds)).isNotEmpty())

            assertTrue(repository.deactivateAccount(account.id))
            assertFalse(repository.deactivateAccount(Uuid.random()))
            assertTrue(repository.getAllAccounts().isEmpty())

            assertTrue(repository.deleteAccount(account.id))
            assertFalse(repository.deleteAccount(account.id))
        }
    }

    @Test
    fun `account repository lists active accounts missing any part of their identity`() {
        withAccountTables {
            val repository = PostgreSQLAccountRepository()
            val now = Clock.System.now()

            fun account(
                username: String,
                handle: String? = null,
                did: String? = null,
                key: String? = null,
            ) = Account(
                id = Uuid.random(),
                username = username,
                displayName = username,
                handle = handle,
                did = did,
                signingKeyPublic = key,
                createdAt = now,
            )
            val complete = account("done", "done.logdate.app", "did:plc:doneaaaaaaaaaaaaaaaaaaaa", "zKey")
            val legacy = account("legacy")
            val handleOnly = account("partial", handle = "partial.logdate.app")
            val inactiveLegacy = account("gone")
            listOf(complete, legacy, handleOnly, inactiveLegacy).forEach { repository.save(it) }
            repository.deactivateAccount(inactiveLegacy.id)

            assertEquals(
                setOf(legacy.id, handleOnly.id),
                repository.findAccountsMissingIdentity().map { it.id }.toSet(),
            )
        }
    }

    @Test
    fun `deleting an account removes its journal repository and sync index`() {
        withAccountTables {
            val repository = PostgreSQLAccountRepository()
            val owner = sampleAccount(Uuid.random(), username = "leaving").copy(did = "did:plc:leaving")
            val other = sampleAccount(Uuid.random(), username = "staying").copy(did = "did:plc:staying")
            repository.save(owner)
            repository.save(other)
            // The moment store falls back to a synthetic repository DID that only the sync state records.
            insertRepository(owner.id, accountDid = "did:plc:leaving", syncRepoDid = "did:web:sync-leaving.logdate.internal")
            insertRepository(other.id, accountDid = "did:plc:staying", syncRepoDid = "did:plc:staying")

            assertTrue(repository.deleteAccount(owner.id))

            transaction {
                val remainingRepos =
                    AtprotoRepoHeadsTable.selectAll().map { it[AtprotoRepoHeadsTable.repoDid] }.toSet() +
                        AtprotoRepoCommitsTable.selectAll().map { it[AtprotoRepoCommitsTable.repoDid] } +
                        AtprotoRepoBlockLinksTable.selectAll().map { it[AtprotoRepoBlockLinksTable.repoDid] }
                assertEquals(setOf("did:plc:staying"), remainingRepos)
                assertEquals(
                    setOf("shared", "only-did:plc:staying"),
                    AtprotoRepoBlocksTable.selectAll().map { it[AtprotoRepoBlocksTable.cid] }.toSet(),
                )
                assertEquals(
                    setOf(other.id.toJavaUuid()),
                    LogDateCollectionStatesTable.selectAll().map { it[LogDateCollectionStatesTable.userId] }.toSet() +
                        LogDateCollectionRecordsTable.selectAll().map { it[LogDateCollectionRecordsTable.userId] },
                )
            }
        }
    }

    @Test
    fun `both account stores set the storage mode only when an account is created or moved`() {
        withAccountTables {
            listOf(PostgreSQLAccountRepository(), InMemoryAccountRepository()).forEach { repository ->
                val created = sampleAccount(Uuid.random(), username = "space-owner")
                val existing = sampleAccount(Uuid.random(), username = "legacy-owner")
                val inserted = sampleAccount(Uuid.random(), username = "inserted-owner")
                assertEquals(AccountStorageMode.LEGACY, existing.storageMode, "an account starts in legacy unless told otherwise")

                assertTrue(repository.createOnly(created.copy(storageMode = AccountStorageMode.PERSONAL_SPACE_V1)))
                repository.save(existing)
                assertTrue(repository.create(inserted.copy(storageMode = AccountStorageMode.PERSONAL_SPACE_V1)))
                assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, repository.findById(created.id)?.storageMode)
                assertEquals(AccountStorageMode.LEGACY, repository.findById(existing.id)?.storageMode)
                assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, repository.findById(inserted.id)?.storageMode)

                val read = requireNotNull(repository.findById(existing.id))
                repository.save(read.copy(displayName = "Renamed", storageMode = AccountStorageMode.PERSONAL_SPACE_V1))
                assertEquals(AccountStorageMode.LEGACY, repository.findById(existing.id)?.storageMode, "a save never moves an account")
                assertEquals("Renamed", repository.findById(existing.id)?.displayName)

                assertFalse(
                    repository.updateStorageModeIfUnchanged(existing.id, AccountStorageMode.PERSONAL_SPACE_V1, AccountStorageMode.LEGACY),
                )
                assertTrue(
                    repository.updateStorageModeIfUnchanged(existing.id, AccountStorageMode.LEGACY, AccountStorageMode.PERSONAL_SPACE_V1),
                )
                assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, repository.findById(existing.id)?.storageMode)
                assertFalse(
                    repository.updateStorageModeIfUnchanged(Uuid.random(), AccountStorageMode.LEGACY, AccountStorageMode.PERSONAL_SPACE_V1),
                )

                repository.save(read.copy(displayName = "Saved from a stale copy"))
                assertEquals(
                    AccountStorageMode.PERSONAL_SPACE_V1,
                    repository.findById(existing.id)?.storageMode,
                    "a save of a copy read before the move doesn't undo it",
                )
                listOf(created.id, existing.id, inserted.id).forEach { repository.deleteAccount(it) }
            }
        }
    }

    @Test
    fun `a storage mode a newer server wrote survives sign-ins, saves and identity setup`() {
        withAccountTables {
            val repository = PostgreSQLAccountRepository()
            val account = sampleAccount(Uuid.random(), username = "future-owner")
            repository.save(account)
            transaction {
                AccountsTable.update({ AccountsTable.id eq account.id.toJavaUuid() }) { it[storageMode] = "personalSpaceV9" }
            }

            val read = requireNotNull(repository.findById(account.id))
            assertEquals(AccountStorageMode.UNRECOGNIZED, read.storageMode)
            repository.updateLastSignIn(account.id)
            repository.save(read.copy(displayName = "Profile save"))
            assertFailsWith<IdentityLifecycleConflictException> { identityService(repository).ensureIdentity(read) }
            assertFalse(repository.updateStorageModeIfUnchanged(account.id, AccountStorageMode.UNRECOGNIZED, AccountStorageMode.LEGACY))
            assertFalse(
                repository.updateStorageModeIfUnchanged(account.id, AccountStorageMode.LEGACY, AccountStorageMode.PERSONAL_SPACE_V1),
            )

            val stored = transaction { AccountsTable.selectAll().where { AccountsTable.id eq account.id.toJavaUuid() }.single() }
            assertEquals("personalSpaceV9", stored[AccountsTable.storageMode])
        }
    }

    @Test
    fun `account repository create only leaves a colliding account unchanged`() {
        withAccountTables {
            val repository = PostgreSQLAccountRepository()
            val ownerId = Uuid.random()
            val existing = sampleAccount(ownerId, username = "existing-owner")
            val attemptedReplacement = sampleAccount(ownerId, username = "attacker-owner")

            repository.save(existing)

            assertFalse(repository.createOnly(attemptedReplacement))
            // SQL timestamp columns have lower precision than kotlin.time.Instant;
            // compare the round-tripped account after adopting the persisted
            // timestamp so this test focuses on collision safety, not storage
            // precision.
            val persisted = requireNotNull(repository.findById(ownerId))
            assertEquals(existing.copy(createdAt = persisted.createdAt), persisted)
            assertNull(repository.findByUsername("attacker-owner"))
        }
    }
}
