@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database
import app.logdate.shared.model.sync.SpaceMigrationPhase
import java.sql.Connection
import kotlin.uuid.Uuid

internal fun Connection.hasPendingIdentityAdoptionMutation(accountId: Uuid): Boolean =
    prepareStatement(
        "SELECT EXISTS(SELECT 1 FROM space_blobs WHERE account_id = ? AND purge_operation_id IS NOT NULL) " +
            "OR EXISTS(SELECT 1 FROM space_migrations WHERE account_id = ? AND phase = ? AND cleaned_at IS NULL) " +
            "OR EXISTS(SELECT 1 FROM legacy_object_deletions WHERE account_id = ? AND completed = FALSE) " +
            "OR EXISTS(SELECT 1 FROM object_upload_intents WHERE account_id = ? AND phase IN ('WRITING', 'STORED', 'DELETE_PENDING'))",
    ).use {
        it.setObject(1, accountId.toJavaUUID())
        it.setObject(2, accountId.toJavaUUID())
        it.setString(3, SpaceMigrationPhase.CLEANED.wireValue)
        it.setObject(4, accountId.toJavaUUID())
        it.setObject(5, accountId.toJavaUUID())
        it.executeQuery().use { rows ->
            check(rows.next())
            rows.getBoolean(1)
        }
    }
