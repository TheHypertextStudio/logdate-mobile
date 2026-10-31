package app.logdate.server.database

import app.logdate.server.database.support.withH2Database
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbQueryTest {
    @Test
    fun `a slow query leaves the calling thread free for other work`() =
        withH2Database {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { requestThread ->
                runBlocking(requestThread) {
                    val released = CountDownLatch(1)
                    val query = async { dbQuery { released.await(5, TimeUnit.SECONDS) } }
                    launch { released.countDown() }

                    assertTrue(query.await(), "The query held the only calling thread, so nothing could release it")
                }
            }
        }

    @Test
    fun `a query inside a transaction is part of that transaction`() =
        withH2Database(ProbeTable) {
            runBlocking {
                runCatching {
                    suspendTransaction {
                        dbQuery { ProbeTable.insert { it[value] = "rolled back" } }
                        error("The enclosing transaction fails after the query")
                    }
                }

                assertEquals(0, dbQuery { ProbeTable.selectAll().count() })
            }
        }

    @Test
    fun `server code queries through dbQuery outside the files still listed as blocking`() {
        val sourceRoot = File("src/main/kotlin")
        val blockingCall = Regex("""\btransaction\s*[({]""")
        val offenders =
            sourceRoot
                .walk()
                .filter { it.extension == "kt" }
                .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
                .filter { it !in STILL_BLOCKING && it != DB_QUERY_SOURCE }
                .filter { path ->
                    sourceRoot.resolve(path).readLines().any { line ->
                        val code = line.trimStart()
                        !code.startsWith("import ") && !code.startsWith("*") && !code.startsWith("//") && blockingCall.containsMatchIn(code)
                    }
                }.toList()

        assertEquals(emptyList(), offenders, "Use dbQuery {} from suspend code instead of a blocking transaction {}")
        assertTrue(STILL_BLOCKING.all { sourceRoot.resolve(it).isFile }, "Remove entries for files that no longer exist")
    }

    private object ProbeTable : Table("db_query_probe") {
        val value = varchar("value", 64)
    }

    private companion object {
        const val DB_QUERY_SOURCE = "app/logdate/server/database/DbQuery.kt"

        /**
         * Files that still run blocking transactions. Remove a file from this list when it moves to
         * [dbQuery]. The signing key repository stays blocking because [app.logdate.server.oauth.OAuthKeyService]
         * reads it synchronously and caches the result.
         */
        val STILL_BLOCKING =
            setOf(
                "app/logdate/server/database/PostgreSQLAccountRepository.kt",
                "app/logdate/server/database/PostgreSQLHostedPlcOperationRepository.kt",
                "app/logdate/server/database/PostgreSQLLegacyJournalEraser.kt",
                "app/logdate/server/database/PostgreSQLLogDateAtprotoBlobRepository.kt",
                "app/logdate/server/database/PostgreSQLLogDateBackupRepository.kt",
                "app/logdate/server/database/PostgreSQLLogDateCollectionsMetadataStore.kt",
                "app/logdate/server/database/PostgreSQLLogDateMediaRepository.kt",
                "app/logdate/server/database/PostgreSQLOAuthSigningKeyRepository.kt",
                "app/logdate/server/database/PostgreSQLPasskeyChallengeRepository.kt",
                "app/logdate/server/database/PostgreSQLPublicRepoDirectory.kt",
                "app/logdate/server/database/PostgreSQLRepoBlockStore.kt",
                "app/logdate/server/database/PostgreSQLResourceRouteRepository.kt",
                "app/logdate/server/database/PostgreSQLSigningKeyRepository.kt",
                "app/logdate/server/database/PostgreSQLSpaceMigrationStore.kt",
                "app/logdate/server/database/PostgreSQLSpaceStore.kt",
                "app/logdate/server/sync/DbLocationHistoryRepository.kt",
                "app/logdate/server/sync/DbSyncAssociationMediaBackup.kt",
                "app/logdate/server/sync/DbSyncJournalOperations.kt",
                "app/logdate/server/sync/DbSyncRepository.kt",
            )
    }
}
