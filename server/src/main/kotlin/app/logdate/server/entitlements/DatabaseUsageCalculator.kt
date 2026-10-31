package app.logdate.server.entitlements

import app.logdate.server.database.AccountStorageUsageTable
import app.logdate.server.database.LogDateBackupsTable
import app.logdate.server.database.StorageTotals
import app.logdate.server.database.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * [UsageCalculator] backed by the running storage totals in `account_storage_usage`, which every
 * write of media, backups and personal-space data keeps current in its own transaction. Reading an
 * account's storage is one row, however much it stores. Backup count comes from the backup rows.
 *
 * The [database] parameter is explicit rather than relying on Exposed's global default: the rest
 * of the server's Postgres repos use the thread-local default because they were scaffolded before
 * we had a DI story, but new code should take its dependencies by constructor so tests and
 * multi-database setups stay tractable.
 */
class DatabaseUsageCalculator(
    private val database: Database,
) : UsageCalculator {
    override suspend fun storageBytes(accountId: UUID): Long = totals(accountId).let { it.spaceBytes + it.mediaBytes + it.backupBytes }

    override suspend fun storageBytesOutsideSpaces(accountId: UUID): Long = totals(accountId).let { it.mediaBytes + it.backupBytes }

    override suspend fun backupCount(accountId: UUID): Int =
        dbQuery(database) {
            LogDateBackupsTable
                .selectAll()
                .where { LogDateBackupsTable.userId eq accountId }
                .count()
                .toInt()
        }

    private suspend fun totals(accountId: UUID): StorageTotals =
        dbQuery(database) {
            AccountStorageUsageTable
                .select(AccountStorageUsageTable.spaceBytes, AccountStorageUsageTable.mediaBytes, AccountStorageUsageTable.backupBytes)
                .where { AccountStorageUsageTable.accountId eq accountId }
                .singleOrNull()
                ?.let {
                    StorageTotals(
                        spaceBytes = it[AccountStorageUsageTable.spaceBytes],
                        mediaBytes = it[AccountStorageUsageTable.mediaBytes],
                        backupBytes = it[AccountStorageUsageTable.backupBytes],
                    )
                } ?: StorageTotals(0L, 0L, 0L)
        }
}
