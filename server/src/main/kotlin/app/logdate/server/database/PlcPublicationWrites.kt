package app.logdate.server.database

import app.logdate.server.identity.PlcPublicationOutcome
import app.logdate.server.identity.PlcPublicationReview
import app.logdate.server.identity.PlcPublicationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.plc.PlcOperation
import javax.sql.DataSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The write boundary for a verified, immutable publication review. */
@OptIn(ExperimentalUuidApi::class)
internal class PlcPublicationWrites(
    private val source: DataSource,
) {
    private val workers = Semaphore(4)

    suspend fun saveReview(
        accountId: Uuid,
        reviewId: Uuid,
        handle: String,
        review: PlcPublicationReview,
        appendPreparedHead: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            source.connection.use { connection ->
                connection.autoCommit = true
                connection
                    .prepareStatement(
                        """
                        INSERT INTO plc_publication_reviews (id, account_id, did, handle, operations, prepared_cid, expires_at)
                        SELECT ?::uuid, id, did, handle, ?::jsonb, ?, now() + interval '5 minutes'
                        FROM accounts
                        WHERE id = ?::uuid AND did = ? AND handle = ? AND is_active AND moderation IS NULL
                            AND public_identity_did IS DISTINCT FROM did
                            AND NOT EXISTS (
                                SELECT 1 FROM plc_publication_reviews r
                                WHERE r.account_id = accounts.id AND r.state IN ('authorized', 'uncertain', 'conflict')
                            )
                        ON CONFLICT (id) DO NOTHING
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, reviewId.toString())
                        statement.setString(2, operationJson.encodeToString(review.operations()))
                        statement.setString(3, if (appendPreparedHead) review.operationCids.last() else null)
                        statement.setString(4, accountId.toString())
                        statement.setString(5, review.did.toString())
                        statement.setString(6, handle)
                        statement.executeUpdate() == 1
                    }
            }
        }

    suspend fun dueReviews(limit: Int): List<PendingPlcPublicationReview> =
        withContext(Dispatchers.IO) {
            source.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT id, account_id, did, handle, operations FROM plc_publication_reviews
                        WHERE state IN ('authorized', 'uncertain') AND next_check_at <= now()
                        ORDER BY next_check_at, id LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, limit)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add(
                                        PendingPlcPublicationReview(
                                            reviewId = Uuid.parse(rows.getString("id")),
                                            accountId = Uuid.parse(rows.getString("account_id")),
                                            review =
                                                SavedPlcPublicationReview(
                                                    did = rows.getString("did"),
                                                    handle = rows.getString("handle"),
                                                    operations =
                                                        operationJson.decodeFromString<List<PlcOperation>>(rows.getString("operations")),
                                                ),
                                        ),
                                    )
                                }
                            }
                        }
                    }
            }
        }

    suspend fun readActiveState(
        accountId: Uuid,
        did: String,
    ): PlcPublicationState? =
        withContext(Dispatchers.IO) {
            source.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT state FROM plc_publication_reviews
                        WHERE account_id = ?::uuid AND did = ? AND state IN ('authorized', 'uncertain', 'conflict')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, accountId.toString())
                        statement.setString(2, did)
                        statement.executeQuery().use { rows ->
                            if (rows.next()) PlcPublicationState.valueOf(rows.getString("state").uppercase()) else null
                        }
                    }
            }
        }

    suspend fun readReview(
        accountId: Uuid,
        reviewId: Uuid,
    ): SavedPlcPublicationReview? =
        withContext(Dispatchers.IO) {
            source.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT did, handle, operations FROM plc_publication_reviews
                        WHERE account_id = ?::uuid AND id = ?::uuid AND state = 'review' AND expires_at > now()
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, accountId.toString())
                        statement.setString(2, reviewId.toString())
                        statement.executeQuery().use { rows ->
                            if (!rows.next()) {
                                null
                            } else {
                                SavedPlcPublicationReview(
                                    did = rows.getString("did"),
                                    handle = rows.getString("handle"),
                                    operations = operationJson.decodeFromString<List<PlcOperation>>(rows.getString("operations")),
                                )
                            }
                        }
                    }
            }
        }

    /**
     * Holds the account's publication lock while advancing its review. This uses a dedicated
     * transaction so transaction-pooled PostgreSQL keeps the advisory lock on one backend.
     */
    private suspend fun <T : Any> withAccountClaim(
        accountId: Uuid,
        advance: suspend () -> T,
    ): T? =
        workers.withPermit {
            withContext(Dispatchers.IO) {
                source.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val acquired =
                            connection.prepareStatement("SELECT pg_try_advisory_xact_lock(271392, hashtext(?))").use { statement ->
                                statement.setString(1, accountId.toString())
                                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                            }
                        if (acquired) advance() else null
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { connection.rollback() }
                    }
                }
            }
        }

    suspend fun advance(
        accountId: Uuid,
        reviewId: Uuid,
        review: PlcPublicationReview,
        worker: PlcPublicationWorker,
    ): PlcPublicationOutcome =
        withAccountClaim(accountId) {
            if (!scheduleNextCheck(accountId, reviewId)) return@withAccountClaim PlcPublicationOutcome.Pending
            val result =
                withTimeoutOrNull(10_000L) {
                    worker.advance(
                        review,
                        markAttempt = { markAttempt(accountId, reviewId, review) },
                        confirm = { confirm(accountId, reviewId, review) },
                    )
                } ?: PlcPublicationOutcome.Pending
            if (result == PlcPublicationOutcome.Conflict) {
                update(accountId, reviewId, review, CONFLICT)
            }
            result
        } ?: PlcPublicationOutcome.Pending

    /** Stop a review whose identity can no longer be reconstructed. Preserve uncertain history. */
    suspend fun invalidate(
        accountId: Uuid,
        reviewId: Uuid,
    ) {
        withAccountClaim(accountId) {
            updatePendingReview(
                accountId,
                reviewId,
                """
                UPDATE plc_publication_reviews
                SET state = CASE WHEN first_attempt_at IS NULL THEN 'superseded' ELSE 'conflict' END
                WHERE account_id = ?::uuid AND id = ?::uuid AND state IN ('authorized', 'uncertain')
                """.trimIndent(),
            )
        }
    }

    private suspend fun scheduleNextCheck(
        accountId: Uuid,
        reviewId: Uuid,
    ): Boolean =
        updatePendingReview(
            accountId,
            reviewId,
            """
            UPDATE plc_publication_reviews SET next_check_at = now() + interval '15 seconds'
            WHERE account_id = ?::uuid AND id = ?::uuid AND state IN ('authorized', 'uncertain')
                AND next_check_at <= now()
            """.trimIndent(),
        )

    private suspend fun updatePendingReview(
        accountId: Uuid,
        reviewId: Uuid,
        sql: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            source.connection.use { connection ->
                connection.autoCommit = true
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, accountId.toString())
                    statement.setString(2, reviewId.toString())
                    statement.executeUpdate() == 1
                }
            }
        }

    /** Called only after strict passkey verification for this review and account. */
    suspend fun authorize(
        accountId: Uuid,
        reviewId: Uuid,
        review: PlcPublicationReview,
    ): Boolean = update(accountId, reviewId, review, AUTHORIZE)

    /** Commits uncertainty before the worker sends an operation. */
    suspend fun markAttempt(
        accountId: Uuid,
        reviewId: Uuid,
        review: PlcPublicationReview,
    ): Boolean = update(accountId, reviewId, review, ATTEMPT)

    /** The directory read-back passed. Update the receipt and the account in one statement. */
    suspend fun confirm(
        accountId: Uuid,
        reviewId: Uuid,
        review: PlcPublicationReview,
    ): Boolean = update(accountId, reviewId, review, CONFIRM, recordConfirmedHead = true)

    private suspend fun update(
        accountId: Uuid,
        reviewId: Uuid,
        review: PlcPublicationReview,
        sql: String,
        recordConfirmedHead: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val operations = operationJson.encodeToString(review.operations())
            source.connection.use { connection ->
                connection.autoCommit = false
                try {
                    // Take the lock before the update so the update sees a fresh snapshot after a concurrent authorization.
                    connection.prepareStatement("SELECT id FROM accounts WHERE id = ?::uuid FOR UPDATE").use { statement ->
                        statement.setString(1, accountId.toString())
                        statement.executeQuery().close()
                    }
                    val updated =
                        connection.prepareStatement(sql).use { statement ->
                            statement.setString(1, accountId.toString())
                            statement.setString(2, review.did.toString())
                            statement.setString(3, review.signingKeyMultibase)
                            statement.setString(4, review.recoveryDidKey)
                            statement.setString(5, reviewId.toString())
                            statement.setString(6, operations)
                            statement.executeUpdate() == 1
                        }
                    if (updated && recordConfirmedHead) {
                        connection.prepareStatement(RECORD_CONFIRMED_HEAD).use { statement ->
                            statement.setString(1, reviewId.toString())
                            statement.executeUpdate()
                        }
                    }
                    connection.commit()
                    updated
                } finally {
                    connection.rollback()
                }
            }
        }

    private companion object {
        private val operationJson = Json { encodeDefaults = true }

        private const val CURRENT_REVIEW = """
            WITH current_account AS (
                SELECT id, did, handle FROM accounts
                WHERE id = ?::uuid AND did = ? AND signing_key_public = ? AND plc_recovery_did_key = ?
                    AND is_active AND moderation IS NULL AND public_identity_did IS DISTINCT FROM did
            ), current_review AS (
                SELECT r.id, r.account_id, r.did, clock_timestamp() AS checked_at FROM plc_publication_reviews r
                JOIN current_account a ON a.id = r.account_id AND a.did = r.did AND a.handle = r.handle
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN r.prepared_cid IS NULL THEN r.operations ELSE r.operations - (jsonb_array_length(r.operations) - 1)
                        END AS operations
                ) AS private_history
                WHERE r.id = ?::uuid AND r.operations = ?::jsonb
                    AND (
                        SELECT count(*) FROM hosted_plc_operations o WHERE o.account_id = a.id AND o.did = a.did
                    ) = jsonb_array_length(private_history.operations)
                    AND NOT EXISTS (
                        SELECT 1 FROM jsonb_array_elements(private_history.operations) AS reviewed(operation)
                        WHERE NOT EXISTS (
                            SELECT 1 FROM hosted_plc_operations o
                            WHERE o.account_id = a.id AND o.did = a.did
                                AND o.operation_json::jsonb = reviewed.operation
                        )
                    )
            )
        """
        private const val AUTHORIZE =
            CURRENT_REVIEW + """
            UPDATE plc_publication_reviews r SET state = 'authorized', authorized_at = c.checked_at
            FROM current_review c WHERE r.id = c.id AND r.state = 'review' AND r.expires_at > c.checked_at
                AND NOT EXISTS (
                    SELECT 1 FROM plc_publication_reviews other WHERE other.account_id = r.account_id
                        AND other.state IN ('authorized', 'uncertain', 'conflict')
                )
        """
        private const val ATTEMPT =
            CURRENT_REVIEW + """
            UPDATE plc_publication_reviews r
            SET state = 'uncertain', first_attempt_at = COALESCE(r.first_attempt_at, now())
            FROM current_review c WHERE r.id = c.id AND r.state IN ('authorized', 'uncertain')
        """
        private const val CONFLICT =
            CURRENT_REVIEW + """
            UPDATE plc_publication_reviews r SET state = 'conflict'
            FROM current_review c WHERE r.id = c.id AND r.state IN ('authorized', 'uncertain')
        """
        private const val CONFIRM =
            CURRENT_REVIEW + """, confirmed AS (
            UPDATE plc_publication_reviews r SET state = 'confirmed', confirmed_at = now()
            FROM current_review c WHERE r.id = c.id AND r.state IN ('authorized', 'uncertain')
            RETURNING r.account_id, r.did
        )
        UPDATE accounts a SET public_identity_did = c.did FROM confirmed c
        WHERE a.id = c.account_id AND a.did = c.did
        """
        private const val RECORD_CONFIRMED_HEAD = """
            INSERT INTO hosted_plc_operations (id, account_id, did, cid, prev_cid, operation_type, operation_json, created_at)
            SELECT id, account_id, did, prepared_cid, (operations -> -1) ->> 'prev', (operations -> -1) ->> 'type',
                (operations -> -1)::text, now()
            FROM plc_publication_reviews WHERE id = ?::uuid AND state = 'confirmed' AND prepared_cid IS NOT NULL
        """
    }
}

internal data class SavedPlcPublicationReview(
    val did: String,
    val handle: String,
    val operations: List<PlcOperation>,
)

internal enum class PlcPublicationState { AUTHORIZED, UNCERTAIN, CONFLICT }

@OptIn(ExperimentalUuidApi::class)
internal data class PendingPlcPublicationReview(
    val reviewId: Uuid,
    val accountId: Uuid,
    val review: SavedPlcPublicationReview,
)
