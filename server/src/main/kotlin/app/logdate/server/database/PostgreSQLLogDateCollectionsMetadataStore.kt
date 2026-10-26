package app.logdate.server.database

import app.logdate.server.logdate.LogDateCollectionChangesMetadata
import app.logdate.server.logdate.LogDateCollectionKind
import app.logdate.server.logdate.LogDateCollectionMetadata
import app.logdate.server.logdate.LogDateCollectionsMetadataStore
import app.logdate.server.logdate.LogDateCollectionsPurgeResult
import app.logdate.server.logdate.LogDateCollectionsState
import app.logdate.server.logdate.LogDateCollectionsStatus
import app.logdate.server.sync.StaleWriteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import studio.hypertext.atproto.identity.AtprotoDid
import java.util.UUID
import kotlin.time.Clock

internal class PostgreSQLLogDateCollectionsMetadataStore : LogDateCollectionsMetadataStore {
    override suspend fun status(userId: UUID): LogDateCollectionsStatus =
        withContext(Dispatchers.IO) {
            transaction {
                val state =
                    LogDateCollectionStatesTable
                        .selectAll()
                        .where { LogDateCollectionStatesTable.userId eq userId }
                        .singleOrNull()
                        ?.toCollectionsState()
                LogDateCollectionsStatus(
                    entryCount = liveCount(userId, LogDateCollectionKind.ENTRY),
                    journalCount = liveCount(userId, LogDateCollectionKind.JOURNAL),
                    associationCount = liveCount(userId, LogDateCollectionKind.ASSOCIATION),
                    lastTimestamp = state?.lastVersion ?: System.currentTimeMillis(),
                )
            }
        }

    override suspend fun state(userId: UUID): LogDateCollectionsState? =
        withContext(Dispatchers.IO) {
            transaction {
                LogDateCollectionStatesTable
                    .selectAll()
                    .where { LogDateCollectionStatesTable.userId eq userId }
                    .singleOrNull()
                    ?.toCollectionsState()
            }
        }

    override suspend fun listLive(
        userId: UUID,
        collection: LogDateCollectionKind,
    ): List<LogDateCollectionMetadata> =
        withContext(Dispatchers.IO) {
            transaction {
                LogDateCollectionRecordsTable
                    .selectAll()
                    .where {
                        (LogDateCollectionRecordsTable.userId eq userId) and
                            (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                            (LogDateCollectionRecordsTable.deleted eq false)
                    }.orderBy(LogDateCollectionRecordsTable.serverVersion to SortOrder.ASC)
                    .map(ResultRow::toCollectionMetadata)
            }
        }

    override suspend fun metadata(
        userId: UUID,
        collection: LogDateCollectionKind,
        recordKey: String,
    ): LogDateCollectionMetadata? =
        withContext(Dispatchers.IO) {
            transaction {
                LogDateCollectionRecordsTable
                    .selectAll()
                    .where {
                        (LogDateCollectionRecordsTable.userId eq userId) and
                            (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                            (LogDateCollectionRecordsTable.recordKey eq recordKey) and
                            (LogDateCollectionRecordsTable.deleted eq false)
                    }.singleOrNull()
                    ?.toCollectionMetadata()
            }
        }

    override suspend fun deletionTimestamp(
        userId: UUID,
        collection: LogDateCollectionKind,
        recordKey: String,
    ): Long? =
        withContext(Dispatchers.IO) {
            transaction {
                LogDateCollectionRecordsTable
                    .selectAll()
                    .where {
                        (LogDateCollectionRecordsTable.userId eq userId) and
                            (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                            (LogDateCollectionRecordsTable.recordKey eq recordKey) and
                            (LogDateCollectionRecordsTable.deleted eq true)
                    }.singleOrNull()
                    ?.let { it[LogDateCollectionRecordsTable.deletedAt] ?: 0L }
            }
        }

    override suspend fun changes(
        userId: UUID,
        collection: LogDateCollectionKind,
        since: Long,
        limit: Int,
    ): LogDateCollectionChangesMetadata =
        withContext(Dispatchers.IO) {
            transaction {
                val changeRows =
                    LogDateCollectionRecordsTable
                        .selectAll()
                        .where {
                            (LogDateCollectionRecordsTable.userId eq userId) and
                                (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                                (LogDateCollectionRecordsTable.deleted eq false) and
                                (LogDateCollectionRecordsTable.serverVersion greater since)
                        }.orderBy(LogDateCollectionRecordsTable.serverVersion to SortOrder.ASC)
                        .limit(limit + 1)
                        .toList()
                val deletionRows =
                    LogDateCollectionRecordsTable
                        .selectAll()
                        .where {
                            (LogDateCollectionRecordsTable.userId eq userId) and
                                (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                                (LogDateCollectionRecordsTable.deleted eq true) and
                                (LogDateCollectionRecordsTable.serverVersion greater since)
                        }.orderBy(LogDateCollectionRecordsTable.serverVersion to SortOrder.ASC)
                        .limit(limit + 1)
                        .toList()
                val page =
                    (changeRows + deletionRows)
                        .sortedBy { it[LogDateCollectionRecordsTable.serverVersion] }
                        .take(limit.coerceAtLeast(1))
                val limitedChanges = page.filter { !it[LogDateCollectionRecordsTable.deleted] }
                val limitedDeletions = page.filter { it[LogDateCollectionRecordsTable.deleted] }
                LogDateCollectionChangesMetadata(
                    changes = limitedChanges.map(ResultRow::toCollectionMetadata),
                    deletions = limitedDeletions.map(ResultRow::toCollectionMetadata),
                    lastTimestamp = page.lastOrNull()?.get(LogDateCollectionRecordsTable.serverVersion) ?: since,
                    hasMore = changeRows.size + deletionRows.size > page.size,
                )
            }
        }

    override suspend fun upsert(
        userId: UUID,
        repoDid: AtprotoDid,
        collection: LogDateCollectionKind,
        recordKey: String,
    ): LogDateCollectionMetadata =
        withContext(Dispatchers.IO) {
            transaction { upsertRow(userId, repoDid, collection, recordKey) }
        }

    override suspend fun upsertBatch(
        userId: UUID,
        repoDid: AtprotoDid,
        collection: LogDateCollectionKind,
        recordKeys: List<String>,
    ): List<LogDateCollectionMetadata> =
        withContext(Dispatchers.IO) {
            transaction {
                // One transaction for the whole batch: Exposed rolls the entire block back if any
                // row upsert throws, instead of leaving the first N rows committed and the rest
                // missing.
                recordKeys.map { recordKey -> upsertRow(userId, repoDid, collection, recordKey) }
            }
        }

    private fun upsertRow(
        userId: UUID,
        repoDid: AtprotoDid,
        collection: LogDateCollectionKind,
        recordKey: String,
    ): LogDateCollectionMetadata {
        val state = nextState(userId, repoDid)
        val existing =
            LogDateCollectionRecordsTable
                .selectAll()
                .where {
                    (LogDateCollectionRecordsTable.userId eq userId) and
                        (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                        (LogDateCollectionRecordsTable.recordKey eq recordKey)
                }.singleOrNull()
        if (existing != null && existing[LogDateCollectionRecordsTable.deleted]) {
            throw StaleWriteException(recordKey, existing[LogDateCollectionRecordsTable.deletedAt] ?: 0L)
        }
        if (existing == null) {
            LogDateCollectionRecordsTable.insert {
                it[LogDateCollectionRecordsTable.userId] = userId
                it[LogDateCollectionRecordsTable.collection] = collection.storageName
                it[LogDateCollectionRecordsTable.recordKey] = recordKey
                it[serverVersion] = state.lastVersion
                it[deleted] = false
                it[deletedAt] = null
            }
        } else {
            LogDateCollectionRecordsTable.update({
                (LogDateCollectionRecordsTable.userId eq userId) and
                    (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                    (LogDateCollectionRecordsTable.recordKey eq recordKey)
            }) {
                it[serverVersion] = state.lastVersion
                it[deleted] = false
                it[deletedAt] = null
            }
        }
        return LogDateCollectionMetadata(recordKey = recordKey, version = state.lastVersion, deletedAt = null)
    }

    override suspend fun delete(
        userId: UUID,
        repoDid: AtprotoDid,
        collection: LogDateCollectionKind,
        recordKey: String,
        deletedAt: Long,
        allowMissing: Boolean,
    ): LogDateCollectionMetadata? =
        withContext(Dispatchers.IO) {
            transaction {
                val state = nextState(userId, repoDid)
                val existing =
                    LogDateCollectionRecordsTable
                        .selectAll()
                        .where {
                            (LogDateCollectionRecordsTable.userId eq userId) and
                                (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                                (LogDateCollectionRecordsTable.recordKey eq recordKey)
                        }.singleOrNull()
                if (existing == null && !allowMissing) return@transaction null
                val retainedDeletedAt = maxOf(deletedAt, existing?.get(LogDateCollectionRecordsTable.deletedAt) ?: deletedAt)
                if (existing == null) {
                    LogDateCollectionRecordsTable.insert {
                        it[LogDateCollectionRecordsTable.userId] = userId
                        it[LogDateCollectionRecordsTable.collection] = collection.storageName
                        it[LogDateCollectionRecordsTable.recordKey] = recordKey
                        it[serverVersion] = state.lastVersion
                        it[deleted] = true
                        it[LogDateCollectionRecordsTable.deletedAt] = retainedDeletedAt
                    }
                } else {
                    LogDateCollectionRecordsTable.update({
                        (LogDateCollectionRecordsTable.userId eq userId) and
                            (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                            (LogDateCollectionRecordsTable.recordKey eq recordKey)
                    }) {
                        it[serverVersion] = state.lastVersion
                        it[LogDateCollectionRecordsTable.deleted] = true
                        it[LogDateCollectionRecordsTable.deletedAt] = retainedDeletedAt
                    }
                }
                LogDateCollectionMetadata(recordKey, state.lastVersion, retainedDeletedAt)
            }
        }

    // These rows contain only deletion identity/order, required by long-offline clients.
    override suspend fun purgeAllTombstones(olderThan: Long): LogDateCollectionsPurgeResult =
        LogDateCollectionsPurgeResult(0, 0, 0, olderThan)

    override suspend fun purgeTombstones(
        userId: UUID,
        olderThan: Long,
    ): LogDateCollectionsPurgeResult = LogDateCollectionsPurgeResult(0, 0, 0, olderThan)

    private fun liveCount(
        userId: UUID,
        collection: LogDateCollectionKind,
    ): Int =
        LogDateCollectionRecordsTable
            .selectAll()
            .where {
                (LogDateCollectionRecordsTable.userId eq userId) and
                    (LogDateCollectionRecordsTable.collection eq collection.storageName) and
                    (LogDateCollectionRecordsTable.deleted eq false)
            }.count()
            .toInt()

    private fun nextState(
        userId: UUID,
        preferredRepoDid: AtprotoDid,
    ): LogDateCollectionsState {
        // Create the lock row even for concurrent first writes, then allocate under its lock.
        LogDateCollectionStatesTable.insertIgnore {
            it[LogDateCollectionStatesTable.userId] = userId
            it[repoDid] = preferredRepoDid.toString()
            it[lastVersion] = 0L
            it[updatedAt] = Clock.System.now()
        }
        val existing =
            LogDateCollectionStatesTable
                .selectAll()
                .where { LogDateCollectionStatesTable.userId eq userId }
                .forUpdate()
                .single()
        val repoDid = existing[LogDateCollectionStatesTable.repoDid]
        val version = nextVersion(existing[LogDateCollectionStatesTable.lastVersion])
        LogDateCollectionStatesTable.update({ LogDateCollectionStatesTable.userId eq userId }) {
            it[lastVersion] = version
            it[updatedAt] = Clock.System.now()
        }
        return LogDateCollectionsState(
            repoDid = AtprotoDid.require(repoDid),
            lastVersion = version,
        )
    }

    private fun nextVersion(existingVersion: Long?): Long {
        val candidate = System.currentTimeMillis()
        return if (existingVersion != null && candidate <= existingVersion) existingVersion + 1L else candidate
    }
}

private fun ResultRow.toCollectionsState(): LogDateCollectionsState =
    LogDateCollectionsState(
        repoDid = AtprotoDid.require(this[LogDateCollectionStatesTable.repoDid]),
        lastVersion = this[LogDateCollectionStatesTable.lastVersion],
    )

private fun ResultRow.toCollectionMetadata(): LogDateCollectionMetadata =
    LogDateCollectionMetadata(
        recordKey = this[LogDateCollectionRecordsTable.recordKey],
        version = this[LogDateCollectionRecordsTable.serverVersion],
        deletedAt =
            this[LogDateCollectionRecordsTable.deletedAt]
                ?: this[LogDateCollectionRecordsTable.serverVersion].takeIf { this[LogDateCollectionRecordsTable.deleted] },
    )
