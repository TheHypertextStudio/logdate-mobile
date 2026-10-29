package app.logdate.server.database

import app.logdate.server.atproto.firehose.PublicRepoDirectory
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountModeration
import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.withH2Database
import app.logdate.server.database.support.withOwnPostgresDatabase
import app.logdate.shared.model.AccountStorageMode
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DAG_CBOR_CODEC
import studio.hypertext.atproto.repo.RepoHead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Finding the repositories to list takes one query, with the same rules as the gate that decides what is
 * shown. It runs against H2 and against the real PostgreSQL through the real migrations.
 */
@OptIn(ExperimentalUuidApi::class)
class PostgreSQLPublicRepoDirectoryTest {
    private val cid = Cid.sha256(DAG_CBOR_CODEC, byteArrayOf(1))

    private fun onH2(test: suspend () -> Unit) = withH2Database(AccountsTable, AtprotoRepoHeadsTable) { runBlocking { test() } }

    private fun onPostgres(test: suspend () -> Unit) =
        withOwnPostgresDatabase("directory_") { url ->
            localTestPostgresDataSource(url).use { source ->
                val previous = TransactionManager.defaultDatabase
                try {
                    TransactionManager.defaultDatabase = DatabaseConfig.initializeDatabase(source, autoMigrate = true)
                    runBlocking { test() }
                } finally {
                    TransactionManager.defaultDatabase = previous
                }
            }
        }

    private suspend fun seed(
        did: String,
        mode: AccountStorageMode = AccountStorageMode.PERSONAL_SPACE_V1,
        active: Boolean = true,
        head: Boolean = true,
        moderation: AccountModeration? = null,
        publicIdentity: Boolean = true,
    ) {
        val accounts = PostgreSQLAccountRepository()
        val account =
            Account(
                id = Uuid.random(),
                username = "u" + Uuid.random().toHexString().take(12),
                displayName = "x",
                did = did,
                publicIdentityDid = if (publicIdentity) did else null,
                createdAt = Clock.System.now(),
                storageMode = mode,
                isActive = active,
            )
        accounts.create(account)
        moderation?.let { accounts.setModeration(account.id, it) }
        if (head) PostgreSQLRepoBlockStore().writeHead(RepoHead(AtprotoDid.require(did), cid, cid, revision = 7)).getOrThrow()
    }

    private suspend fun scenario(directory: PublicRepoDirectory) {
        seed("did:web:b.example.com")
        seed("did:web:a.example.com")
        seed("did:web:c.example.com", mode = AccountStorageMode.LEGACY)
        seed("did:web:d.example.com", active = false)
        seed("did:web:e.example.com", moderation = AccountModeration.TAKENDOWN)
        seed("did:web:f.example.com", head = false)
        seed("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
        seed("did:web:external.example.com")
        seed("did:web:future.example.com")
        transaction {
            exec("UPDATE accounts SET identity_kind = 'external' WHERE did = 'did:web:external.example.com'")
            exec("UPDATE accounts SET identity_kind = 'future-kind' WHERE did = 'did:web:future.example.com'")
        }

        assertEquals(listOf("did:web:a.example.com", "did:web:b.example.com"), directory.after(null, 10).map { it.did })
        assertEquals(listOf("did:web:b.example.com"), directory.after("did:web:a.example.com", 10).map { it.did })
        assertEquals(listOf("did:web:a.example.com"), directory.after(null, 1).map { it.did })
        val first = directory.after(null, 1).single()
        assertEquals(7, first.revision)
        assertEquals(cid.toString(), first.commitCid)
    }

    @Test
    fun `h2 never lists private identities even when PLC publishing is enabled`() =
        onH2 {
            seed("did:web:private.example.com", publicIdentity = false)
            seed("did:plc:ewvi7nxzyoun6zhxrhs64oiz", publicIdentity = false)
            assertEquals(emptyList(), PostgreSQLPublicRepoDirectory(publishesPlc = true).after(null, 10))
        }

    @Test
    fun `h2 lists only accounts that may be shown, in DID order`() = onH2 { scenario(PostgreSQLPublicRepoDirectory(publishesPlc = false)) }

    @Test
    fun `postgres lists only accounts that may be shown, in DID order`() =
        onPostgres {
            scenario(PostgreSQLPublicRepoDirectory(publishesPlc = false))
        }

    @Test
    fun `a did plc is listed only when the server publishes them`() =
        onPostgres {
            seed("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
            seed("did:web:a.example.com")

            assertEquals(
                listOf("did:web:a.example.com"),
                PostgreSQLPublicRepoDirectory(publishesPlc = false).after(null, 10).map { it.did },
            )
            assertEquals(
                listOf("did:plc:ewvi7nxzyoun6zhxrhs64oiz", "did:web:a.example.com"),
                PostgreSQLPublicRepoDirectory(publishesPlc = true).after(null, 10).map { it.did },
            )
        }
}
