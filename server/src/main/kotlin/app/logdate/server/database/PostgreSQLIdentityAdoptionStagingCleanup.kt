@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

internal class PostgreSQLIdentityAdoptionStagingCleanup(
    private val source: DataSource,
    private val clock: Clock = Clock.System,
    private val batchSize: Int = 100,
) {
    init {
        require(batchSize in 1..100)
    }

    suspend fun pending(after: String? = null): List<String> =
        source.blueskyTransaction {
            prepareStatement(
                "SELECT o.id FROM identity_adoption_operations o WHERE ((o.phase <> 'COMMITTED' " +
                    "AND (o.phase='CANCELED' OR (o.updated_at < ? AND o.lease_expires_at <= ?)) AND ($PAYLOAD_EXISTS)) " +
                    "OR (o.phase='COMMITTED' AND (${IdentityAdoptionCommittedStagingCleanup.PENDING}))) " +
                    "AND (?::text IS NULL OR o.id > ?) ORDER BY o.id LIMIT 100",
            ).use {
                val now = clock.now()
                it.setTimestamp(1, (now - 7.days).timestamp())
                it.setTimestamp(2, now.timestamp())
                it.setTimestamp(3, (now - 7.days).timestamp())
                it.setString(4, after)
                it.setString(5, after)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

    suspend fun cleanup(id: String): Boolean =
        source.blueskyTransaction {
            val account = account(id) ?: return@blueskyTransaction true
            prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE").use {
                it.setObject(1, account)
                it.executeQuery().use { rows -> if (!rows.next()) return@blueskyTransaction true }
            }
            val now = clock.now()
            IdentityAdoptionCommittedStagingCleanup(this, batchSize).cleanup(id, account, now)?.let {
                return@blueskyTransaction it
            }
            val canceled = eligible(id, account, now) ?: return@blueskyTransaction false
            if (!hasPayload(id, account)) return@blueskyTransaction true
            if (!canceled) {
                // Revoke every old generation before its private evidence can disappear; activity time is unchanged.
                prepareStatement(
                    "UPDATE identity_adoption_operations SET phase='PREPARING',revision=revision+1 " +
                        "WHERE id=? AND account_id=? AND revision < 9223372036854775807",
                ).use {
                    it.setString(1, id)
                    it.setObject(2, account)
                    if (it.executeUpdate() != 1) return@blueskyTransaction false
                }
            }
            for (table in PAYLOAD_TABLES) deleteBatch(table, id, account)
            !hasPayload(id, account)
        }

    private fun Connection.account(id: String): java.util.UUID? =
        prepareStatement("SELECT account_id FROM identity_adoption_operations WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, java.util.UUID::class.java) else null }
        }

    private fun Connection.eligible(
        id: String,
        account: java.util.UUID,
        now: Instant,
    ): Boolean? =
        prepareStatement(
            "SELECT phase,updated_at,lease_expires_at FROM identity_adoption_operations WHERE id=? AND account_id=? FOR UPDATE",
        ).use {
            it.setString(1, id)
            it.setObject(2, account)
            it.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(1) == "COMMITTED") return null
                val canceled = rows.getString(1) == "CANCELED"
                if (!canceled &&
                    (rows.getTimestamp(2).connectionInstant() >= now - 7.days || rows.getTimestamp(3).connectionInstant() > now)
                ) {
                    return null
                }
                canceled
            }
        }

    private fun Connection.hasPayload(
        id: String,
        account: java.util.UUID,
    ): Boolean =
        prepareStatement("SELECT ($PAYLOAD_EXISTS) FROM identity_adoption_operations o WHERE o.id=? AND o.account_id=?").use {
            it.setString(1, id)
            it.setObject(2, account)
            it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    private fun Connection.deleteBatch(
        table: String,
        id: String,
        account: java.util.UUID,
    ) {
        val unreferenced =
            if (table == "identity_adoption_staged_records") {
                " AND NOT EXISTS(SELECT 1 FROM identity_adoption_staged_origins origin " +
                    "WHERE origin.operation_id=payload.operation_id AND origin.account_id=payload.account_id " +
                    "AND origin.copy_generation=payload.hold_generation AND origin.target_rkey=payload.rkey)"
            } else {
                ""
            }
        prepareStatement(
            "DELETE FROM $table WHERE ctid IN (SELECT payload.ctid FROM $table payload " +
                "WHERE operation_id=? AND account_id=?$unreferenced LIMIT ?)",
        ).use {
            it.setString(1, id)
            it.setObject(2, account)
            it.setInt(3, batchSize)
            it.executeUpdate()
        }
    }

    private companion object {
        val PAYLOAD_TABLES =
            listOf(
                "identity_adoption_staged_origins",
                "identity_adoption_staged_records",
                "identity_adoption_source_mappings",
                "identity_adoption_staged_blobs",
                "identity_adoption_verifications",
            )
        val PAYLOAD_EXISTS =
            PAYLOAD_TABLES.joinToString(" OR ") {
                "EXISTS(SELECT 1 FROM $it payload WHERE payload.operation_id=o.id AND payload.account_id=o.account_id)"
            }
    }
}
