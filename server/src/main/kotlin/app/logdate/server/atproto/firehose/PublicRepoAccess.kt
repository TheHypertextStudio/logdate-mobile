package app.logdate.server.atproto.firehose

import app.logdate.server.auth.AccountRepository
import app.logdate.server.logging.RepoEventNotices
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.RepoBlockStore
import studio.hypertext.atproto.repo.RepoCommitChanges
import studio.hypertext.atproto.repo.RepoExport
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.RepoListPage
import studio.hypertext.atproto.repo.RepoRecord
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid
import java.util.concurrent.ConcurrentHashMap

/** One repository in a `com.atproto.sync.listRepos` page. */
data class PublicRepoSummary(
    val did: AtprotoDid,
    val head: RepoHead,
)

/** A page of [PublicRepoSummary]; [cursor] is the DID to continue after, or `null` at the end. */
data class PublicRepoPage(
    val repos: List<PublicRepoSummary>,
    val cursor: String?,
)

/**
 * A repository the directory found, as stored: the account's DID and the head of its record tree. They
 * stay text until [PublicRepoAccess] parses them, so one malformed row is skipped and never stops a page.
 */
data class PublicRepoCandidate(
    val did: String,
    val rootCid: String,
    val commitCid: String,
    val revision: Long,
)

/**
 * Finds the repositories of accounts that may be shown, in one query, ordered by DID.
 *
 * An implementation applies the account rules of [PublicRepoGate] itself, so that paging through
 * accounts costs one query per page and not several per account: active, in good standing, in a
 * personal space, with an identity other apps can look up, and with a repository.
 */
interface PublicRepoDirectory {
    /** Up to [limit] candidates whose DID sorts after [afterDid], or from the start when it is `null`. */
    suspend fun after(
        afterDid: String?,
        limit: Int,
    ): List<PublicRepoCandidate>
}

/**
 * A directory over any [AccountRepository], for servers without a database. It reads every account, so
 * a database-backed directory is used wherever there are many.
 */
class AccountsPublicRepoDirectory(
    private val accounts: AccountRepository,
    private val gate: AccountPublicRepoGate,
    private val blocks: RepoBlockStore,
) : PublicRepoDirectory {
    override suspend fun after(
        afterDid: String?,
        limit: Int,
    ): List<PublicRepoCandidate> {
        val dids =
            accounts
                .getAllAccounts()
                .filter(gate::mayShow)
                .mapNotNull { it.did }
                .filter { afterDid == null || it > afterDid }
                .sorted()
        val found = mutableListOf<PublicRepoCandidate>()
        for (did in dids) {
            val parsed = AtprotoDid.parse(did).getOrNull() ?: continue
            val head = blocks.readHead(parsed).getOrThrow() ?: continue
            found += PublicRepoCandidate(did, head.root.toString(), head.commitCid.toString(), head.revision)
            if (found.size == limit) break
        }
        return found
    }
}

/**
 * Everything the server shows to people who are not an account's owner, and the only place it
 * decides what that is.
 *
 * Reading a repository's records and blobs needs only that the account's repository is one the
 * server shows ([PublicRepoGate]). The record methods further limit the answer to public
 * collections: a record in any other collection is reported as missing and a listing of one is
 * empty, so a caller cannot tell a private collection from one that does not exist.
 *
 * Anything that reveals the repository itself needs more: the record tree must hold nothing
 * private. That covers the export, and also the head, the revision and the listing, because a tree
 * names every record's collection and key, and a revision is the time of a write. An account that
 * moved into its personal space keeps its old journal records in the tree for a while, and until they
 * are erased none of this is shown.
 *
 * Reads go straight to the block store through a read-only engine. They never pass through the
 * adapters that present a person's journal as records, so no private record can be reached from here.
 */
class PublicRepoAccess(
    private val gate: PublicRepoGate,
    private val policy: PublicRepoPolicy,
    private val blocks: RepoBlockStore,
    private val directory: PublicRepoDirectory,
) {
    private val reader = DefaultRepoEngine(blocks)
    private val treeChecks = ConcurrentHashMap<AtprotoDid, Pair<Cid, Boolean>>()
    private val blobIndexes = ConcurrentHashMap<AtprotoDid, Pair<Cid, Set<Cid>>>()

    /** The standing of [did]'s account, or `null` when the server shows nothing about it. */
    suspend fun standing(did: AtprotoDid): RepoStanding? = gate.standing(did)

    /**
     * The head of [did]'s repository whatever the account's standing, unless its record tree holds
     * something private. For the status of an account that is not shown anymore.
     */
    suspend fun publicHeadOf(did: AtprotoDid): RepoHead? = reader.loadHead(did).getOrThrow()?.takeIf { treeIsPublic(did, it) }

    /** The head of [did]'s repository when the server may show it and its tree holds nothing private, otherwise `null`. */
    suspend fun headIfShown(did: AtprotoDid): RepoHead? = if (gate.mayShow(did)) publicHeadOf(did) else null

    /**
     * The head of [did]'s repository when the server may show its public records, otherwise `null`. Unlike
     * [headIfShown] this does not look at the tree, because a record read names only the record it returns.
     */
    suspend fun readableHead(did: AtprotoDid): RepoHead? = if (gate.mayShow(did)) reader.loadHead(did).getOrThrow() else null

    /**
     * The record at [collection] and [recordKey], or `null` when it does not exist, is in a
     * collection that is not public, or the repository is not shown. When [cid] is given, only that
     * exact version matches.
     */
    suspend fun record(
        did: AtprotoDid,
        collection: Nsid,
        recordKey: RecordKey,
        cid: String?,
    ): RepoRecord? {
        if (!policy.isPublic(collection) || readableHead(did) == null) return null
        val record = reader.getRecord(RepoRecordId(did, collection, recordKey)).getOrThrow()
        return record?.takeIf { cid == null || it.cid == cid }
    }

    /** One page of [collection], empty when it is not public or the repository is not shown. */
    suspend fun records(
        did: AtprotoDid,
        collection: Nsid,
        limit: Int,
        cursor: String?,
        reverse: Boolean,
    ): RepoListPage {
        if (!policy.isPublic(collection) || readableHead(did) == null) return RepoListPage(emptyList())
        return reader.listRecords(did, collection, limit, cursor, reverse).getOrThrow()
    }

    /**
     * The repository as a CAR export, or `null` when it is not shown or holds a record that is not
     * public. The result of the tree check is remembered for as long as the head stays the same.
     */
    suspend fun export(
        did: AtprotoDid,
        since: Tid?,
    ): RepoExport? {
        val head = readableHead(did) ?: return null
        if (!treeIsPublic(did, head)) return null
        return reader.export(did, since).getOrThrow()
    }

    /**
     * True when a public record of [did]'s repository refers to the blob [cid]. A blob that no
     * public record refers to stays private to its owner, even when it was uploaded to the same
     * account.
     */
    suspend fun referencesBlob(
        did: AtprotoDid,
        cid: Cid,
    ): Boolean {
        val head = readableHead(did) ?: return false
        val cached = blobIndexes[did]?.takeIf { it.first == head.commitCid }?.second
        val referenced = cached ?: loadBlobReferences(did, head).also { blobIndexes[did] = head.commitCid to it }
        return cid in referenced
    }

    /**
     * One page of the repositories the server shows, ordered by DID, starting after [cursor]. An
     * account that has not written anything yet has no repository and is left out, and so is one whose
     * tree still holds private records.
     *
     * The work for one call is bounded: the directory is asked for at most [MAX_ROUNDS] pages of [limit]
     * candidates. A page that fills up returns its last DID as the cursor, and so does one that used up its
     * rounds with fewer repositories, so the caller continues from where the search stopped. The cursor
     * moves with every candidate looked at, whether or not it is shown.
     */
    suspend fun listRepos(
        cursor: String?,
        limit: Int,
    ): PublicRepoPage {
        val repos = mutableListOf<PublicRepoSummary>()
        var after = cursor
        repeat(MAX_ROUNDS) {
            val candidates = directory.after(after, limit)
            for (candidate in candidates) {
                after = candidate.did
                val did = AtprotoDid.parse(candidate.did).getOrNull() ?: continue
                val head = candidate.toHead(did) ?: continue
                if (!treeIsPublic(did, head)) continue
                repos += PublicRepoSummary(did, head)
                if (repos.size == limit) return PublicRepoPage(repos, cursor = after)
            }
            if (candidates.size < limit) return PublicRepoPage(repos, cursor = null)
        }
        return PublicRepoPage(repos, cursor = after)
    }

    private fun PublicRepoCandidate.toHead(did: AtprotoDid): RepoHead? =
        runCatching { RepoHead(did, Cid.require(rootCid), Cid.require(commitCid), revision) }.getOrNull()

    private suspend fun treeIsPublic(
        did: AtprotoDid,
        head: RepoHead,
    ): Boolean {
        treeChecks[did]?.takeIf { it.first == head.commitCid }?.let { return it.second }
        // A tree that cannot be read is not shown, and the failure is not remembered: it may pass.
        val collections =
            RepoCommitChanges.collectionsAt(blocks, head.root).getOrElse { failure ->
                Napier.w(RepoEventNotices.TREE_UNREADABLE, failure)
                return false
            }
        val pure = policy.arePublic(collections)
        treeChecks[did] = head.commitCid to pure
        return pure
    }

    private suspend fun loadBlobReferences(
        did: AtprotoDid,
        head: RepoHead,
    ): Set<Cid> {
        val collections = RepoCommitChanges.collectionsAt(blocks, head.root).getOrThrow().filter(policy::isPublic)
        val references = linkedSetOf<Cid>()
        collections.forEach { collection ->
            var cursor: String? = null
            do {
                val page = reader.listRecords(did, collection, limit = PAGE_SIZE, cursor = cursor, reverse = false).getOrThrow()
                page.records.forEach { record -> collectBlobCids(record.value, references) }
                cursor = page.cursor
            } while (cursor != null)
        }
        return references
    }

    private fun collectBlobCids(
        element: JsonElement,
        into: MutableSet<Cid>,
    ) {
        when (element) {
            is JsonObject -> {
                val link = (element["ref"] as? JsonObject)?.get("\$link") as? JsonPrimitive
                if (element["\$type"]?.jsonPrimitive?.content == "blob" && link != null) {
                    Cid.parse(link.content).getOrNull()?.let(into::add)
                }
                element.values.forEach { collectBlobCids(it, into) }
            }

            is JsonArray -> {
                element.forEach { collectBlobCids(it, into) }
            }

            is JsonPrimitive -> {
                Unit
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_ROUNDS = 3
    }
}
