@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.logdate.LogDateBlobStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** Settled private candidates use an irreversible path barrier before provider deletion. */
internal class PostgreSQLIdentityAdoptionMediaCleanup(
    private val source: DataSource,
    private val storage: LogDateBlobStorage,
    private val clock: Clock = Clock.System,
) {
    suspend fun pending(after: String? = null): List<String> =
        source.blueskyTransaction {
            prepareStatement(
                "SELECT u.id FROM identity_adoption_media_uploads u LEFT JOIN identity_adoption_operations o ON o.id=u.operation_id " +
                    "AND o.account_id=u.account_id WHERE u.phase <> 'RETIRED' " +
                    "AND (u.phase <> 'PROMOTED' OR NOT EXISTS(SELECT 1 FROM accounts a WHERE a.id=u.account_id)) " +
                    "AND (u.phase='DELETE_PENDING' OR o.id IS NULL OR o.phase IN ('CANCELED','COMMITTED') " +
                    "OR (GREATEST(o.updated_at,u.last_used_at) < ? AND o.lease_expires_at < ?)) " +
                    "AND (?::text IS NULL OR u.id > ?) ORDER BY u.id LIMIT 100",
            ).use {
                it.setTimestamp(1, (clock.now() - 7.days).timestamp())
                it.setTimestamp(2, clock.now().timestamp())
                it.setString(3, after)
                it.setString(4, after)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

    suspend fun cleanup(id: String): Boolean {
        val row = withContext(RawJdbcTransactionContext()) { source.blueskyTransaction { prepare(id) } } ?: return false
        if (row.phase == "RETIRED") return true
        val fenced = withContext(Dispatchers.IO) { storage.retirePrivateBlob(row.blobId, row.ownerToken) }
        if (!fenced) return false
        return source.blueskyTransaction {
            lockOwner(row.accountId)
            lockObjectPath(row.path)
            val current = upload(id) ?: return@blueskyTransaction false
            if (current.phase == "RETIRED") return@blueskyTransaction true
            if (current.phase != "DELETE_PENDING" || current.path != row.path) return@blueskyTransaction false
            requireExclusiveObjectPath(Uuid.parse(row.accountId).toJavaUUID(), LegacyObjectKind.UNREFERENCED, row.path, row.path)
            prepareStatement(
                "UPDATE legacy_object_deletions SET completed=TRUE WHERE operation_id=? AND account_id=? AND storage_path=?",
            ).use {
                it.setString(1, row.blobId)
                it.setObject(2, Uuid.parse(row.accountId).toJavaUUID())
                it.setString(3, row.path)
                check(it.executeUpdate() == 1)
            }
            mediaUploadPhase(id, "DELETE_PENDING", "RETIRED", clock.now())
        }
    }

    private fun Connection.prepare(id: String): IdentityAdoptionMediaUploadRow? {
        val found = upload(id, lock = false) ?: return null
        lockOwner(found.accountId)
        val row = upload(id) ?: return null
        if (row.phase == "RETIRED") return row
        if (row.phase == "PROMOTED") {
            val exists =
                prepareStatement("SELECT 1 FROM accounts WHERE id=?").use {
                    it.setObject(1, Uuid.parse(row.accountId).toJavaUUID())
                    it.executeQuery().use { rows -> rows.next() }
                }
            if (exists) return null
        }
        if (row.phase != "DELETE_PENDING" && !expired(row)) return null
        return retire(row)
    }

    internal fun Connection.retire(row: IdentityAdoptionMediaUploadRow): IdentityAdoptionMediaUploadRow {
        val id = row.upload.uploadId
        lockObjectPath(row.path)
        prepareStatement("DELETE FROM identity_adoption_staged_blobs WHERE id=? AND operation_id=? AND storage_path=?").use {
            it.setString(1, id)
            it.setString(2, row.operationId)
            it.setString(3, row.path)
            it.executeUpdate()
        }
        if (row.phase != "DELETE_PENDING") check(mediaUploadPhase(id, row.phase, "DELETE_PENDING", clock.now()))
        requireExclusiveObjectPath(Uuid.parse(row.accountId).toJavaUUID(), LegacyObjectKind.UNREFERENCED, row.path, row.path)
        prepareStatement(
            "INSERT INTO legacy_object_deletions(operation_id,account_id,kind,resource_id,identity_epoch,storage_path,completed) " +
                "VALUES (?,?,'UNREFERENCED',?,?,?,FALSE) ON CONFLICT(storage_path) DO NOTHING",
        ).use {
            it.setString(1, row.blobId)
            it.setObject(2, Uuid.parse(row.accountId).toJavaUUID())
            it.setString(3, row.path)
            it.setLong(4, row.epoch)
            it.setString(5, row.path)
            it.executeUpdate()
        }
        val owns =
            prepareStatement("SELECT 1 FROM legacy_object_deletions WHERE operation_id=? AND account_id=? AND storage_path=?").use {
                it.setString(1, row.blobId)
                it.setObject(2, Uuid.parse(row.accountId).toJavaUUID())
                it.setString(3, row.path)
                it.executeQuery().use { rows -> rows.next() }
            }
        check(owns)
        return row.copy(phase = "DELETE_PENDING")
    }

    private fun Connection.expired(row: IdentityAdoptionMediaUploadRow): Boolean =
        prepareStatement(
            "SELECT o.phase,o.updated_at,o.lease_expires_at,u.last_used_at FROM identity_adoption_operations o " +
                "JOIN identity_adoption_media_uploads u ON u.operation_id=o.id AND u.account_id=o.account_id " +
                "WHERE o.id=? AND o.account_id=? AND u.id=? FOR UPDATE OF o",
        ).use {
            it.setString(1, row.operationId)
            it.setObject(2, Uuid.parse(row.accountId).toJavaUUID())
            it.setString(3, row.upload.uploadId)
            it.executeQuery().use { rows ->
                if (!rows.next()) return true
                val phase = rows.getString(1)
                phase == "CANCELED" ||
                    phase == "COMMITTED" ||
                    (
                        maxOf(rows.getTimestamp(2).connectionInstant(), rows.getTimestamp(4).connectionInstant()) < clock.now() - 7.days &&
                            rows.getTimestamp(3).connectionInstant() < clock.now()
                    )
            }
        }

    private fun Connection.lockOwner(accountId: String) {
        prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE").use {
            it.setObject(1, Uuid.parse(accountId).toJavaUUID())
            it.executeQuery().close()
        }
    }

    private fun Connection.upload(
        id: String,
        lock: Boolean = true,
    ): IdentityAdoptionMediaUploadRow? =
        prepareStatement("SELECT * FROM identity_adoption_media_uploads WHERE id=?" + if (lock) " FOR UPDATE" else "").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> if (rows.next()) rows.mediaUploadRow() else null }
        }
}
