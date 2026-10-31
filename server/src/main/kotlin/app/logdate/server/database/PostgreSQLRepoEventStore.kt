package app.logdate.server.database

import app.logdate.server.atproto.firehose.RepoEventDraft
import app.logdate.server.atproto.firehose.RepoEventKind
import app.logdate.server.atproto.firehose.RepoEventStore
import app.logdate.server.atproto.firehose.StoredRepoEvent
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.time.Instant

/**
 * The repository event log in PostgreSQL.
 *
 * An append locks the one row of [AtprotoRepoEventSequenceTable] until its transaction ends, so a
 * second append waits for the first to commit and events become visible in numbered order. A reader
 * that has seen event N therefore never finds an event below N appear later. The lock stands for the
 * whole transaction, and a repository write holds its transaction open until its records and its
 * event are stored together, so one slow write delays announcements for other repositories; public
 * repositories are small and rarely written, which keeps that short.
 */
class PostgreSQLRepoEventStore : RepoEventStore {
    override suspend fun append(draft: RepoEventDraft): StoredRepoEvent =
        dbQuery {
            AtprotoRepoEventSequenceTable.insertIgnore {
                it[id] = SEQUENCE_ROW
                it[lastSeq] = 0L
            }
            val last =
                AtprotoRepoEventSequenceTable
                    .select(AtprotoRepoEventSequenceTable.lastSeq)
                    .where { AtprotoRepoEventSequenceTable.id eq SEQUENCE_ROW }
                    .forUpdate()
                    .single()[AtprotoRepoEventSequenceTable.lastSeq]
            val seq = last + 1
            val frame = draft.encode(seq)
            AtprotoRepoEventSequenceTable.update({ AtprotoRepoEventSequenceTable.id eq SEQUENCE_ROW }) { it[lastSeq] = seq }
            AtprotoRepoAnnouncedTable.insertIgnore {
                it[repoDid] = draft.repo.toString()
                it[firstAnnouncedAt] = draft.time
            }
            AtprotoRepoEventsTable.insert {
                it[AtprotoRepoEventsTable.seq] = seq
                it[repoDid] = draft.repo.toString()
                it[eventType] = draft.kind.code
                it[revision] = draft.revision.takeIf { draft.kind.carriesRevision }
                it[AtprotoRepoEventsTable.frame] = frame
                it[createdAt] = draft.time
            }
            StoredRepoEvent(seq, draft.repo, draft.kind, frame)
        }

    override suspend fun read(
        afterSeq: Long,
        limit: Int,
    ): List<StoredRepoEvent> =
        dbQuery {
            AtprotoRepoEventsTable
                .selectAll()
                .where { AtprotoRepoEventsTable.seq greater afterSeq }
                .orderBy(AtprotoRepoEventsTable.seq, SortOrder.ASC)
                .limit(limit)
                .map(ResultRow::toStoredEvent)
        }

    override suspend fun latestSeq(): Long =
        dbQuery {
            AtprotoRepoEventSequenceTable
                .select(AtprotoRepoEventSequenceTable.lastSeq)
                .where { AtprotoRepoEventSequenceTable.id eq SEQUENCE_ROW }
                .singleOrNull()
                ?.get(AtprotoRepoEventSequenceTable.lastSeq)
                ?: 0L
        }

    override suspend fun earliestSeq(): Long? =
        dbQuery {
            AtprotoRepoEventsTable
                .select(AtprotoRepoEventsTable.seq)
                .orderBy(AtprotoRepoEventsTable.seq, SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.get(AtprotoRepoEventsTable.seq)
        }

    override suspend fun lastRevision(repo: AtprotoDid): Long? =
        dbQuery {
            AtprotoRepoEventsTable
                .select(AtprotoRepoEventsTable.revision)
                .where {
                    (AtprotoRepoEventsTable.repoDid eq repo.toString()) and
                        (AtprotoRepoEventsTable.eventType inList REVISION_EVENT_TYPES)
                }.orderBy(AtprotoRepoEventsTable.seq, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(AtprotoRepoEventsTable.revision)
        }

    override suspend fun hasEvents(repo: AtprotoDid): Boolean =
        dbQuery {
            !AtprotoRepoAnnouncedTable
                .select(AtprotoRepoAnnouncedTable.repoDid)
                .where { AtprotoRepoAnnouncedTable.repoDid eq repo.toString() }
                .limit(1)
                .empty()
        }

    override suspend fun deleteBefore(time: Instant): Int = dbQuery { AtprotoRepoEventsTable.deleteWhere { createdAt less time } }

    private companion object {
        const val SEQUENCE_ROW: Short = 1
        val REVISION_EVENT_TYPES = RepoEventKind.entries.filter { it.carriesRevision }.map { it.code }
    }
}

private fun ResultRow.toStoredEvent(): StoredRepoEvent =
    StoredRepoEvent(
        seq = this[AtprotoRepoEventsTable.seq],
        repo = AtprotoDid.require(this[AtprotoRepoEventsTable.repoDid]),
        kind = RepoEventKind.fromCode(this[AtprotoRepoEventsTable.eventType]),
        frame = this[AtprotoRepoEventsTable.frame],
    )
