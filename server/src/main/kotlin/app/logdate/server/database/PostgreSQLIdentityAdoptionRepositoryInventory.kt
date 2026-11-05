@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryAuditor
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryInventory
import kotlinx.coroutines.withContext
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.RepoCommit
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.SignedRepoCommit
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Instant

internal data class IdentityAdoptionRepositorySnapshot(
    val operationId: String,
    val revision: Long,
    val generation: Long,
    val fenced: Boolean,
    val inventory: IdentityAdoptionRepositoryInventory,
)

/** All graph reads share the account lock and only use the source DID's owned block links. */
internal class PostgreSQLIdentityAdoptionRepositoryInventory(
    private val source: DataSource,
) {
    suspend fun read(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionRepositorySnapshot? =
        withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction { readLocked(this, operation, now) }
        }

    internal fun readLocked(
        connection: Connection,
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionRepositorySnapshot? =
        with(connection) {
            check(RawJdbcTransactionContext.isActive())
            with(PostgreSQLIdentityAdoptionActions(source)) {
                if (currentKey(operation, now) == null) return null
            }
            val did = AtprotoDid.require(operation.binding.sourceDid)
            val head = head(did)
            val commits = commits(did)
            val owned =
                prepareStatement("SELECT cid FROM atproto_repo_block_links WHERE repo_did = ? ORDER BY cid COLLATE \"C\"").use {
                    it.setString(1, did.toString())
                    it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                }
            val inventory =
                IdentityAdoptionRepositoryAuditor.inventory(did.toString(), head, commits, owned) { cid ->
                    prepareStatement(
                        "SELECT CASE WHEN octet_length(b.bytes) <= 4008192 THEN b.bytes END FROM atproto_repo_blocks b " +
                            "JOIN atproto_repo_block_links l ON l.cid = b.cid WHERE l.repo_did = ? AND b.cid = ?",
                    ).use {
                        it.setString(1, did.toString())
                        it.setString(2, cid)
                        it.executeQuery().use { rows -> if (rows.next()) rows.getBytes(1) else null }
                    }
                }
            val fenced = operation.phase != IdentityAdoptionPhase.PREPARING
            val generation = if (fenced) generation(operation) else 0L
            IdentityAdoptionRepositorySnapshot(operation.id, operation.revision, generation, fenced, inventory)
        }

    private fun Connection.head(did: AtprotoDid): RepoHead? =
        prepareStatement("SELECT root_cid, commit_cid, revision FROM atproto_repo_heads WHERE repo_did = ?").use {
            it.setString(1, did.toString())
            it.executeQuery().use { rows ->
                if (rows.next()) RepoHead(did, Cid.require(rows.getString(1)), Cid.require(rows.getString(2)), rows.getLong(3)) else null
            }
        }

    private fun Connection.commits(did: AtprotoDid): List<SignedRepoCommit> =
        prepareStatement("SELECT * FROM atproto_repo_commits WHERE repo_did = ? ORDER BY revision").use {
            it.setString(1, did.toString())
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            SignedRepoCommit(
                                cid = Cid.require(rows.getString("cid")),
                                commit =
                                    RepoCommit(
                                        did,
                                        Cid.require(rows.getString("root_cid")),
                                        rows.getString("prev_cid")?.let(Cid::require),
                                        rows.getLong("revision"),
                                        rows.getLong("created_at_epoch_millis"),
                                        rows.getInt("record_count"),
                                    ),
                                signature = rows.getString("signature"),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.generation(operation: IdentityAdoptionOperation): Long =
        prepareStatement("SELECT hold_generation FROM identity_adoption_operations WHERE id = ? AND account_id = ?").use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1).also { check(it > 0 && it <= operation.revision) }
            }
        }
}
