package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.AccountLinkEvent
import app.logdate.server.auth.DeviceInfo
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.SessionType
import app.logdate.server.auth.TemporarySession
import app.logdate.server.database.support.withH2Database
import app.logdate.server.entitlements.AccountEntitlementsTable
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.EncryptedPrivateKey
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.identity.StoredSigningKey
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.PasskeyInfo
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Comprehensive integration tests for core PostgreSQL-backed identity and session repositories.
 *
 * This suite provides broad coverage for the primary authentication and account management
 * components, including:
 * - [PostgreSQLAccountRepository]: Full account lifecycle (creation, lookup, deactivation, deletion).
 * - [PostgreSQLAccountIdentityRepository]: Management of external identity providers (e.g., Google)
 *   and their linkage to internal accounts.
 * - [PostgreSQLSigningKeyRepository]: Storage and revocation of account-level signing keys.
 * - [PostgreSQLPasskeyRepository]: Robust WebAuthn credential management, including signature
 *   counts and device metadata.
 * - [PostgreSQLSessionManager]: Lifecycle of temporary sessions for account creation and
 *   authentication flows, including automatic expiration and cleanup.
 */
@OptIn(ExperimentalUuidApi::class)
class PostgreSqlRepositoriesTest {
    @Test
    fun `passkey repository store catches transaction failures when no database is initialized`() {
        val repository = PostgreSQLPasskeyRepository()
        val info = samplePasskeyInfo("cred-no-db")

        val stored =
            runBlocking {
                repository.storePasskey(
                    userId = Uuid.random(),
                    credentialId = "cred-no-db",
                    publicKey = byteArrayOf(1),
                    signCount = 0L,
                    info = info,
                )
            }

        assertFalse(stored)
    }

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
            identityService(repository).ensureIdentity(read)
            assertFalse(repository.updateStorageModeIfUnchanged(account.id, AccountStorageMode.UNRECOGNIZED, AccountStorageMode.LEGACY))
            assertFalse(
                repository.updateStorageModeIfUnchanged(account.id, AccountStorageMode.LEGACY, AccountStorageMode.PERSONAL_SPACE_V1),
            )

            val stored = transaction { AccountsTable.selectAll().where { AccountsTable.id eq account.id.toJavaUuid() }.single() }
            assertEquals("personalSpaceV9", stored[AccountsTable.storageMode])
        }
    }

    private fun identityService(repository: PostgreSQLAccountRepository) =
        AtprotoIdentityService(
            accountRepository = repository,
            signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "storage-mode-test-kek"),
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )

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

    @Test
    fun `account identity repository supports upsert and query operations`() {
        withAccountIdentityTables {
            val repository = PostgreSQLAccountIdentityRepository()
            val now = Clock.System.now()
            val accountId = Uuid.random()
            val linkedAccountId = Uuid.random()
            PostgreSQLAccountRepository().save(sampleAccount(accountId, username = "identity-owner"))
            PostgreSQLAccountRepository().save(sampleAccount(linkedAccountId, username = "identity-owner-linked"))

            val identity =
                AccountIdentity(
                    id = Uuid.random(),
                    accountId = accountId,
                    provider = IdentityProvider.GOOGLE,
                    providerSubject = "google-subject",
                    email = "identity@example.com",
                    emailVerified = true,
                    createdAt = now,
                    metadataJson = "{}",
                )

            val saved = repository.save(identity)
            assertEquals(identity.id, saved.id)

            val updated = repository.save(identity.copy(accountId = linkedAccountId, metadataJson = "{\"v\":2}"))
            assertEquals(saved.id, updated.id)

            val byProvider = repository.findByProviderSubject(IdentityProvider.GOOGLE, "google-subject")
            assertNotNull(byProvider)
            assertEquals("google-subject", byProvider.providerSubject)

            assertEquals(1, repository.findByAccountId(updated.accountId).size)
            assertEquals(1, repository.findByVerifiedEmail("identity@example.com").size)
            assertTrue(repository.touchLastSignIn(updated.id))
            assertFalse(repository.touchLastSignIn(Uuid.random()))

            val linkEvent =
                AccountLinkEvent(
                    id = Uuid.random(),
                    accountId = updated.accountId,
                    provider = IdentityProvider.GOOGLE,
                    providerSubject = "google-subject",
                    reason = "implicit-link",
                    createdAt = now,
                    metadataJson = "{}",
                )
            val persistedEvent = repository.saveLinkEvent(linkEvent)
            assertEquals(linkEvent.id, persistedEvent.id)
        }
    }

    @Test
    fun `signing key repository supports save lookup and revoke`() {
        withH2Database(AccountsTable, SigningKeysTable) {
            val accountId = Uuid.random()
            val accountRepository = PostgreSQLAccountRepository()
            val repository = PostgreSQLSigningKeyRepository()

            val stored =
                StoredSigningKey(
                    id = Uuid.random(),
                    accountId = accountId,
                    publicKeyMultibase = "zSigningKey",
                    privateKeyEncrypted = "encrypted",
                    createdAt = Clock.System.now(),
                )

            runBlocking {
                accountRepository.save(sampleAccount(accountId, username = "signing-owner"))
                repository.save(stored)
                assertEquals(stored.publicKeyMultibase, repository.findActiveByAccountId(accountId)?.publicKeyMultibase)
                assertEquals(1, repository.revokeActiveKeys(accountId))
                assertNull(repository.findActiveByAccountId(accountId))
            }
        }
    }

    @Test
    fun `signing key repository lists and replaces encrypted private keys only while unchanged`() {
        withH2Database(AccountsTable, SigningKeysTable) {
            val accountId = Uuid.random()
            val repository = PostgreSQLSigningKeyRepository()
            val stored =
                StoredSigningKey(
                    id = Uuid.random(),
                    accountId = accountId,
                    publicKeyMultibase = "zSigningKey",
                    privateKeyEncrypted = "encrypted-old",
                    createdAt = Clock.System.now(),
                )

            runBlocking {
                PostgreSQLAccountRepository().save(sampleAccount(accountId, username = "rewrap-owner"))
                repository.save(stored)

                assertEquals(
                    listOf(EncryptedPrivateKey(stored.id.toString(), "encrypted-old")),
                    repository.encryptedPrivateKeys(),
                )
                assertFalse(repository.replaceEncryptedPrivateKey(stored.id.toString(), "stale", "encrypted-new"))
                assertTrue(repository.replaceEncryptedPrivateKey(stored.id.toString(), "encrypted-old", "encrypted-new"))
                assertEquals("encrypted-new", repository.findActiveByAccountId(accountId)?.privateKeyEncrypted)

                repository.revokeActiveKeys(accountId)
                assertEquals(emptyList(), repository.encryptedPrivateKeys())
            }
        }
    }

    @Test
    fun `passkey repository supports storage lookup updates and utility methods`() {
        withPasskeyTables {
            val accountId = Uuid.random()
            val repository = PostgreSQLPasskeyRepository()
            PostgreSQLAccountRepository().save(sampleAccount(accountId, username = "passkey-owner"))
            val info = samplePasskeyInfo("cred-a")

            assertTrue(
                repository.storePasskey(
                    userId = accountId,
                    credentialId = "cred-a",
                    publicKey = byteArrayOf(1, 2, 3),
                    signCount = 1,
                    info = info,
                ),
            )

            assertTrue(repository.credentialExists("cred-a"))
            assertFalse(repository.credentialExists("missing"))
            assertTrue(repository.credentialBelongsToUser("cred-a", accountId))
            assertFalse(repository.credentialBelongsToUser("cred-a", Uuid.random()))

            val byCredential = repository.getPasskeyByCredentialId("cred-a")
            assertNotNull(byCredential)
            assertEquals(accountId, byCredential.first)
            assertEquals(1L, byCredential.second.signCount)

            assertEquals(1, repository.getPasskeysForUser(accountId).size)
            assertEquals(listOf("cred-a"), repository.getCredentialIdsForUser(accountId))

            assertTrue(repository.updateSignCount("cred-a", 7))
            assertFalse(repository.updateSignCount("missing", 1))

            assertNotNull(repository.findByCredentialId("cred-a"))
            assertNotNull(repository.findById(info.id))
            assertEquals(1, repository.findByAccountId(accountId).size)
            assertEquals(1, repository.findActiveByAccountId(accountId).size)

            // Cover decode fallback path by storing non-base64 public key.
            val plainInfo = samplePasskeyInfo("cred-b")
            insertPasskeyRow(accountId, plainInfo, publicKey = "@@not-base64@@", signCount = 5)
            val plainStored = repository.getPasskeyByCredentialId("cred-b")
            assertNotNull(plainStored)
            assertTrue(plainStored.second.publicKey.contentEquals("@@not-base64@@".toByteArray()))
            assertEquals(5L, plainStored.second.signCount)

            assertFalse(repository.deactivatePasskey("cred-a", Uuid.random()))
            assertTrue(repository.deactivatePasskey("cred-a", accountId))
            assertNull(repository.getPasskeyByCredentialId("cred-a"))

            assertTrue(repository.deletePasskey(plainInfo.id))
            assertFalse(repository.deletePasskey(plainInfo.id))
        }
    }

    @Test
    fun `session manager handles creation validation expiration and cleanup`() {
        withSessionTables {
            val repository = PostgreSQLSessionManager()
            val now = Clock.System.now()

            val accountCreation =
                repository.createAccountCreationSession(
                    temporaryUserId = null,
                    username = "alice",
                    displayName = "Alice",
                    challenge = "challenge-a",
                    deviceInfo = DeviceInfo(platform = "android", deviceName = "Pixel"),
                    bio = "bio",
                )
            assertNotNull(repository.getSession(accountCreation.id))
            assertNotNull(repository.validateSession(accountCreation.id, SessionType.ACCOUNT_CREATION))
            assertNull(repository.validateSession(accountCreation.id, SessionType.AUTHENTICATION))

            assertTrue(repository.markSessionUsed(accountCreation.id))
            assertNull(repository.validateSession(accountCreation.id, SessionType.ACCOUNT_CREATION))
            assertFalse(repository.markSessionUsed("missing"))

            val authSession =
                repository.createAuthenticationSession(
                    challenge = "challenge-b",
                    accountHint = null,
                    deviceInfo = null,
                )
            assertEquals("", authSession.username)
            assertNotNull(repository.validateSession(authSession.id, SessionType.AUTHENTICATION))
            assertTrue(repository.removeSession(authSession.id))
            assertFalse(repository.removeSession(authSession.id))
            assertNull(repository.getSession("missing-session"))

            // Store an expired session to cover expiry removal path.
            val expired =
                TemporarySession(
                    id = "expired-session",
                    temporaryUserId = Uuid.random(),
                    challenge = "challenge-expired",
                    username = "u",
                    displayName = "d",
                    bio = null,
                    deviceInfo = null,
                    sessionType = SessionType.AUTHENTICATION,
                    createdAt = now.minus(10.seconds),
                    expiresAt = now.minus(1.seconds),
                    isUsed = false,
                )
            repository.storeSession(expired)
            assertNull(repository.getSession(expired.id))

            // Add one used and one expired session to exercise cleanup logic.
            val usedSession =
                TemporarySession(
                    id = "used-session",
                    temporaryUserId = Uuid.random(),
                    challenge = "challenge-used",
                    username = "u",
                    displayName = "d",
                    bio = null,
                    deviceInfo = null,
                    sessionType = SessionType.AUTHENTICATION,
                    createdAt = now,
                    expiresAt = now.plus(5.minutes),
                    isUsed = true,
                )
            repository.storeSession(usedSession)

            val expired2 =
                TemporarySession(
                    id = "expired-session-2",
                    temporaryUserId = Uuid.random(),
                    challenge = "challenge-expired-2",
                    username = "u",
                    displayName = "d",
                    bio = null,
                    deviceInfo = null,
                    sessionType = SessionType.AUTHENTICATION,
                    createdAt = now.minus(10.minutes),
                    expiresAt = now.minus(2.minutes),
                    isUsed = false,
                )
            repository.storeSession(expired2)

            // Insert a row with nullable username/displayName to cover null fallback mapping.
            val nullableRowSessionId = "nullable-fields-session"
            transaction {
                SessionsTable.insert {
                    it[id] = nullableRowSessionId
                    it[temporaryUserId] = Uuid.random().toJavaUUID()
                    it[challenge] = "c-null"
                    it[sessionType] = SessionType.AUTHENTICATION.name
                    it[username] = null
                    it[displayName] = null
                    it[bio] = null
                    it[deviceInfo] = null
                    val now = Clock.System.now()
                    it[createdAt] = now
                    it[expiresAt] = now + 5.minutes
                    it[isUsed] = false
                }
            }
            val nullableRowSession = repository.getSession(nullableRowSessionId)
            assertNotNull(nullableRowSession)
            assertEquals("", nullableRowSession.username)
            assertEquals("", nullableRowSession.displayName)

            assertTrue(repository.cleanupExpiredSessions() >= 2)
        }
    }

    private fun withAccountTables(block: suspend () -> Unit) {
        withH2Database(
            AccountsTable,
            PendingEmailVerificationsTable,
            AtprotoPasswordCredentialsTable,
            AtprotoSessionsTable,
            SigningKeysTable,
            HostedPlcOperationsTable,
            AccountIdentitiesTable,
            AccountLinkEventsTable,
            PasskeysTable,
            RestoreCredentialsTable,
            ResourceRoutesTable,
            AccountEntitlementsTable,
            AtprotoRepoHeadsTable,
            AtprotoRepoBlocksTable,
            AtprotoRepoBlockLinksTable,
            AtprotoRepoCommitsTable,
            LogDateCollectionStatesTable,
            LogDateCollectionRecordsTable,
        ) {
            runBlocking { block() }
        }
    }

    /**
     * Writes a small repository for [accountDid], with one block shared with every other repository,
     * plus sync state under [syncRepoDid] and one indexed record.
     */
    private fun insertRepository(
        accountId: Uuid,
        accountDid: String,
        syncRepoDid: String,
    ) {
        transaction {
            for (did in setOf(accountDid, syncRepoDid)) {
                AtprotoRepoHeadsTable.insert {
                    it[repoDid] = did
                    it[rootCid] = "root"
                    it[commitCid] = "commit"
                    it[revision] = 1
                    it[updatedAt] = Clock.System.now()
                }
                AtprotoRepoCommitsTable.insert {
                    it[repoDid] = did
                    it[revision] = 1
                    it[cid] = "commit"
                    it[rootCid] = "root"
                    it[prevCid] = null
                    it[createdAtEpochMillis] = 1
                    it[recordCount] = 1
                    it[signature] = "signature"
                }
                for (blockCid in listOf("shared", "only-$did")) {
                    AtprotoRepoBlocksTable.insertIgnore {
                        it[cid] = blockCid
                        it[bytes] = byteArrayOf(1)
                    }
                    AtprotoRepoBlockLinksTable.insert {
                        it[repoDid] = did
                        it[cid] = blockCid
                    }
                }
            }
            LogDateCollectionStatesTable.insert {
                it[userId] = accountId.toJavaUuid()
                it[repoDid] = syncRepoDid
                it[lastVersion] = 1
                it[updatedAt] = Clock.System.now()
            }
            LogDateCollectionRecordsTable.insert {
                it[userId] = accountId.toJavaUuid()
                it[collection] = "entry"
                it[recordKey] = "entry-1"
                it[serverVersion] = 1
                it[deleted] = false
                it[deletedAt] = null
            }
        }
    }

    private fun withAccountIdentityTables(block: suspend () -> Unit) {
        withH2Database(AccountsTable, AccountIdentitiesTable, AccountLinkEventsTable) {
            runBlocking { block() }
        }
    }

    private fun withPasskeyTables(block: suspend () -> Unit) {
        withH2Database(AccountsTable, PasskeysTable) {
            runBlocking { block() }
        }
    }

    private fun withSessionTables(block: suspend () -> Unit) {
        withH2Database(SessionsTable) {
            runBlocking { block() }
        }
    }

    /** Stores a passkey row as written, including a public key the repository would not encode. */
    private fun insertPasskeyRow(
        accountId: Uuid,
        passkey: PasskeyInfo,
        publicKey: String,
        signCount: Long,
    ) {
        transaction {
            PasskeysTable.insert {
                it[id] = passkey.id.toJavaUuid()
                it[this.accountId] = accountId.toJavaUuid()
                it[credentialId] = passkey.credentialId
                it[this.publicKey] = publicKey
                it[this.signCount] = signCount
                it[createdAt] = passkey.createdAt
            }
        }
    }

    private fun samplePasskeyInfo(credentialId: String): PasskeyInfo =
        PasskeyInfo(
            id = Uuid.random(),
            credentialId = credentialId,
            nickname = "phone",
            deviceType = "platform",
            createdAt = Clock.System.now(),
            lastUsedAt = null,
            isActive = true,
        )

    private fun sampleAccount(
        id: Uuid,
        username: String,
    ): Account =
        Account(
            id = id,
            username = username,
            displayName = username,
            email = "$username@example.com",
            emailVerified = true,
            bio = null,
            createdAt = Clock.System.now(),
            preferences = "{}",
            isActive = true,
        )
}
