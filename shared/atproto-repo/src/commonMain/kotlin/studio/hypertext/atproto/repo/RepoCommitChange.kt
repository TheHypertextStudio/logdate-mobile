package studio.hypertext.atproto.repo

import studio.hypertext.atproto.syntax.Nsid

/**
 * What one commit changed in its repository, worked out from the stored blocks.
 *
 * @property commit the commit that was just written.
 * @property previous the commit it followed, or `null` for the first commit of a repository.
 * @property operations every record the commit created, updated or deleted, sorted by path.
 * @property treeBlocks every node of the record tree this commit describes. Sending the whole tree,
 * not only the nodes that changed, lets a consumer undo a deletion without asking for more.
 * @property recordBlocks the blocks of the records the commit created or updated.
 * @property collections every collection that has a record in the commit's tree, including records
 * this commit did not touch.
 */
public class RepoCommitChange(
    public val commit: SignedRepoCommit,
    public val commitBlock: RepoBlock,
    public val previous: SignedRepoCommit?,
    public val operations: List<RepoOperation>,
    public val treeBlocks: List<RepoBlock>,
    public val recordBlocks: List<RepoBlock>,
    public val collections: Set<Nsid>,
) {
    /**
     * The CAR file `com.atproto.sync.subscribeRepos` sends for this commit: the commit block first,
     * then the tree and the new records, with the commit as the only root.
     */
    public fun toCommitCar(): ByteArray =
        CarCodec.writeCar(roots = listOf(commit.cid), blocks = listOf(commitBlock) + treeBlocks + recordBlocks)

    /**
     * The CAR file a `#sync` event sends: only the commit block.
     */
    public fun toSyncCar(): ByteArray = CarCodec.writeCar(roots = listOf(commit.cid), blocks = listOf(commitBlock))
}

/**
 * Reads the changes a commit made.
 */
public object RepoCommitChanges {
    /**
     * Compares the tree of [commit] with the tree of the commit before it.
     *
     * Fails when a block either tree needs is missing, which is also what happens for a commit
     * whose predecessor was erased from the store.
     */
    public suspend fun read(
        store: RepoBlockStore,
        commit: SignedRepoCommit,
    ): Result<RepoCommitChange> =
        runCatching {
            val commitBlock = requireNotNull(store.readBlock(commit.cid).getOrThrow()) { "Missing commit block ${commit.cid}" }
            val previous = commit.commit.prev?.let { loadCommit(store, it) }
            val newTree = MerkleSearchTree.fromBlocks(commit.commit.root) { cid -> store.readBlock(cid).getOrThrow() }
            val oldTree =
                previous
                    ?.let { MerkleSearchTree.fromBlocks(it.commit.root) { cid -> store.readBlock(cid).getOrThrow() } }
                    ?: MerkleSearchTree.empty()
            val operations = diff(oldTree, newTree)
            val recordCids = operations.mapNotNull { it.cid }
            val recordBlocks =
                store
                    .readBlocks(recordCids)
                    .getOrThrow()
                    .mapIndexed { index, block -> requireNotNull(block) { "Missing record block ${recordCids[index]}" } }
            RepoCommitChange(
                commit = commit,
                commitBlock = commitBlock,
                previous = previous,
                operations = operations,
                treeBlocks = newTree.toBlockGraph().blocks,
                recordBlocks = recordBlocks,
                collections = newTree.entries().mapTo(linkedSetOf()) { it.collection },
            )
        }

    /**
     * The collections that have a record in the tree whose root is [root].
     *
     * Reading the tree is all this does, so it is the cheap way to ask what a repository holds
     * before showing it to anyone.
     */
    public suspend fun collectionsAt(
        store: RepoBlockStore,
        root: Cid,
    ): Result<Set<Nsid>> = entriesAt(store, root).map { entries -> entries.mapTo(linkedSetOf()) { it.collection } }

    /**
     * Every record in the tree whose root is [root], as its collection, key and content identifier, in
     * key order. Only the tree is read, not the records.
     */
    public suspend fun entriesAt(
        store: RepoBlockStore,
        root: Cid,
    ): Result<List<MerkleSearchTreeEntry>> =
        runCatching {
            MerkleSearchTree.fromBlocks(root) { cid -> store.readBlock(cid).getOrThrow() }.entries()
        }

    /**
     * Reads the commit stored as block [cid].
     *
     * @throws IllegalArgumentException when the block is missing or is not a repository commit.
     */
    public suspend fun loadCommit(
        store: RepoBlockStore,
        cid: Cid,
    ): SignedRepoCommit {
        val block = requireNotNull(store.readBlock(cid).getOrThrow()) { "Missing previous commit block $cid" }
        return requireNotNull(CarCodec.decodeSignedCommitBlock(block)) { "Block $cid is not a repository commit" }
    }

    private fun diff(
        oldTree: MerkleSearchTree,
        newTree: MerkleSearchTree,
    ): List<RepoOperation> {
        val before = oldTree.entries().associateBy { it.collection to it.recordKey }
        val after = newTree.entries().associateBy { it.collection to it.recordKey }
        val created =
            after.filterKeys { it !in before }.values.map {
                RepoOperation(RepoOperationAction.CREATE, it.collection, it.recordKey, cid = it.cid, prev = null)
            }
        val updated =
            after.values.mapNotNull { entry ->
                val old = before[entry.collection to entry.recordKey] ?: return@mapNotNull null
                if (old.cid == entry.cid) return@mapNotNull null
                RepoOperation(RepoOperationAction.UPDATE, entry.collection, entry.recordKey, cid = entry.cid, prev = old.cid)
            }
        val deleted =
            before.filterKeys { it !in after }.values.map {
                RepoOperation(RepoOperationAction.DELETE, it.collection, it.recordKey, cid = null, prev = it.cid)
            }
        return (created + updated + deleted).sortedBy(RepoOperation::path)
    }
}
