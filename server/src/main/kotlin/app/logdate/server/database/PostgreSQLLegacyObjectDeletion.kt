package app.logdate.server.database

import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal enum class LegacyObjectKind(
    val table: String,
    val key: String,
) {
    MEDIA("logdate_media_records", "media_id"),
    BACKUP("logdate_backups", "id"),
    UNREFERENCED("", ""),
}

/** Claims survive storage failures and process restarts. Retired paths are never re-registered. */
internal object LegacyObjectDeletionsTable : Table("legacy_object_deletions") {
    val operationId = varchar("operation_id", 64)
    val accountId = javaUUID("account_id")
    val kind = varchar("kind", 16)
    val resourceId = text("resource_id")
    val identityEpoch = long("identity_epoch")
    val storagePath = text("storage_path").uniqueIndex()
    val completed = bool("completed").default(false)
    override val primaryKey = PrimaryKey(operationId)
}

@OptIn(ExperimentalUuidApi::class)
internal class PostgreSQLLegacyObjectDeletion {
    private data class Work(
        val operationId: String,
        val path: String,
    )

    fun cleanup(
        accountId: UUID,
        path: String,
        epoch: Long,
        deleteObject: (String) -> Boolean,
    ): Boolean = delete(accountId, LegacyObjectKind.UNREFERENCED, path, epoch, deleteObject) {}

    fun delete(
        accountId: UUID,
        kind: LegacyObjectKind,
        resourceId: String,
        epoch: Long,
        deleteObject: (String) -> Boolean,
        finishMetadata: () -> Unit,
    ): Boolean {
        if (TransactionManager.currentOrNull() != null || RawJdbcTransactionContext.isActive()) {
            throw IdentityLifecycleConflictException("Object deletion cannot run inside another database transaction")
        }
        val work = prepare(accountId, kind, resourceId, epoch, finishMetadata) ?: return true
        if (!deleteObject(work.path)) return false
        return complete(accountId, kind, resourceId, epoch, work, finishMetadata)
    }

    private fun prepare(
        accountId: UUID,
        kind: LegacyObjectKind,
        resourceId: String,
        epoch: Long,
        finishMetadata: () -> Unit,
    ): Work? =
        transaction {
            requireAccountIdentityWrite(accountId, epoch)
            val connection = accountWriteConnection()
            val pending = connection.pending(accountId, kind, resourceId, epoch)
            if (pending != null) {
                connection.lockObjectPath(pending.path)
                if (connection.currentPath(accountId, kind, resourceId) != pending.path) {
                    throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                }
                connection.requireExclusiveObjectPath(accountId, kind, resourceId, pending.path)
                return@transaction pending
            }
            val path = connection.currentPath(accountId, kind, resourceId)
            if (path == null) {
                finishMetadata()
                return@transaction null
            }
            connection.lockObjectPath(path)
            val previous = connection.previous(path)
            if (previous != null) {
                if (previous.first != accountId || !previous.second) {
                    throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                }
                // Completion and metadata deletion were committed together.
                return@transaction null
            }
            connection.requireExclusiveObjectPath(accountId, kind, resourceId, path)
            val work = Work(Uuid.random().toString(), path)
            connection
                .prepareStatement(
                    "INSERT INTO legacy_object_deletions(operation_id, account_id, kind, resource_id, " +
                        "identity_epoch, storage_path, completed) " +
                        "VALUES (?, ?, ?, ?, ?, ?, FALSE)",
                ).use {
                    it.setString(1, work.operationId)
                    it.setObject(2, accountId)
                    it.setString(3, kind.name)
                    it.setString(4, resourceId)
                    it.setLong(5, epoch)
                    it.setString(6, path)
                    check(it.executeUpdate() == 1)
                }
            work
        }

    private fun complete(
        accountId: UUID,
        kind: LegacyObjectKind,
        resourceId: String,
        epoch: Long,
        work: Work,
        finishMetadata: () -> Unit,
    ): Boolean =
        transaction {
            requireAccountIdentityWrite(accountId, epoch)
            val connection = accountWriteConnection()
            connection.lockObjectPath(work.path)
            val completed =
                connection
                    .prepareStatement(
                        "UPDATE legacy_object_deletions SET completed = TRUE WHERE operation_id = ? AND account_id = ? " +
                            "AND kind = ? AND resource_id = ? AND identity_epoch = ? AND storage_path = ? AND completed = FALSE",
                    ).use {
                        it.setString(1, work.operationId)
                        it.setObject(2, accountId)
                        it.setString(3, kind.name)
                        it.setString(4, resourceId)
                        it.setLong(5, epoch)
                        it.setString(6, work.path)
                        it.executeUpdate()
                    }
            if (completed == 0) return@transaction connection.previous(work.path) == (accountId to true)
            if (connection.currentPath(accountId, kind, resourceId) != work.path) {
                throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
            }
            finishMetadata()
            true
        }

    private fun Connection.pending(
        accountId: UUID,
        kind: LegacyObjectKind,
        resourceId: String,
        epoch: Long,
    ): Work? =
        prepareStatement(
            "SELECT operation_id, storage_path, identity_epoch FROM legacy_object_deletions " +
                "WHERE account_id = ? AND kind = ? AND resource_id = ? AND completed = FALSE",
        ).use {
            it.setObject(1, accountId)
            it.setString(2, kind.name)
            it.setString(3, resourceId)
            it.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                if (rows.getLong(3) != epoch) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                Work(rows.getString(1), rows.getString(2))
            }
        }

    private fun Connection.previous(path: String): Pair<UUID, Boolean>? =
        prepareStatement("SELECT account_id, completed FROM legacy_object_deletions WHERE storage_path = ?").use {
            it.setString(1, path)
            it.executeQuery().use { rows -> if (rows.next()) (rows.getObject(1, UUID::class.java) to rows.getBoolean(2)) else null }
        }

    private fun Connection.currentPath(
        accountId: UUID,
        kind: LegacyObjectKind,
        resourceId: String,
    ): String? {
        if (kind == LegacyObjectKind.UNREFERENCED) return resourceId
        return prepareStatement(
            "SELECT storage_path FROM ${kind.table} WHERE user_id = ? AND ${kind.key} = ?" +
                (if (kind == LegacyObjectKind.MEDIA) " AND deleted = FALSE" else "") + " FOR UPDATE",
        ).use {
            it.setObject(1, accountId)
            if (kind == LegacyObjectKind.BACKUP) it.setObject(2, Uuid.parse(resourceId).toJavaUUID()) else it.setString(2, resourceId)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }
}

internal fun requireLegacyObjectMutation(
    accountId: UUID,
    kind: LegacyObjectKind,
    resourceId: String,
    newPath: String? = null,
) {
    val connection = accountWriteConnection()
    val pending =
        connection
            .prepareStatement(
                "SELECT 1 FROM legacy_object_deletions WHERE account_id = ? AND kind = ? AND resource_id = ? AND completed = FALSE",
            ).use {
                it.setObject(1, accountId)
                it.setString(2, kind.name)
                it.setString(3, resourceId)
                it.executeQuery().use { rows -> rows.next() }
            }
    if (pending) throw IdentityWriteRefusedException(IdentityFenceOutcome.HELD)
    requireUnretiredObjectPath(newPath)
}

internal fun requireUnretiredObjectPath(path: String?) {
    if (path == null) return
    val connection = accountWriteConnection()
    connection.lockObjectPath(path)
    val retired =
        connection.prepareStatement("SELECT 1 FROM legacy_object_deletions WHERE storage_path = ?").use {
            it.setString(1, path)
            it.executeQuery().use { rows -> rows.next() }
        }
    if (retired) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
}

internal fun Connection.lockObjectPath(path: String) {
    // H2 fixtures cannot advertise the database adoption capability.
    if (metaData.databaseProductName != "PostgreSQL") return
    val digest = MessageDigest.getInstance("SHA-256").digest("logdate:object-deletion:$path".encodeToByteArray())
    prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
        it.setLong(1, ByteBuffer.wrap(digest).long)
        it.execute()
    }
}

internal fun Connection.requireExclusiveObjectPath(
    accountId: UUID,
    kind: LegacyObjectKind,
    resourceId: String,
    path: String,
) {
    val references =
        listOf(
            Triple("logdate_media_records", "user_id", "media_id"),
            Triple("logdate_backups", "user_id", "id"),
            Triple("logdate_atproto_blobs", "user_id", "cid"),
            Triple("space_blobs", "account_id", "cid"),
            Triple("identity_adoption_staged_blobs", "account_id", "id"),
            Triple("identity_adoption_media_uploads", "account_id", "id"),
            Triple("sync_media", "user_id", "media_id"),
            Triple("sync_backups", "user_id", "id"),
        )
    for ((table, owner, key) in references) {
        val exists = metaData.getTables(null, "public", table, arrayOf("TABLE")).use { it.next() }
        if (!exists) continue
        val own = table == kind.table
        val privateUpload = if (table == "identity_adoption_media_uploads") " AND phase NOT IN ('DELETE_PENDING','RETIRED')" else ""
        val exclusion = if (own) " AND NOT ($owner = ? AND CAST($key AS VARCHAR) = ?)" else ""
        val shared =
            prepareStatement("SELECT 1 FROM $table WHERE storage_path = ?$privateUpload$exclusion LIMIT 1").use {
                it.setString(1, path)
                if (own) {
                    it.setObject(2, accountId)
                    it.setString(3, resourceId)
                }
                it.executeQuery().use { rows -> rows.next() }
            }
        if (shared) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
    }
}
