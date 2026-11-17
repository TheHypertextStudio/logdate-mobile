package app.logdate.server.database

import app.logdate.server.logdate.syntheticRepoDid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

/**
 * Deletes an account's journal repositories and its sync index. Run it inside the transaction that
 * deletes the account.
 *
 * Repository rows are keyed by DID rather than by account, so no foreign key removes them. An
 * account's records can sit under its own DID, written through AT Protocol, and under the
 * repository DID its sync state recorded, or under the synthetic DID sync uses for an account
 * without one.
 * Blocks are content-addressed and shared between repositories, so a block is deleted only once
 * no remaining repository links to it.
 */
internal fun deleteAccountRepositories(accountId: UUID) {
    // Read below; SearchDeadCode 0.21.0 misreads these locals as unreachable properties.
    @Suppress(names = ["unused"])
    val repoDids =
        buildSet {
            add(syntheticRepoDid(accountId))
            AccountsTable
                .select(AccountsTable.did)
                .where { AccountsTable.id eq accountId }
                .mapNotNullTo(this) { it[AccountsTable.did] }
            LogDateCollectionStatesTable
                .select(LogDateCollectionStatesTable.repoDid)
                .where { LogDateCollectionStatesTable.userId eq accountId }
                .mapTo(this) { it[LogDateCollectionStatesTable.repoDid] }
        }
    LogDateCollectionRecordsTable.deleteWhere { LogDateCollectionRecordsTable.userId eq accountId }
    LogDateCollectionStatesTable.deleteWhere { LogDateCollectionStatesTable.userId eq accountId }

    // Read below; SearchDeadCode 0.21.0 misreads these locals as unreachable properties.
    @Suppress(names = ["unused"])
    val linkedCids =
        AtprotoRepoBlockLinksTable
            .select(AtprotoRepoBlockLinksTable.cid)
            .where { AtprotoRepoBlockLinksTable.repoDid inList repoDids }
            .mapTo(mutableSetOf()) { it[AtprotoRepoBlockLinksTable.cid] }
    AtprotoRepoHeadsTable.deleteWhere { AtprotoRepoHeadsTable.repoDid inList repoDids }
    AtprotoRepoCommitsTable.deleteWhere { AtprotoRepoCommitsTable.repoDid inList repoDids }
    AtprotoRepoBlockLinksTable.deleteWhere { AtprotoRepoBlockLinksTable.repoDid inList repoDids }
    linkedCids.chunked(CID_BATCH_SIZE).forEach { batch ->
        // Read below; SearchDeadCode 0.21.0 misreads these locals as unreachable properties.
        @Suppress(names = ["unused"])
        val stillLinked =
            AtprotoRepoBlockLinksTable
                .select(AtprotoRepoBlockLinksTable.cid)
                .where { AtprotoRepoBlockLinksTable.cid inList batch }
                .mapTo(mutableSetOf()) { it[AtprotoRepoBlockLinksTable.cid] }

        // Read below; SearchDeadCode 0.21.0 misreads these locals as unreachable properties.
        @Suppress(names = ["unused"])
        val orphaned = batch - stillLinked
        if (orphaned.isNotEmpty()) {
            AtprotoRepoBlocksTable.deleteWhere { AtprotoRepoBlocksTable.cid inList orphaned }
        }
    }
}

/** Limits each `IN` list to well under PostgreSQL's bind-parameter limit. */
private const val CID_BATCH_SIZE = 1_000
