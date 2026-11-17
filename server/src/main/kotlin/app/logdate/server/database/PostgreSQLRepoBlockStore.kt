package app.logdate.server.database

import app.logdate.server.logdate.RepoWriteTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.RepoBlock
import studio.hypertext.atproto.repo.RepoBlockStore
import studio.hypertext.atproto.repo.RepoCommit
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.SignedRepoCommit
import kotlin.time.Clock

/**
 * Exposed-backed repo block store for the server's durable AT Protocol mirror.
 *
 * Repo ownership is keyed by DID string rather than a foreign-key account id so the shared repo
 * library can keep speaking pure AT Protocol identifiers.
 */
class PostgreSQLRepoBlockStore :
    RepoBlockStore,
    RepoWriteTransaction {
    private val blockCache = RepoBlockReadCache()

    override suspend fun <T> write(action: suspend () -> T): T =
        try {
            withContext(Dispatchers.IO) {
                suspendTransaction {
                    maxAttempts = 1
                    action()
                }
            }
        } catch (failure: Throwable) {
            blockCache.clear()
            throw failure
        }

    // Every method here is blocking JDBC. Run on the caller's thread it held a Ktor request
    // worker for the whole query, and a one-CPU instance sizes that pool at one - so a few
    // in-flight repo writes left nothing to answer /health with, and the liveness probe killed
    // the container. The work still blocks; it no longer blocks a thread whose job is accepting
    // requests.

    override suspend fun readHead(repo: AtprotoDid): Result<RepoHead?> =
        runCatching {
            withContext(Dispatchers.IO) {
                transaction {
                    AtprotoRepoHeadsTable
                        .selectAll()
                        .where { AtprotoRepoHeadsTable.repoDid eq repo.toString() }
                        .singleOrNull()
                        ?.toRepoHead()
                }
            }
        }

    override suspend fun writeHead(head: RepoHead): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                transaction {
                    val repoDid = head.repo.toString()
                    val existing =
                        AtprotoRepoHeadsTable
                            .selectAll()
                            .where { AtprotoRepoHeadsTable.repoDid eq repoDid }
                            .singleOrNull()

                    if (existing == null) {
                        AtprotoRepoHeadsTable.insert {
                            it[AtprotoRepoHeadsTable.repoDid] = repoDid
                            it[rootCid] = head.root.toString()
                            it[commitCid] = head.commitCid.toString()
                            it[revision] = head.revision
                            it[updatedAt] = Clock.System.now()
                        }
                    } else {
                        AtprotoRepoHeadsTable.update({ AtprotoRepoHeadsTable.repoDid eq repoDid }) {
                            it[rootCid] = head.root.toString()
                            it[commitCid] = head.commitCid.toString()
                            it[revision] = head.revision
                            it[updatedAt] = Clock.System.now()
                        }
                    }
                }
            }
        }

    override suspend fun compareAndSwapHead(
        head: RepoHead,
        expectedRevision: Long?,
    ): Result<Boolean> =
        runCatching {
            withContext(Dispatchers.IO) {
                transaction {
                    val repoDid = head.repo.toString()
                    if (expectedRevision == null) {
                        AtprotoRepoHeadsTable
                            .insertIgnore {
                                it[AtprotoRepoHeadsTable.repoDid] = repoDid
                                it[rootCid] = head.root.toString()
                                it[commitCid] = head.commitCid.toString()
                                it[revision] = head.revision
                                it[updatedAt] = Clock.System.now()
                            }.insertedCount == 1
                    } else {
                        val updated =
                            AtprotoRepoHeadsTable.update({
                                (AtprotoRepoHeadsTable.repoDid eq repoDid) and
                                    (AtprotoRepoHeadsTable.revision eq expectedRevision)
                            }) {
                                it[rootCid] = head.root.toString()
                                it[commitCid] = head.commitCid.toString()
                                it[revision] = head.revision
                                it[updatedAt] = Clock.System.now()
                            }
                        updated > 0
                    }
                }
            }
        }

    override suspend fun readBlock(cid: Cid): Result<RepoBlock?> =
        blockCache.read(cid) {
            runCatching {
                withContext(Dispatchers.IO) {
                    transaction {
                        AtprotoRepoBlocksTable
                            .selectAll()
                            .where { AtprotoRepoBlocksTable.cid eq cid.toString() }
                            .singleOrNull()
                            ?.toRepoBlock()
                    }
                }
            }
        }

    override suspend fun readBlocks(cids: List<Cid>): Result<List<RepoBlock?>> =
        blockCache.readMany(cids) { missing ->
            runCatching {
                withContext(Dispatchers.IO) {
                    transaction {
                        AtprotoRepoBlocksTable
                            .selectAll()
                            .where { AtprotoRepoBlocksTable.cid inList missing.map(Cid::toString) }
                            .map { it.toRepoBlock() }
                            .associateBy(RepoBlock::cid)
                    }
                }
            }
        }

    override suspend fun writeBlock(
        repo: AtprotoDid,
        block: RepoBlock,
    ): Result<Unit> {
        val generation = blockCache.generation()
        return runCatching {
            withContext(Dispatchers.IO) {
                transaction {
                    val cidValue = block.cid.toString()
                    AtprotoRepoBlocksTable.insertIgnore {
                        it[cid] = cidValue
                        it[bytes] = block.bytes
                    }
                    AtprotoRepoBlockLinksTable.insertIgnore {
                        it[repoDid] = repo.toString()
                        it[cid] = cidValue
                    }
                    Unit
                }
            }
        }.onSuccess { blockCache.remember(block, generation) }
    }

    override suspend fun clearRepo(repo: AtprotoDid): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                transaction {
                    val repoDid = repo.toString()
                    AtprotoRepoHeadsTable.deleteWhere { AtprotoRepoHeadsTable.repoDid eq repoDid }
                    AtprotoRepoBlockLinksTable.deleteWhere { AtprotoRepoBlockLinksTable.repoDid eq repoDid }
                    AtprotoRepoCommitsTable.deleteWhere { AtprotoRepoCommitsTable.repoDid eq repoDid }
                    Unit
                }
            }
        }.onSuccess { blockCache.clear() }

    override suspend fun listBlocks(repo: AtprotoDid): Result<List<RepoBlock>> =
        runCatching {
            withContext(Dispatchers.IO) {
                transaction {
                    AtprotoRepoBlockLinksTable
                        .innerJoin(AtprotoRepoBlocksTable)
                        .selectAll()
                        .where { AtprotoRepoBlockLinksTable.repoDid eq repo.toString() }
                        .orderBy(AtprotoRepoBlocksTable.cid, SortOrder.ASC)
                        .map { row ->
                            RepoBlock(
                                cid = Cid.require(row[AtprotoRepoBlocksTable.cid]),
                                bytes = row[AtprotoRepoBlocksTable.bytes],
                            )
                        }
                }
            }
        }

    override suspend fun appendCommit(
        repo: AtprotoDid,
        commit: SignedRepoCommit,
    ): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                transaction {
                    AtprotoRepoCommitsTable.upsert(AtprotoRepoCommitsTable.repoDid, AtprotoRepoCommitsTable.revision) {
                        it[repoDid] = repo.toString()
                        it[revision] = commit.commit.revision
                        it[cid] = commit.cid.toString()
                        it[rootCid] = commit.commit.root.toString()
                        it[prevCid] = commit.commit.prev?.toString()
                        it[createdAtEpochMillis] = commit.commit.createdAtEpochMillis
                        it[recordCount] = commit.commit.recordCount
                        it[signature] = commit.signature
                    }
                }
            }
        }

    override suspend fun listCommits(repo: AtprotoDid): Result<List<SignedRepoCommit>> =
        runCatching {
            withContext(Dispatchers.IO) {
                transaction {
                    AtprotoRepoCommitsTable
                        .selectAll()
                        .where { AtprotoRepoCommitsTable.repoDid eq repo.toString() }
                        .orderBy(AtprotoRepoCommitsTable.revision, SortOrder.ASC)
                        .map(ResultRow::toSignedRepoCommit)
                }
            }
        }
}

private fun ResultRow.toRepoHead(): RepoHead =
    RepoHead(
        repo = AtprotoDid.require(this[AtprotoRepoHeadsTable.repoDid]),
        root = Cid.require(this[AtprotoRepoHeadsTable.rootCid]),
        commitCid = Cid.require(this[AtprotoRepoHeadsTable.commitCid]),
        revision = this[AtprotoRepoHeadsTable.revision],
    )

private fun ResultRow.toRepoBlock(): RepoBlock =
    RepoBlock(
        cid = Cid.require(this[AtprotoRepoBlocksTable.cid]),
        bytes = this[AtprotoRepoBlocksTable.bytes],
    )

private fun ResultRow.toSignedRepoCommit(): SignedRepoCommit =
    SignedRepoCommit(
        cid = Cid.require(this[AtprotoRepoCommitsTable.cid]),
        commit =
            RepoCommit(
                repo = AtprotoDid.require(this[AtprotoRepoCommitsTable.repoDid]),
                root = Cid.require(this[AtprotoRepoCommitsTable.rootCid]),
                prev = this[AtprotoRepoCommitsTable.prevCid]?.let(Cid::require),
                revision = this[AtprotoRepoCommitsTable.revision],
                createdAtEpochMillis = this[AtprotoRepoCommitsTable.createdAtEpochMillis],
                recordCount = this[AtprotoRepoCommitsTable.recordCount],
            ),
        signature = this[AtprotoRepoCommitsTable.signature],
    )
