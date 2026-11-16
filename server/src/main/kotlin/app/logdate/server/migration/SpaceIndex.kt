package app.logdate.server.migration

import app.logdate.server.crypto.sha256Hex
import app.logdate.server.space.MAX_SPACE_CHANGES_LIMIT
import app.logdate.server.space.SpaceStore
import app.logdate.shared.model.sync.SpaceMigrationDigests

/**
 * What one author's repository in a space holds right now, as the server can see it: the opaque key
 * and content identifier of every live record, and the last sequence number written.
 */
internal data class SpaceIndex(
    val lastSeq: Long,
    val live: List<Pair<String, String>>,
) {
    /** The digest a device sends as `spaceDigest`, over the same records. */
    val digest: String get() = sha256Hex(SpaceMigrationDigests.spaceText(live))
}

/** Reads [author]'s repository in [space] from the start of its change log. */
internal suspend fun SpaceStore.index(
    space: String,
    author: String,
): SpaceIndex {
    val latest = mutableMapOf<String, String?>()
    var since = 0L
    var lastSeq = 0L
    do {
        val page = changes(space, author, since, MAX_SPACE_CHANGES_LIMIT)
        page.changes.forEach { change ->
            latest[change.rkey] = change.cid.takeUnless { change.deleted }
            lastSeq = maxOf(lastSeq, change.seq)
        }
        since = page.cursor
    } while (page.hasMore)
    return SpaceIndex(lastSeq, latest.mapNotNull { (rkey, cid) -> cid?.let { rkey to it } })
}
