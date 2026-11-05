package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.space.SpaceObjectDeleteException
import app.logdate.server.space.deleteObjectOrThrow
import app.logdate.server.space.logObjectDeleteFailure
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal enum class SpaceBlobPurgeOutcome { DELETED, KEPT, FAILED }

/** A durable quarantine bridges the two transactions; no object request retains SQL locks. */
@OptIn(ExperimentalUuidApi::class)
internal class PostgreSQLSpaceBlobPurge(
    private val timeout: Duration,
    private val adjustUsage: (UUID, Long) -> Unit,
) {
    private data class Work(
        val accountId: UUID,
        val path: String,
        val size: Long,
        val createdAt: Instant,
        val operationId: String?,
        val epoch: Long?,
    )

    fun purge(
        space: String,
        cid: String,
        before: Instant,
        deleteObject: (String) -> Unit,
    ): SpaceBlobPurgeOutcome {
        val work = quarantine(space, cid, before) ?: return SpaceBlobPurgeOutcome.KEPT
        try {
            deleteObjectOrThrow(deleteObject, work.path, timeout)
        } catch (failure: SpaceObjectDeleteException) {
            logObjectDeleteFailure(failure)
            return SpaceBlobPurgeOutcome.FAILED
        }
        return complete(space, cid, work)
    }

    private fun quarantine(
        space: String,
        cid: String,
        before: Instant,
    ): Work? =
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val connection = connection()
            val candidate = connection.blob(space, cid, lock = false) ?: return@transaction null
            val epoch = candidate.epoch ?: connection.epoch(candidate.accountId) ?: return@transaction null
            val outcome =
                connection.lockIdentityWrite(
                    IdentityWriteExpectation(candidate.accountId.toKotlinUuid(), epoch),
                    Clock.System.now(),
                    space,
                )
            if (outcome != IdentityFenceOutcome.ALLOWED) return@transaction null
            val current = connection.blob(space, cid, lock = true) ?: return@transaction null
            if (current.accountId != candidate.accountId ||
                (current.operationId == null && current.createdAt >= before) ||
                (current.epoch != null && current.epoch != epoch) ||
                connection.referenced(space, cid) ||
                connection.identityAdoptionRetainsObject(current.path)
            ) {
                return@transaction null
            }
            val operation = current.operationId ?: Uuid.random().toString()
            connection
                .prepareStatement(
                    "UPDATE space_blobs SET purge_operation_id = ?, purge_identity_epoch = ? WHERE space_uri = ? AND cid = ?",
                ).use {
                    it.setString(1, operation)
                    it.setLong(2, epoch)
                    it.setString(3, space)
                    it.setString(4, cid)
                    check(it.executeUpdate() == 1)
                }
            current.copy(operationId = operation, epoch = epoch)
        }

    private fun complete(
        space: String,
        cid: String,
        work: Work,
    ): SpaceBlobPurgeOutcome =
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val connection = connection()
            if (connection.lockIdentityWrite(
                    IdentityWriteExpectation(work.accountId.toKotlinUuid(), requireNotNull(work.epoch)),
                    Clock.System.now(),
                    space,
                ) != IdentityFenceOutcome.ALLOWED
            ) {
                return@transaction SpaceBlobPurgeOutcome.FAILED
            }
            val deleted =
                connection
                    .prepareStatement(
                        "DELETE FROM space_blobs WHERE space_uri = ? AND cid = ? AND account_id = ? " +
                            "AND storage_path = ? AND purge_operation_id = ? AND purge_identity_epoch = ?",
                    ).use {
                        it.setString(1, space)
                        it.setString(2, cid)
                        it.setObject(3, work.accountId)
                        it.setString(4, work.path)
                        it.setString(5, work.operationId)
                        it.setLong(6, requireNotNull(work.epoch))
                        it.executeUpdate()
                    }
            if (deleted == 0) return@transaction SpaceBlobPurgeOutcome.KEPT
            adjustUsage(work.accountId, -work.size)
            SpaceBlobPurgeOutcome.DELETED
        }

    private fun connection(): Connection = TransactionManager.current().connection.connection as Connection

    private fun Connection.blob(
        space: String,
        cid: String,
        lock: Boolean,
    ): Work? =
        prepareStatement(
            "SELECT account_id, storage_path, size_bytes, created_at, purge_operation_id, purge_identity_epoch " +
                "FROM space_blobs WHERE space_uri = ? AND cid = ?" + if (lock) " FOR UPDATE" else "",
        ).use {
            it.setString(1, space)
            it.setString(2, cid)
            it.executeQuery().use rows@{ rows ->
                if (!rows.next()) return@rows null
                val epoch = rows.getLong("purge_identity_epoch").let { value -> if (rows.wasNull()) null else value }
                val created = rows.getTimestamp("created_at").toInstant()
                Work(
                    rows.getObject("account_id", UUID::class.java),
                    rows.getString("storage_path"),
                    rows.getLong("size_bytes"),
                    Instant.fromEpochSeconds(created.epochSecond, created.nano),
                    rows.getString("purge_operation_id"),
                    epoch,
                )
            }
        }

    private fun Connection.epoch(accountId: UUID): Long? =
        prepareStatement("SELECT identity_epoch FROM accounts WHERE id = ?").use {
            it.setObject(1, accountId)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }

    private fun Connection.referenced(
        space: String,
        cid: String,
    ): Boolean =
        prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM space_record_blobs WHERE space_uri = ? AND cid = ?)",
        ).use {
            it.setString(1, space)
            it.setString(2, cid)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }
}
