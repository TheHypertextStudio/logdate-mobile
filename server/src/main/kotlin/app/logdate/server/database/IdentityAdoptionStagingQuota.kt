@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import java.sql.Connection
import kotlin.time.Instant

internal fun Connection.requireIdentityAdoptionStagingRoom(
    operation: IdentityAdoptionOperation,
    growth: Long,
    limit: Long,
    now: Instant,
) {
    val account = operation.binding.accountId.toJavaUUID()
    prepareStatement("INSERT INTO account_storage_usage(account_id) VALUES (?) ON CONFLICT DO NOTHING").use {
        it.setObject(1, account)
        it.executeUpdate()
    }
    val totals =
        prepareStatement(
            "SELECT space_bytes, media_bytes, backup_bytes FROM account_storage_usage WHERE account_id = ? FOR UPDATE",
        ).use {
            it.setObject(1, account)
            it.executeQuery().use { rows ->
                check(rows.next())
                listOf(rows.getLong(1), rows.getLong(2), rows.getLong(3))
            }
        }
    val reserved =
        prepareStatement(
            "SELECT COALESCE(SUM(bytes), 0) FROM space_upload_reservations WHERE account_id = ? AND expires_at > ?",
        ).use {
            it.setObject(1, account)
            it.setTimestamp(2, now.timestamp())
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    val used =
        try {
            (totals + reserved + growth).fold(0L) { total, value ->
                stagingCheck(value >= 0, IdentityAdoptionStagingFailure.QUOTA)
                Math.addExact(total, value)
            }
        } catch (_: ArithmeticException) {
            throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.QUOTA)
        }
    stagingCheck(used <= limit, IdentityAdoptionStagingFailure.QUOTA)
}
