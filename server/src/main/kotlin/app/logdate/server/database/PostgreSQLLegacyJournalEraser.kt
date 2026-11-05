package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.migration.LegacyJournalEraser
import app.logdate.server.migration.SpaceMigration
import app.logdate.server.sync.LocationHistoryTable
import app.logdate.server.sync.LocationHistoryVersionsTable
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi

/** The account column of V37's journal merge history, which legacy sync alone uses. */
private object JournalMergeOperationsTable : Table("journal_merge_operations") {
    val accountId = javaUUID("account_id")
}

/**
 * Deletes an account's legacy journal from PostgreSQL and object storage. The files go first, while
 * their records still say where they are, so a failure part way leaves the records for the next run;
 * then every row goes in one transaction, the public repository and sync index included.
 */
@OptIn(ExperimentalUuidApi::class)
class PostgreSQLLegacyJournalEraser(
    private val storage: LogDateBlobStorage?,
) : LegacyJournalEraser {
    private data class PreparedCleanup(
        val did: String?,
        val paths: List<String>,
    )

    override suspend fun erase(claim: SpaceMigration): Boolean =
        withContext(Dispatchers.IO) {
            val prepared =
                transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                    if (!authorized(claim)) return@transaction null
                    PreparedCleanup(currentDid(claim.accountId), storagePaths(claim.accountId))
                } ?: return@withContext false
            check(prepared.paths.isEmpty() || storage != null) { "Object storage cleanup is unavailable" }
            storage?.let { objects -> prepared.paths.forEach(objects::deleteBlobIfExists) }
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                if (!authorized(claim) || currentDid(claim.accountId) != prepared.did) return@transaction false
                eraseRows(claim.accountId)
                true
            }
        }

    private fun authorized(claim: SpaceMigration): Boolean {
        if (claim.phase != SpaceMigrationPhase.CLEANED || claim.cleanedAt != null) return false
        val account =
            AccountsTable
                .select(AccountsTable.storageMode, AccountsTable.did)
                .where { AccountsTable.id eq claim.accountId }
                .forUpdate()
                .singleOrNull() ?: return false
        if (account[AccountsTable.storageMode] != AccountStorageMode.PERSONAL_SPACE_V1.wireValue) return false
        val connection = TransactionManager.current().connection.connection as Connection
        if (connection.lockIdentityWrite(
                IdentityWriteExpectation(claim.accountId.toKotlinUuid(), claim.identityEpoch),
                Clock.System.now(),
                author = account[AccountsTable.did],
            ) != IdentityFenceOutcome.ALLOWED
        ) {
            return false
        }
        return SpaceMigrationsTable
            .selectAll()
            .where {
                (SpaceMigrationsTable.accountId eq claim.accountId) and
                    (SpaceMigrationsTable.migrationId eq claim.migrationId) and
                    (SpaceMigrationsTable.version eq claim.version) and
                    (SpaceMigrationsTable.identityEpoch eq claim.identityEpoch) and
                    (SpaceMigrationsTable.phase eq SpaceMigrationPhase.CLEANED.wireValue) and SpaceMigrationsTable.cleanedAt.isNull()
            }.forUpdate()
            .singleOrNull() != null
    }

    private fun currentDid(accountId: UUID): String? =
        AccountsTable
            .select(AccountsTable.did)
            .where { AccountsTable.id eq accountId }
            .single()[AccountsTable.did]

    private fun eraseRows(accountId: UUID) {
        deleteAccountRepositories(accountId)
        LocationHistoryTable.deleteWhere { LocationHistoryTable.userId eq accountId }
        LocationHistoryVersionsTable.deleteWhere { LocationHistoryVersionsTable.userId eq accountId }
        LogDateMediaRecordsTable.deleteWhere { LogDateMediaRecordsTable.userId eq accountId }
        LogDateAtprotoBlobsTable.deleteWhere { LogDateAtprotoBlobsTable.userId eq accountId }
        JournalMergeOperationsTable.deleteWhere { JournalMergeOperationsTable.accountId eq accountId }
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
