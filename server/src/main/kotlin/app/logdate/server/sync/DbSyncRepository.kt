@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.server.sync

import app.logdate.server.database.requireLegacyCollectionWrite
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.migration.SpaceMigrationConfig
import app.logdate.shared.model.sync.DeviceId
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exposed-based implementation of SyncRepository with user isolation.
 * All queries are scoped by user_id for multi-tenancy.
 */
class DbSyncRepository(
    private val migrationConfig: SpaceMigrationConfig = SpaceMigrationConfig(),
    private val clock: Clock = Clock.System,
) : SyncRepository {
    private fun requireLegacyWrite(userId: UUID) = requireLegacyCollectionWrite(userId, migrationConfig, clock.now())

    // --- Status ---
    override fun status(userId: UUID): SyncStatus =
        transaction {
            SyncStatus(
                contentCount =
                    ContentSyncTable
                        .selectAll()
                        .where { ContentSyncTable.userId eq userId }
                        .count()
                        .toInt(),
                journalCount =
                    JournalSyncTable
                        .selectAll()
                        .where { JournalSyncTable.userId eq userId }
                        .count()
                        .toInt(),
                associationCount =
                    AssociationSyncTable
                        .selectAll()
                        .where { AssociationSyncTable.userId eq userId }
                        .count()
                        .toInt(),
                lastTimestamp = currentTimestamp(),
            )
        }

    // --- Content ---
    override fun upsertContent(
        userId: UUID,
        record: ContentRecord,
    ): ContentRecord =
        transaction {
            requireLegacyWrite(userId)
            val existing =
                ContentSyncTable
                    .selectAll()
                    .where {
                        (ContentSyncTable.id eq record.id) and (ContentSyncTable.userId eq userId)
                    }.forUpdate()
                    .singleOrNull()
            if (existing != null && existing[ContentSyncTable.deleted]) {
                val tombstonedAt = existing[ContentSyncTable.deletedAt] ?: 0L
                throw StaleWriteException(record.id, tombstonedAt)
            }
            val serverVersion = nextVersion(existing?.get(ContentSyncTable.serverVersion))
            val acceptedTranscript =
                record.transcript ?: existing
                    ?.takeIf {
                        it[ContentSyncTable.type] == record.type &&
                            it[ContentSyncTable.mediaUri] == record.mediaUri &&
                            it[ContentSyncTable.durationMs] == record.durationMs
                    }?.get(ContentSyncTable.transcript)
            if (existing == null) {
                ContentSyncTable.insert {
                    it[id] = record.id
                    it[ContentSyncTable.userId] = userId
                    it[type] = record.type
                    it[content] = record.content
                    it[mediaUri] = record.mediaUri
                    it[transcript] = record.transcript
                    it[durationMs] = record.durationMs
                    it[createdAt] = record.createdAt
                    it[lastUpdated] = record.lastUpdated
                    it[ContentSyncTable.serverVersion] = serverVersion
                    it[deviceId] = record.deviceId.value
                    it[deleted] = false
                    it[deletedAt] = null
                }
            } else {
                ContentSyncTable.update({ (ContentSyncTable.id eq record.id) and (ContentSyncTable.userId eq userId) }) {
                    it[type] = record.type
                    it[content] = record.content
                    it[mediaUri] = record.mediaUri
                    it[transcript] = acceptedTranscript
                    it[durationMs] = record.durationMs
                    it[lastUpdated] = record.lastUpdated
                    it[ContentSyncTable.serverVersion] = serverVersion
                    it[deviceId] = record.deviceId.value
                    it[deleted] = false
                    it[deletedAt] = null
                }
            }
            record.copy(
                serverVersion = serverVersion,
                lastUpdated = currentTimestamp(),
                transcript = acceptedTranscript,
            )
        }

    override fun getContent(
        userId: UUID,
        id: String,
    ): ContentRecord? =
        transaction {
            ContentSyncTable
                .selectAll()
                .where {
                    (ContentSyncTable.id eq id) and
                        (ContentSyncTable.userId eq userId) and
                        (ContentSyncTable.deleted eq false)
                }.singleOrNull()
                ?.toContentRecord()
        }

    override fun deleteContent(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) {
        transaction {
            requireLegacyWrite(userId)
            val existing =
                ContentSyncTable
                    .selectAll()
                    .where { (ContentSyncTable.id eq id) and (ContentSyncTable.userId eq userId) }
                    .forUpdate()
                    .singleOrNull()
            val existingVersion = existing?.get(ContentSyncTable.serverVersion)
            val retainedDeletedAt = maxOf(deletedAt, existing?.get(ContentSyncTable.deletedAt) ?: deletedAt)
            val newVersion = nextVersion(existingVersion)
            if (existingVersion == null) {
                ContentSyncTable.insert {
                    it[ContentSyncTable.id] = id
                    it[ContentSyncTable.userId] = userId
                    it[type] = "TEXT"
                    it[createdAt] = retainedDeletedAt
                    it[lastUpdated] = retainedDeletedAt
                    it[ContentSyncTable.serverVersion] = newVersion
                    it[deviceId] = DeviceId.UNKNOWN.value
                    it[deleted] = true
                    it[ContentSyncTable.deletedAt] = retainedDeletedAt
                }
            } else {
                ContentSyncTable.update({ (ContentSyncTable.id eq id) and (ContentSyncTable.userId eq userId) }) {
                    it[deleted] = true
                    it[ContentSyncTable.deletedAt] = retainedDeletedAt
                    it[lastUpdated] = retainedDeletedAt
                    it[ContentSyncTable.serverVersion] = newVersion
                }
            }
        }
    }

    override fun contentChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): ChangeSet<ContentRecord, ContentDeletionMarker> =
        transaction {
            val changeRows =
                ContentSyncTable
                    .selectAll()
                    .where {
                        (ContentSyncTable.userId eq userId) and
                            (ContentSyncTable.deleted eq false) and
                            (ContentSyncTable.serverVersion greater since)
                    }.orderBy(ContentSyncTable.serverVersion to SortOrder.ASC)
                    .limit(changePageReadSize(limit))
                    .toList()
            val hasMoreChanges = changeRows.size > limit
            val changes = changeRows.take(limit).map { it.toContentRecord() }
            val changeVersionMax = changes.maxOfOrNull { it.serverVersion }

            val deletionRows =
                ContentSyncTable
                    .selectAll()
                    .where {
                        (ContentSyncTable.userId eq userId) and
                            (ContentSyncTable.deleted eq true) and
                            (ContentSyncTable.serverVersion greater since)
                    }.orderBy(ContentSyncTable.serverVersion to SortOrder.ASC)
                    .limit(changePageReadSize(limit))
                    .toList()
            val hasMoreDeletions = deletionRows.size > limit
            val deletions =
                deletionRows.take(limit).map { row ->
                    ContentDeletionMarker(
                        row[ContentSyncTable.id],
                        row[ContentSyncTable.deletedAt] ?: row[ContentSyncTable.lastUpdated],
                        row[ContentSyncTable.serverVersion],
                    )
                }
            val deletionVersionMax =
                deletionRows.take(limit).maxOfOrNull { it[ContentSyncTable.serverVersion] }

            val lastTimestamp =
                listOfNotNull(changeVersionMax, deletionVersionMax).maxOrNull() ?: since

            ChangeSet(changes, deletions, lastTimestamp, hasMoreChanges || hasMoreDeletions)
        }

    // --- Journals ---
    override fun upsertJournal(
        userId: UUID,
        record: JournalRecord,
    ): JournalRecord = dbUpsertJournal(userId, record, migrationConfig, clock)

    override fun getJournal(
        userId: UUID,
        id: String,
    ): JournalRecord? = dbGetJournal(userId, id)

    override fun deleteJournal(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) = dbDeleteJournal(userId, id, deletedAt, migrationConfig, clock)

    override fun journalChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): ChangeSet<JournalRecord, JournalDeletionMarker> = dbJournalChanges(userId, since, limit)

    // --- Associations ---
    override fun upsertAssociations(
        userId: UUID,
        records: List<AssociationRecord>,
    ) = dbUpsertAssociations(userId, records, migrationConfig, clock)

    override fun deleteAssociations(
        userId: UUID,
        keys: List<AssociationKey>,
        deletedAt: Long,
    ) = dbDeleteAssociations(userId, keys, deletedAt, migrationConfig, clock)

    override fun associationChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): ChangeSet<AssociationRecord, AssociationDeletionMarker> = dbAssociationChanges(userId, since, limit)

    override fun upsertMedia(
        userId: UUID,
        record: MediaRecord,
    ): MediaRecord = dbUpsertMedia(userId, record)

    override fun getMedia(
        userId: UUID,
        mediaId: String,
    ): MediaRecord? = dbGetMedia(userId, mediaId)

    override fun deleteMedia(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
    ) = dbDeleteMedia(userId, mediaId, deletedAt)

    override fun listAllMediaForUser(userId: UUID): List<MediaRecord> = dbListAllMediaForUser(userId)

    override fun createBackupRecord(
        userId: UUID,
        record: BackupRecord,
    ): BackupRecord = dbCreateBackupRecord(userId, record)

    override fun getBackupRecord(
        userId: UUID,
        id: UUID,
    ): BackupRecord? = dbGetBackupRecord(userId, id)

    override fun listBackups(userId: UUID): List<BackupRecord> = dbListBackups(userId)

    override fun deleteBackup(
        userId: UUID,
        id: UUID,
    ) = dbDeleteBackup(userId, id)

    override fun purgeTombstones(
        userId: UUID,
        olderThan: Long,
    ): SyncPurgeResult =
        transaction {
            requireLegacyWrite(userId)
            val contentPurged =
                ContentSyncTable.update({
                    (ContentSyncTable.userId eq userId) and
                        (ContentSyncTable.deleted eq true) and
                        (ContentSyncTable.deletedAt less olderThan) and
                        (
                            ContentSyncTable.content.isNotNull() or ContentSyncTable.mediaUri.isNotNull() or
                                ContentSyncTable.transcript.isNotNull() or ContentSyncTable.durationMs.isNotNull()
                        )
                }) {
                    it[content] = null
                    it[mediaUri] = null
                    it[transcript] = null
                    it[durationMs] = null
                }
            val journalPurged =
                JournalSyncTable.update({
                    (JournalSyncTable.userId eq userId) and
                        (JournalSyncTable.deleted eq true) and
                        (JournalSyncTable.deletedAt less olderThan) and
                        ((JournalSyncTable.title neq "") or (JournalSyncTable.description neq ""))
                }) {
                    it[title] = ""
                    it[description] = ""
                }
            val associationPurged = 0
            val mediaPurged =
                MediaSyncTable.update({
                    (MediaSyncTable.userId eq userId) and
                        (MediaSyncTable.deleted eq true) and
                        (MediaSyncTable.deletedAt less olderThan) and
                        (MediaSyncTable.data neq byteArrayOf())
                }) {
                    it[data] = byteArrayOf()
                }

            SyncPurgeResult(
                contentPurged = contentPurged,
                journalPurged = journalPurged,
                associationPurged = associationPurged,
                mediaPurged = mediaPurged,
                cutoff = olderThan,
            )
        }

    override fun purgeTombstonesOlderThan(olderThan: Long): SyncPurgeResult {
        // Discover owners without taking data-row locks; each mutation locks its account first.
        val owners =
            transaction {
                buildSet {
                    addAll(
                        ContentSyncTable
                            .select(ContentSyncTable.userId)
                            .where {
                                (ContentSyncTable.deleted eq true) and (ContentSyncTable.deletedAt less olderThan)
                            }.map { it[ContentSyncTable.userId] },
                    )
                    addAll(
                        JournalSyncTable
                            .select(JournalSyncTable.userId)
                            .where {
                                (JournalSyncTable.deleted eq true) and (JournalSyncTable.deletedAt less olderThan)
                            }.map { it[JournalSyncTable.userId] },
                    )
                    addAll(
                        MediaSyncTable
                            .select(MediaSyncTable.userId)
                            .where {
                                (MediaSyncTable.deleted eq true) and (MediaSyncTable.deletedAt less olderThan)
                            }.map { it[MediaSyncTable.userId] },
                    )
                }.sorted()
            }
        return owners.fold(SyncPurgeResult(0, 0, 0, 0, olderThan)) { total, owner ->
            val purged =
                try {
                    purgeTombstones(owner, olderThan)
                } catch (_: IdentityWriteRefusedException) {
                    // Held, retired and newer-epoch inventories remain available for reconciliation.
                    SyncPurgeResult(0, 0, 0, 0, olderThan)
                }
            total.copy(
                contentPurged = total.contentPurged + purged.contentPurged,
                journalPurged = total.journalPurged + purged.journalPurged,
                associationPurged = total.associationPurged + purged.associationPurged,
                mediaPurged = total.mediaPurged + purged.mediaPurged,
            )
        }
    }

    // --- Helpers ---
}
