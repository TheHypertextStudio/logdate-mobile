package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.withH2Database
import app.logdate.server.database.support.withOwnPostgresDatabase
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExternalIdentityAccountTest {
    @Test
    fun `identity kind uses stable strings and unknown kinds fail closed`() {
        assertEquals("\"external\"", Json.encodeToString(AccountIdentityKind.EXTERNAL))
        assertEquals(AccountIdentityKind.UNRECOGNIZED, Json.decodeFromString<AccountIdentityKind>("\"future-kind\""))
    }

    @Test
    fun `in memory external accounts preserve their identity and cannot become hosted`() =
        runBlocking { verifyIsolation(InMemoryAccountRepository()) }

    @Test
    fun `database external accounts preserve their identity and cannot become hosted`() =
        withH2Database(AccountsTable) { runBlocking { verifyIsolation(PostgreSQLAccountRepository()) } }

    @Test
    fun `in memory creation refuses duplicate DIDs and invalid external accounts`() =
        runBlocking { verifyCreation(InMemoryAccountRepository()) }

    @Test
    fun `database creation refuses duplicate DIDs and invalid external accounts`() =
        withH2Database(AccountsTable) { runBlocking { verifyCreation(PostgreSQLAccountRepository()) } }

    @Test
    fun `migration keeps existing accounts hosted and preserves future kinds`() =
        withH2Database(AccountsTable) {
            runBlocking {
                val repository = PostgreSQLAccountRepository()
                val hosted = external().copy(identityKind = AccountIdentityKind.HOSTED)
                assertTrue(repository.create(hosted))
                org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                    exec("ALTER TABLE accounts DROP COLUMN identity_kind")
                    exec(requireNotNull(javaClass.classLoader.getResource("db/migration/V58__Add_account_identity_kind.sql")).readText())
                }
                assertEquals(AccountIdentityKind.HOSTED, repository.findById(hosted.id)?.identityKind)
                org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                    exec("UPDATE accounts SET identity_kind = 'future-kind'")
                }
                val future = requireNotNull(repository.findById(hosted.id))
                assertEquals(AccountIdentityKind.UNRECOGNIZED, future.identityKind)
                assertEquals(AccountIdentityKind.UNRECOGNIZED, repository.save(future.copy(displayName = "Renamed")).identityKind)
                assertFalse(repository.recordPublicIdentity(future.id, requireNotNull(future.did)))
                assertFalse(repository.updateStorageModeIfUnchanged(future.id, future.storageMode, AccountStorageMode.PERSONAL_SPACE_V1))
                assertNull(repository.updateIdentityIfUnchanged(future, future.copy(did = "did:web:replacement.example.com")))
                assertTrue(repository.findAccountsMissingIdentity().isEmpty())
                org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                    exec("SELECT identity_kind FROM accounts") { rows ->
                        assertTrue(rows.next())
                        assertEquals("future-kind", rows.getString(1))
                    }
                }
            }
        }

    @Test
    fun `postgres migrations preserve the external kind and prevent hosted lifecycle changes`() =
        withOwnPostgresDatabase("external_identity_") { url ->
            localTestPostgresDataSource(url).use { source ->
                val previous = TransactionManager.defaultDatabase
                try {
                    TransactionManager.defaultDatabase = DatabaseConfig.initializeDatabase(source, autoMigrate = true)
                    runBlocking {
                        verifyIsolation(PostgreSQLAccountRepository())
                    }
                    source.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute("DELETE FROM accounts")
                        }
                        connection.commit()
                    }
                    runBlocking { verifyCreation(PostgreSQLAccountRepository()) }
                } finally {
                    TransactionManager.defaultDatabase = previous
                }
            }
        }

    private suspend fun verifyIsolation(repository: AccountRepository) {
        val account = external()
        assertTrue(repository.create(account))
        val forged =
            account.copy(
                displayName = "Renamed",
                identityKind = AccountIdentityKind.HOSTED,
                did = "did:web:replacement.example.com",
                handle = "replacement.example.com",
                signingKeyPublic = "zForged",
                plcRecoveryDidKey = "did:key:zForged",
                publicIdentityDid = account.did,
                storageMode = AccountStorageMode.LEGACY,
            )
        val stored = repository.save(forged)
        assertEquals(account.copy(displayName = "Renamed"), stored)
        assertEquals(stored, repository.findById(account.id))
        assertEquals(stored, repository.findByDid(requireNotNull(account.did)))
        assertNull(repository.findByDid(requireNotNull(forged.did)))
        assertEquals(stored, repository.findByHandle(requireNotNull(account.handle)))
        assertNull(repository.findByHandle(requireNotNull(forged.handle)))
        assertFalse(repository.recordPublicIdentity(account.id, requireNotNull(account.did)))
        assertNull(repository.updateIdentityIfUnchanged(stored, forged))
        assertFalse(repository.updateStorageModeIfUnchanged(account.id, AccountStorageMode.PERSONAL_SPACE_V1, AccountStorageMode.LEGACY))
        assertTrue(repository.findAccountsMissingIdentity().isEmpty())
        val hosted =
            account.copy(
                id = Uuid.random(),
                username = "hosted",
                did = null,
                handle = null,
                identityKind = AccountIdentityKind.HOSTED,
            )
        assertTrue(repository.createOnly(hosted))
        assertEquals(AccountIdentityKind.HOSTED, repository.save(hosted.copy(identityKind = AccountIdentityKind.EXTERNAL)).identityKind)
        assertEquals(listOf(hosted.id), repository.findAccountsMissingIdentity().map { it.id })
    }

    private suspend fun verifyCreation(repository: AccountRepository) {
        val account = external()
        assertTrue(repository.createOnly(account))
        assertFalse(repository.createOnly(account.copy(id = Uuid.random(), username = "other", handle = "other.example.com")))
        assertFalse(repository.create(account.copy(id = Uuid.random(), username = "third", handle = "third.example.com")))
        val longHost = List(3) { "a".repeat(63) }.joinToString(".") + "." + "b".repeat(61)
        val longDid = "did:web:$longHost"
        val longAccount = account.copy(id = Uuid.random(), username = "long", did = longDid, handle = longHost)
        assertTrue(repository.create(longAccount))
        assertEquals(longDid, repository.findById(longAccount.id)?.did)
        val invalid = account.copy(id = Uuid.random(), username = "invalid", did = null, handle = null)
        for (candidate in listOf(
            invalid,
            invalid.copy(did = account.did, storageMode = AccountStorageMode.LEGACY),
            invalid.copy(did = account.did, signingKeyPublic = "zKey"),
            invalid.copy(did = account.did, plcRecoveryDidKey = "did:key:zKey"),
            invalid.copy(did = account.did, publicIdentityDid = account.did),
            invalid.copy(identityKind = AccountIdentityKind.UNRECOGNIZED),
        )) {
            assertFailsWith<IllegalArgumentException> { repository.createOnly(candidate) }
            assertFailsWith<IllegalArgumentException> { repository.create(candidate) }
            assertFailsWith<IllegalArgumentException> { repository.save(candidate) }
        }
    }

    private fun external() =
        Account(
            id = Uuid.random(),
            username = "external",
            displayName = "External",
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()),
            did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz",
            handle = "external.example.com",
            preferences = "{}",
            storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
            identityKind = AccountIdentityKind.EXTERNAL,
        )
}
