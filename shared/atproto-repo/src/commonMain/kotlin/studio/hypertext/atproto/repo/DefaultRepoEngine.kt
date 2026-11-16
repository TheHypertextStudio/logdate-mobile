package studio.hypertext.atproto.repo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid
import kotlin.time.Clock

/**
 * Default repo engine implementation backed by a [RepoBlockStore].
 *
 * @property blockStore Underlying block and head store for repository commits and MST nodes.
 * @property clock Clock used to mint monotonic commit revision TIDs.
 * @property signer Commit signer used to sign DAG-CBOR commit payloads.
 * @property onClockSkew Optional callback invoked when a previous commit's revision is ahead of
 *   [clock] by more than five seconds (`aheadMicros`).
 */
public class DefaultRepoEngine(
    private val blockStore: RepoBlockStore,
    private val clock: Clock = Clock.System,
    private val signer: RepoCommitSigner = DigestRepoCommitSigner,
    private val onClockSkew: ((repo: AtprotoDid, aheadMicros: Long) -> Unit)? = null,
) : RepoEngine {
    override suspend fun getRecord(recordId: RepoRecordId): Result<RepoRecord?> =
        runCatching {
            val snapshot = loadSnapshot(recordId.repo)
            val cid = snapshot.tree.get(recordId.collection, recordId.recordKey) ?: return@runCatching null
            val block = blockStore.readBlock(cid).getOrThrow() ?: return@runCatching null
            RepoRecord(
                uri = recordId.uri,
                cid = cid.toString(),
                value = DagCborCodec.decode(block.bytes).jsonObject,
            )
        }

    override suspend fun getRecords(recordIds: List<RepoRecordId>): Result<List<RepoRecord?>> =
        runCatching {
            val snapshots = mutableMapOf<AtprotoDid, RepoSnapshot>()
            val references =
                recordIds.map { recordId ->
                    val snapshot = snapshots.getOrPut(recordId.repo) { loadSnapshot(recordId.repo) }
                    recordId to snapshot.tree.get(recordId.collection, recordId.recordKey)
                }
            val cids = references.mapNotNull { it.second }.distinct()
            if (cids.isEmpty()) return@runCatching references.map { null }
            val loaded = blockStore.readBlocks(cids).getOrThrow()
            check(loaded.size == cids.size) { "Block store returned an incomplete batch" }
            val blocks = cids.zip(loaded).toMap()
            references.map { (recordId, cid) ->
                val block = blocks[cid] ?: return@map null
                RepoRecord(
                    uri = recordId.uri,
                    cid = block.cid.toString(),
                    value = DagCborCodec.decode(block.bytes).jsonObject,
                )
            }
        }

    override suspend fun listRecords(
        repo: AtprotoDid,
        collection: Nsid,
        limit: Int,
        cursor: String?,
        reverse: Boolean,
    ): Result<RepoListPage> =
        runCatching {
            val snapshot = loadSnapshot(repo)
            val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
            val sortedEntries =
                snapshot.tree
                    .entries(collection)
                    .filter { entry ->
                        cursor == null ||
                            if (reverse) {
                                entry.recordKey.toString() < cursor
                            } else {
                                entry.recordKey.toString() > cursor
                            }
                    }.let { entries ->
                        if (reverse) {
                            entries.sortedByDescending { it.recordKey.toString() }
                        } else {
                            entries.sortedBy { it.recordKey.toString() }
                        }
                    }
            val pageEntries = sortedEntries.take(safeLimit)
            RepoListPage(
                records =
                    pageEntries.map { entry ->
                        RepoRecord(
                            uri =
                                RepoRecordId(
                                    repo = repo,
                                    collection = collection,
                                    recordKey = entry.recordKey,
                                ).uri,
                            cid = entry.cid.toString(),
                            value = DagCborCodec.decode(requireNotNull(blockStore.readBlock(entry.cid).getOrThrow()).bytes).jsonObject,
                        )
                    },
                cursor =
                    pageEntries
                        .lastOrNull()
                        ?.recordKey
                        ?.toString()
                        ?.takeIf { sortedEntries.size > pageEntries.size },
            )
        }

    override suspend fun createRecord(
        repo: AtprotoDid,
        collection: Nsid,
        value: JsonObject,
        recordKey: RecordKey?,
    ): Result<RepoWriteResult> {
        val resolvedRecordKey = recordKey ?: RecordKey.require("record-${clock.now().epochSeconds}")
        return putRecord(
            recordId =
                RepoRecordId(
                    repo = repo,
                    collection = collection,
                    recordKey = resolvedRecordKey,
                ),
            value = value,
        )
    }

    override suspend fun putRecord(
        recordId: RepoRecordId,
        value: JsonObject,
        swapRecord: String?,
    ): Result<RepoWriteResult> =
        runCatching {
            retryingOnHeadConflict {
                putRecordOnce(recordId, value, swapRecord)
            }
        }

    /** Atomically creates a record only while its key is absent, including across head retries. */
    public suspend fun putRecordIfAbsent(
        recordId: RepoRecordId,
        value: JsonObject,
    ): Result<RepoWriteResult> =
        runCatching {
            retryingOnHeadConflict {
                putRecordOnce(recordId, value, swapRecord = null, expectedAbsent = true)
            }
        }

    private suspend fun putRecordOnce(
        recordId: RepoRecordId,
        value: JsonObject,
        swapRecord: String?,
        expectedAbsent: Boolean = false,
    ): RepoWriteResult =
        run {
            val snapshot = loadSnapshot(recordId.repo)
            val previousCid = snapshot.tree.get(recordId.collection, recordId.recordKey)?.toString()
            if (expectedAbsent && previousCid != null) {
                throw InvalidSwapException(expectedCid = previousCid, providedCid = "<absent>")
            }
            if (swapRecord != null && previousCid != swapRecord) {
                throw InvalidSwapException(expectedCid = previousCid, providedCid = swapRecord)
            }

            val recordBytes = DagCborCodec.encode(value)
            val recordCid = Cid.sha256(DAG_CBOR_CODEC, recordBytes)
            blockStore.writeBlock(recordId.repo, RepoBlock(recordCid, recordBytes)).getOrThrow()

            val updatedTree = snapshot.tree.put(recordId.collection, recordId.recordKey, recordCid)
            persistSnapshot(recordId.repo, updatedTree, snapshot.head, snapshot.tree)
            RepoWriteResult(
                uri = recordId.uri,
                cid = recordCid.toString(),
                validationStatus = RepoValidationStatus.UNKNOWN,
            )
        }

    override suspend fun putRecords(records: List<BatchRecordWrite>): Result<List<RepoWriteResult>> =
        runCatching {
            if (records.isEmpty()) return@runCatching emptyList()
            val repo = records.first().recordId.repo
            require(records.all { it.recordId.repo == repo }) {
                "putRecords writes one repo at a time; got records for more than one"
            }
            retryingOnHeadConflict {
                putRecordsOnce(repo, records)
            }
        }

    private suspend fun putRecordsOnce(
        repo: AtprotoDid,
        records: List<BatchRecordWrite>,
    ): List<RepoWriteResult> {
        // The tree is read once and persisted once, however many records are in the batch; the
        // per-record work is a block write and a tree put.
        val snapshot = loadSnapshot(repo)
        var tree = snapshot.tree

        // Check every swapRecord precondition against the snapshot this batch is building on
        // before writing anything. A batch is one atomic commit, so a CAS mismatch on record N
        // must fail the whole batch rather than leave the records before it committed and the
        // rest missing - there would be no way back from that half-applied state.
        for (record in records) {
            val expectedSwap = record.swapRecord ?: continue
            val previousCid = tree.get(record.recordId.collection, record.recordId.recordKey)?.toString()
            if (previousCid != expectedSwap) {
                throw InvalidSwapException(expectedCid = previousCid, providedCid = expectedSwap)
            }
        }

        val results = mutableListOf<RepoWriteResult>()
        for (record in records) {
            val recordBytes = DagCborCodec.encode(record.value)
            val recordCid = Cid.sha256(DAG_CBOR_CODEC, recordBytes)
            blockStore.writeBlock(repo, RepoBlock(recordCid, recordBytes)).getOrThrow()
            tree = tree.put(record.recordId.collection, record.recordId.recordKey, recordCid)
            results +=
                RepoWriteResult(
                    uri = record.recordId.uri,
                    cid = recordCid.toString(),
                    validationStatus = RepoValidationStatus.UNKNOWN,
                )
        }

        persistSnapshot(repo, tree, snapshot.head, snapshot.tree)
        return results
    }

    override suspend fun deleteRecord(
        recordId: RepoRecordId,
        swapRecord: String?,
    ): Result<Boolean> =
        runCatching {
            retryingOnHeadConflict {
                deleteRecordOnce(recordId, swapRecord)
            }
        }

    private suspend fun deleteRecordOnce(
        recordId: RepoRecordId,
        swapRecord: String?,
    ): Boolean =
        run {
            val snapshot = loadSnapshot(recordId.repo)
            val previousCid = snapshot.tree.get(recordId.collection, recordId.recordKey)?.toString() ?: return false
            if (swapRecord != null && previousCid != swapRecord) {
                throw InvalidSwapException(expectedCid = previousCid, providedCid = swapRecord)
            }
            val updatedTree = snapshot.tree.remove(recordId.collection, recordId.recordKey)
            persistSnapshot(recordId.repo, updatedTree, snapshot.head, snapshot.tree)
            true
        }

    /**
     * Reruns [write] from a fresh snapshot when another writer moved the head underneath it.
     *
     * The whole read-modify-write has to be repeated, not just the head swap: the tree this
     * attempt built no longer contains what the winner wrote.
     */
    private suspend fun <T> retryingOnHeadConflict(write: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return write()
            } catch (conflict: RepoHeadConflictException) {
                attempt++
                if (attempt >= MAX_HEAD_CONFLICT_RETRIES) {
                    throw conflict
                }
            }
        }
    }

    override suspend fun loadHead(repo: AtprotoDid): Result<RepoHead?> = blockStore.readHead(repo)

    override suspend fun listCommits(
        repo: AtprotoDid,
        limit: Int,
    ): Result<List<SignedRepoCommit>> =
        blockStore
            .listCommits(repo)
            .mapCatching { commits -> commits.takeLast(limit.coerceAtLeast(1)).reversed() }

    override suspend fun export(
        repo: AtprotoDid,
        since: Tid?,
    ): Result<RepoExport> =
        runCatching {
            val snapshot = loadSnapshot(repo)
            val head = requireNotNull(snapshot.head) { "Unknown repo: $repo" }
            val storedCommits = blockStore.listCommits(repo).getOrThrow()
            val commits =
                if (storedCommits.none { it.cid == head.commitCid }) {
                    val block =
                        requireNotNull(blockStore.readBlock(head.commitCid).getOrThrow()) { "Missing head commit ${head.commitCid}" }
                    val recovered = requireNotNull(CarCodec.decodeSignedCommitBlock(block)) { "Invalid head commit ${head.commitCid}" }
                    (storedCommits + recovered).sortedBy { it.commit.revision }
                } else {
                    storedCommits
                }
            val exportCommits =
                commits.filter { commit ->
                    since == null || commit.commit.revision > since.toLong()
                }
            val currentBlocks = reachableBlocks(repo, snapshot.tree, head.commitCid)
            val previousBlockIds =
                if (since == null) {
                    emptySet()
                } else {
                    val previousCommit = commits.lastOrNull { commit -> commit.commit.revision == since.toLong() }
                    previousCommit?.let { commit ->
                        val previousTree = MerkleSearchTree.fromBlocks(commit.commit.root) { cid -> blockStore.readBlock(cid).getOrThrow() }
                        reachableBlocks(repo, previousTree, commit.cid).mapTo(linkedSetOf(), RepoBlock::cid)
                    } ?: emptySet()
                }
            val blocks = linkedMapOf<Cid, RepoBlock>()
            currentBlocks
                .filterNot { block -> block.cid in previousBlockIds }
                .forEach { block -> blocks[block.cid] = block }
            // Commit blocks travel as stored. Rebuilding them from the commit rows would only give
            // the same bytes -- and so the same CIDs -- if the encoding had never changed.
            blockStore
                .readBlocks(exportCommits.map(SignedRepoCommit::cid))
                .getOrThrow()
                .filterNotNull()
                .forEach { block -> blocks.getOrPut(block.cid) { block } }
            RepoExport(
                repo = repo,
                head = head,
                commits = exportCommits,
                blocks = blocks.values.toList(),
            )
        }

    override suspend fun import(export: RepoExport): Result<RepoHead> =
        runCatching {
            // Everything is checked before anything is stored, so a rejected import leaves no trace.
            val head = export.head
            require(head.repo == export.repo) { "Head belongs to ${head.repo}, not ${export.repo}" }
            export.blocks.forEach { block ->
                require(block.cid.isCidFor(block.bytes)) { "Block ${block.cid} does not hash to its CID" }
            }
            val providedBlocks = export.blocks.associateBy(RepoBlock::cid)
            val headCommit =
                requireNotNull(export.commits.firstOrNull { it.cid == head.commitCid }) {
                    "Head commit ${head.commitCid} is not among the exported commits"
                }
            require(
                headCommit.commit.repo == export.repo &&
                    headCommit.commit.root == head.root &&
                    headCommit.commit.revision == head.revision,
            ) { "Head ${head.commitCid} does not match its commit" }
            val signedCommits =
                export.commits.associate { commit ->
                    val block =
                        requireNotNull(providedBlocks[commit.cid] ?: blockStore.readBlock(commit.cid).getOrThrow()) {
                            "Commit block ${commit.cid} is missing"
                        }
                    commit.cid to validateImportedCommit(block, commit, export.repo, head.commitCid)
                }
            // Only the head has to verify against the account's current key: older commits may be
            // signed by a key the account has since rotated away from. The signature covers the
            // stored bytes without `sig`, whatever encoding they were written with.
            val unsignedHead = DagCborCodec.encode(JsonObject(signedCommits.getValue(headCommit.cid) - COMMIT_SIGNATURE_FIELD))
            require(
                signer.verify(headCommit.commit, unsignedHead, headCommit.signature),
            ) { "Invalid commit signature for ${headCommit.cid}" }
            // The specification requires readers to check the tree of untrusted input: keys sorted,
            // each at the depth its hash gives it. Rebuilding the tree from its entries and comparing
            // roots checks both, since only the canonical shape produces the same root.
            val importedTree =
                MerkleSearchTree.fromBlocks(head.root) { cid -> providedBlocks[cid] ?: blockStore.readBlock(cid).getOrThrow() }
            require(importedTree.rootCid() == head.root) {
                "Record tree ${head.root} is not in the canonical shape for its entries"
            }

            export.blocks.forEach { block ->
                blockStore.writeBlock(export.repo, block).getOrThrow()
            }
            export.commits.forEach { commit ->
                blockStore.appendCommit(export.repo, commit).getOrThrow()
            }
            blockStore.writeHead(head).getOrThrow()
            head
        }

    /**
     * Writes a new commit of [repo]'s current records, signed by the current signer, and returns
     * the new head; null when the repository has no commits yet.
     *
     * The specification requires a repository's newest commit to verify against the signing key in
     * the account's DID document, so a key rotation has to be followed by a commit signed with the
     * new key. The records and the tree stay the same; only `rev` and `sig` change.
     */
    public suspend fun recommit(repo: AtprotoDid): Result<RepoHead?> =
        runCatching {
            retryingOnHeadConflict {
                val snapshot = loadSnapshot(repo)
                snapshot.head?.let { head -> persistSnapshot(repo, snapshot.tree, head, snapshot.tree) }
            }
        }

    private suspend fun loadSnapshot(repo: AtprotoDid): RepoSnapshot {
        val head = blockStore.readHead(repo).getOrThrow() ?: return RepoSnapshot(head = null, tree = MerkleSearchTree.empty())
        // Only MST nodes reachable from the current head are needed here. listBlocks loads every
        // historic record, tree, and commit block for the repo into memory on every read/write;
        // that grows without bound as backups accumulate and can exhaust the server container.
        val tree = MerkleSearchTree.fromBlocks(head.root) { cid -> blockStore.readBlock(cid).getOrThrow() }
        return RepoSnapshot(head = head, tree = tree)
    }

    private suspend fun persistSnapshot(
        repo: AtprotoDid,
        tree: MerkleSearchTree,
        previousHead: RepoHead?,
        previousTree: MerkleSearchTree?,
    ): RepoHead {
        val graph = tree.toBlockGraph()
        // Node blocks are content addressed, so a node the write did not touch serialises to the
        // exact bytes already stored. Writing the whole tree every time made a single record cost
        // a round trip per node -- work proportional to the size of the journal, on every save.
        // Only the nodes on the changed path are actually new.
        val alreadyStored =
            previousTree
                ?.toBlockGraph()
                ?.blocks
                ?.mapTo(mutableSetOf()) { it.cid }
                ?: emptySet()
        graph.blocks.forEach { block ->
            if (block.cid !in alreadyStored) {
                blockStore.writeBlock(repo, block).getOrThrow()
            }
        }
        val rootCid = graph.root

        val commit =
            RepoCommit(
                repo = repo,
                root = rootCid,
                prev = previousHead?.commitCid,
                revision = nextRevision(repo, previousHead?.revision),
                createdAtEpochMillis = clock.now().toEpochMilliseconds(),
                recordCount = tree.entries().size,
            )
        val unsignedCommitBytes = encodeCommitPayload(commit)
        val signature = signer.sign(commit, unsignedCommitBytes)
        val signedCommitBytes = encodeSignedCommitPayload(commit, signature)
        val commitCid = Cid.sha256(DAG_CBOR_CODEC, signedCommitBytes)
        blockStore.writeBlock(repo, RepoBlock(commitCid, signedCommitBytes)).getOrThrow()

        val head =
            RepoHead(
                repo = repo,
                root = rootCid,
                commitCid = commitCid,
                revision = commit.revision,
            )
        val swapped = blockStore.compareAndSwapHead(head, previousHead?.revision).getOrThrow()
        if (!swapped) {
            throw RepoHeadConflictException(repo = repo, expectedRevision = previousHead?.revision)
        }
        // Recorded only after the head moved, so the history never lists a commit that lost the
        // race for the head. Exports read the head's commit block directly, so a failure here
        // leaves the repository readable and exportable.
        blockStore.appendCommit(repo, SignedRepoCommit(cid = commitCid, commit = commit, signature = signature)).getOrThrow()
        return head
    }

    /**
     * The revision for a new commit: the TID for the current time, or one past [previous] when the
     * clock has not moved past it. The specification requires every commit's `rev` to be greater
     * than the one before, and that has to hold even when two commits land in the same
     * microsecond, the clock steps backwards, or the repository still carries the small counter
     * revisions it was written with before revisions were TIDs.
     */
    private fun nextRevision(
        repo: AtprotoDid,
        previous: Long?,
    ): Long {
        val clockRevision = Tid.fromInstant(clock.now()).toLong()
        if (previous == null || clockRevision > previous) return clockRevision
        val revision = previous + 1
        val aheadMicros = Tid.fromLong(revision).timestampMicros - Tid.fromLong(clockRevision).timestampMicros
        if (aheadMicros > CLOCK_SKEW_WARNING_MICROS) {
            onClockSkew?.invoke(repo, aheadMicros)
        }
        return revision
    }

    private data class RepoSnapshot(
        val head: RepoHead?,
        val tree: MerkleSearchTree,
    )

    private suspend fun reachableBlocks(
        repo: AtprotoDid,
        tree: MerkleSearchTree,
        commitCid: Cid,
    ): List<RepoBlock> {
        val blocks = linkedMapOf<Cid, RepoBlock>()
        val commitBlock = requireNotNull(blockStore.readBlock(commitCid).getOrThrow()) { "Missing commit block $commitCid" }
        blocks[commitBlock.cid] = commitBlock
        val graph = tree.toBlockGraph()
        graph.blocks.forEach { block ->
            blocks[block.cid] = block
        }
        tree.entries().forEach { entry ->
            val leafBlock = requireNotNull(blockStore.readBlock(entry.cid).getOrThrow()) { "Missing record block ${entry.cid}" }
            blocks[leafBlock.cid] = leafBlock
        }
        return blocks.values.toList()
    }

    private companion object {
        private const val MAX_PAGE_SIZE: Int = 100
        private const val MICROS_PER_SECOND: Long = 1_000_000L

        /** Ordinary clock drift between servers stays well under this. */
        private const val CLOCK_SKEW_WARNING_MICROS: Long = 5 * MICROS_PER_SECOND

        /**
         * Enough to ride out contention between the handful of writers one account can have in
         * flight, while still failing loudly rather than spinning if something is badly wrong.
         */
        private const val MAX_HEAD_CONFLICT_RETRIES: Int = 5
    }
}
