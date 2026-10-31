package studio.hypertext.atproto.repo

import studio.hypertext.atproto.identity.AtprotoDid

/**
 * Persistence contract for repo blocks, heads, and commit history.
 */
public interface RepoBlockStore {
    /**
     * Loads the current repo head.
     */
    public suspend fun readHead(repo: AtprotoDid): Result<RepoHead?>

    /**
     * Persists [head] as the current repo head.
     */
    public suspend fun writeHead(head: RepoHead): Result<Unit>

    /**
     * Moves the head to [head] only while the stored head is still at [expectedRevision], where
     * null means the repo has no head yet. Returns false when somebody else moved it first.
     *
     * A repo write reads the whole tree, rebuilds it and writes the head back. Two writes in
     * flight for one repo each build a tree that is missing the other's record, so an
     * unconditional write silently drops whichever landed first -- its blocks survive but nothing
     * points at them. Callers use the false return to reload and try again.
     */
    public suspend fun compareAndSwapHead(
        head: RepoHead,
        expectedRevision: Long?,
    ): Result<Boolean>

    /**
     * Reads a single block by [cid].
     */
    public suspend fun readBlock(cid: Cid): Result<RepoBlock?>

    /** Reads blocks in request order, preserving duplicates and nulls for missing blocks. */
    public suspend fun readBlocks(cids: List<Cid>): Result<List<RepoBlock?>> = runCatching { cids.map { readBlock(it).getOrThrow() } }

    /**
     * Persists [block] and associates it with [repo].
     */
    public suspend fun writeBlock(
        repo: AtprotoDid,
        block: RepoBlock,
    ): Result<Unit>

    /**
     * Clears [repo]'s head, block associations, and commit history.
     */
    public suspend fun clearRepo(repo: AtprotoDid): Result<Unit>

    /**
     * Lists blocks currently associated with [repo].
     */
    public suspend fun listBlocks(repo: AtprotoDid): Result<List<RepoBlock>>

    /**
     * Appends [commit] to [repo]'s commit history.
     */
    public suspend fun appendCommit(
        repo: AtprotoDid,
        commit: SignedRepoCommit,
    ): Result<Unit>

    /**
     * Lists commits for [repo] in oldest-to-newest order.
     */
    public suspend fun listCommits(repo: AtprotoDid): Result<List<SignedRepoCommit>>

    /**
     * Returns the newest [limit] commits of [repo], oldest first. A store that can limit the query
     * should override this, so a page of history does not read all of it.
     */
    public suspend fun listRecentCommits(
        repo: AtprotoDid,
        limit: Int,
    ): Result<List<SignedRepoCommit>> = listCommits(repo).map { it.takeLast(limit) }
}

/**
 * In-memory repo block store for tests and standalone use.
 */
public class InMemoryRepoBlockStore : RepoBlockStore {
    private val heads: MutableMap<AtprotoDid, RepoHead> = linkedMapOf()
    private val blocks: MutableMap<Cid, RepoBlock> = linkedMapOf()
    private val repoBlocks: MutableMap<AtprotoDid, LinkedHashSet<Cid>> = linkedMapOf()
    private val commits: MutableMap<AtprotoDid, MutableList<SignedRepoCommit>> = linkedMapOf()

    override suspend fun readHead(repo: AtprotoDid): Result<RepoHead?> = Result.success(heads[repo])

    override suspend fun writeHead(head: RepoHead): Result<Unit> =
        Result.success(
            run {
                heads[head.repo] = head
                Unit
            },
        )

    override suspend fun compareAndSwapHead(
        head: RepoHead,
        expectedRevision: Long?,
    ): Result<Boolean> =
        Result.success(
            if (heads[head.repo]?.revision != expectedRevision) {
                false
            } else {
                heads[head.repo] = head
                true
            },
        )

    override suspend fun readBlock(cid: Cid): Result<RepoBlock?> = Result.success(blocks[cid])

    override suspend fun writeBlock(
        repo: AtprotoDid,
        block: RepoBlock,
    ): Result<Unit> =
        Result.success(
            run {
                blocks[block.cid] = block
                repoBlocks.getOrPut(repo) { linkedSetOf() }.add(block.cid)
                Unit
            },
        )

    override suspend fun clearRepo(repo: AtprotoDid): Result<Unit> =
        Result.success(
            run {
                heads.remove(repo)
                repoBlocks.remove(repo)
                commits.remove(repo)
                Unit
            },
        )

    override suspend fun listBlocks(repo: AtprotoDid): Result<List<RepoBlock>> =
        Result.success(
            repoBlocks[repo]
                .orEmpty()
                .mapNotNull(blocks::get),
        )

    override suspend fun appendCommit(
        repo: AtprotoDid,
        commit: SignedRepoCommit,
    ): Result<Unit> =
        Result.success(
            run {
                commits.getOrPut(repo) { mutableListOf() }.add(commit)
                Unit
            },
        )

    override suspend fun listCommits(repo: AtprotoDid): Result<List<SignedRepoCommit>> = Result.success(commits[repo].orEmpty().toList())
}
