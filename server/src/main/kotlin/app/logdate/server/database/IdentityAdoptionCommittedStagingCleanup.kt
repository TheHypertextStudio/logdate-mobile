package app.logdate.server.database

import java.sql.Connection
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Discards superseded temporary evidence, never the receipt's final generation or source history. */
internal class IdentityAdoptionCommittedStagingCleanup(
    private val connection: Connection,
    private val batchSize: Int,
) {
    fun cleanup(
        id: String,
        account: UUID,
        now: Instant,
    ): Boolean? {
        val committed =
            connection
                .prepareStatement(
                    "SELECT phase FROM identity_adoption_operations WHERE id=? AND account_id=? FOR UPDATE",
                ).use {
                    it.setString(1, id)
                    it.setObject(2, account)
                    it.executeQuery().use { rows -> rows.next() && rows.getString(1) == "COMMITTED" }
                }
        if (!committed) return null
        val anchor =
            connection
                .prepareStatement(
                    "SELECT v.hold_generation,v.verified_revision,r.committed_at FROM identity_adoption_operations o $ANCHOR " +
                        "WHERE o.id=? AND o.account_id=?",
                ).use {
                    it.setString(1, id)
                    it.setObject(2, account)
                    it.executeQuery().use { rows ->
                        if (!rows.next() || rows.getTimestamp(3).connectionInstant() >= now - 7.days) return false
                        rows.getLong(1) to rows.getLong(2)
                    }
                }
        if (!hasPayload(id, account, anchor)) return false
        for ((table, column) in GENERATIONS) {
            val limit = if (table == "identity_adoption_verifications") anchor.second else anchor.first
            deleteBatch(table, column, id, account, limit)
        }
        return !hasPayload(id, account, anchor)
    }

    private fun hasPayload(
        id: String,
        account: UUID,
        anchor: Pair<Long, Long>,
    ): Boolean {
        for ((table, column) in GENERATIONS) {
            val found =
                connection
                    .prepareStatement(
                        "SELECT 1 FROM $table WHERE operation_id=? AND account_id=? AND $column < ? LIMIT 1",
                    ).use {
                        it.setString(1, id)
                        it.setObject(2, account)
                        it.setLong(3, if (table == "identity_adoption_verifications") anchor.second else anchor.first)
                        it.executeQuery().use { rows -> rows.next() }
                    }
            if (found) return true
        }
        return false
    }

    private fun deleteBatch(
        table: String,
        column: String,
        id: String,
        account: UUID,
        generation: Long,
    ) {
        val unreferenced =
            if (table == "identity_adoption_staged_records") {
                " AND NOT EXISTS(SELECT 1 FROM identity_adoption_staged_origins origin " +
                    "WHERE origin.operation_id=payload.operation_id AND origin.account_id=payload.account_id " +
                    "AND origin.copy_generation=payload.hold_generation AND origin.target_rkey=payload.rkey)"
            } else {
                ""
            }
        connection
            .prepareStatement(
                "DELETE FROM $table WHERE ctid IN (SELECT payload.ctid FROM $table payload " +
                    "WHERE operation_id=? AND account_id=? AND $column < ?$unreferenced LIMIT ?)",
            ).use {
                it.setString(1, id)
                it.setObject(2, account)
                it.setLong(3, generation)
                it.setInt(4, batchSize)
                it.executeUpdate()
            }
    }

    companion object {
        private val GENERATIONS =
            listOf(
                "identity_adoption_staged_origins" to "copy_generation",
                "identity_adoption_staged_records" to "hold_generation",
                "identity_adoption_source_mappings" to "copy_generation",
                "identity_adoption_staged_blobs" to "hold_generation",
                "identity_adoption_verifications" to "verified_revision",
            )
        private const val VERIFIED =
            "JOIN identity_adoption_verifications v ON v.operation_id=r.operation_id AND v.account_id=r.account_id " +
                "AND v.verified_revision=r.verified_revision AND r.verified_revision=o.revision-1 " +
                "AND v.hold_generation=o.hold_generation AND v.manifest_digest=r.manifest_digest " +
                "AND v.coverage_digest=r.coverage_digest"
        private const val ANCHOR =
            "JOIN identity_adoption_receipts r ON r.operation_id=o.id AND r.account_id=o.account_id " + VERIFIED
        val PENDING: String =
            run {
                val obsolete =
                    GENERATIONS.joinToString(" OR ") { (table, column) ->
                        val anchor = if (table == "identity_adoption_verifications") "v.verified_revision" else "v.hold_generation"
                        "EXISTS(SELECT 1 FROM $table payload WHERE payload.operation_id=o.id " +
                            "AND payload.account_id=o.account_id AND payload.$column < $anchor)"
                    }
                "EXISTS(SELECT 1 FROM identity_adoption_receipts r $VERIFIED " +
                    "WHERE r.operation_id=o.id AND r.account_id=o.account_id AND r.committed_at < ? AND ($obsolete))"
            }
    }
}
