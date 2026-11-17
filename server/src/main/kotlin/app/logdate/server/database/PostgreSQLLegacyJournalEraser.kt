package app.logdate.server.database

import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.migration.LegacyJournalEraser
import app.logdate.server.sync.LocationHistoryTable
import app.logdate.server.sync.LocationHistoryVersionsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * The account column of V37's journal merge history, which legacy sync alone uses.
 *
 * Erase() deletes through it; SearchDeadCode 0.21.0 misses uses inside nested Exposed lambdas.
 */
@Suppress("unused")
private object JournalMergeOperationsTable : Table("journal_merge_operations") {
    val accountId = javaUUID("account_id")
}

/**
 * Deletes an account's legacy journal from PostgreSQL and object storage. The files go first, while
 * their records still say where they are, so a failure part way leaves the records for the next run;
 * then every row goes in one transaction, the public repository and sync index included.
 */
class PostgreSQLLegacyJournalEraser(
    private val storage: LogDateBlobStorage?,
) : LegacyJournalEraser {
    override suspend fun erase(accountId: UUID) {
        withContext(Dispatchers.IO) {
            val paths = transaction { storagePaths(accountId) }
            storage?.let { objects -> paths.forEach(objects::deleteBlobIfExists) }
            transaction {
                deleteAccountRepositories(accountId)
                LocationHistoryTable.deleteWhere { LocationHistoryTable.userId eq accountId }
                LocationHistoryVersionsTable.deleteWhere { LocationHistoryVersionsTable.userId eq accountId }
                LogDateMediaRecordsTable.deleteWhere { LogDateMediaRecordsTable.userId eq accountId }
                LogDateAtprotoBlobsTable.deleteWhere { LogDateAtprotoBlobsTable.userId eq accountId }
                JournalMergeOperationsTable.deleteWhere { JournalMergeOperationsTable.accountId eq accountId }
            }
        }
    }

    private fun storagePaths(accountId: UUID): List<String> {
        val media =
            LogDateMediaRecordsTable
                .select(LogDateMediaRecordsTable.storagePath)
                .where { (LogDateMediaRecordsTable.userId eq accountId) and LogDateMediaRecordsTable.storagePath.isNotNull() }
                .mapNotNull { it[LogDateMediaRecordsTable.storagePath] }
        val blobs =
            LogDateAtprotoBlobsTable
                .select(LogDateAtprotoBlobsTable.storagePath)
                .where { LogDateAtprotoBlobsTable.userId eq accountId }
                .map { it[LogDateAtprotoBlobsTable.storagePath] }
        return (media + blobs).distinct()
    }
}
