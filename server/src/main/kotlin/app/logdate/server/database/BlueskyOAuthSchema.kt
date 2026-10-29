package app.logdate.server.database

import javax.sql.DataSource

internal fun requireAtprotoConnectionSchema(source: DataSource) {
    requireBlueskyOAuthSchema(source)
    try {
        source.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                        "WHERE conrelid = 'webauthn_challenges'::regclass AND conname = 'challenge_type_valid' " +
                        "AND contype = 'c' AND convalidated",
                ).use { statement ->
                    statement.queryTimeout = 5
                    statement.executeQuery().use { rows ->
                        check(rows.next() && rows.getString(1).contains("'atproto_disconnect'"))
                    }
                }
        }
    } catch (_: Exception) {
        throw IllegalStateException("Atmosphere connection proof storage is unavailable; apply its migrations before enabling it")
    }
}

/** Read-only startup validation; schema changes belong to the deployment migration step. */
internal fun requireBlueskyOAuthSchema(source: DataSource) {
    try {
        source.connection.use { connection ->
            for (query in listOf(
                "SELECT identity_kind, did, storage_mode FROM accounts LIMIT 0",
                "SELECT state_key, encrypted_payload, expires_at FROM bluesky_oauth_states LIMIT 0",
                "SELECT did, encrypted_payload, updated_at FROM bluesky_oauth_sessions LIMIT 0",
                "SELECT attempt_key, encrypted_payload, expires_at FROM bluesky_sign_in_attempts LIMIT 0",
                "SELECT handoff_key, encrypted_payload, expires_at FROM bluesky_account_handoffs LIMIT 0",
                "SELECT did, state, revision, observed_at, last_activity_at FROM atproto_connections LIMIT 0",
                "SELECT decision_key, account_id, did, expected_revision, challenge_hash, created_at, expires_at, " +
                    "authorized_at, completed_at, disconnected_revision, remote_confirmed FROM atproto_disconnect_decisions LIMIT 0",
            )) {
                connection.prepareStatement(query).use { statement ->
                    statement.queryTimeout = 5
                    statement.executeQuery().close()
                }
            }
        }
    } catch (_: Exception) {
        throw IllegalStateException("Bluesky sign-in storage is unavailable; apply its migrations before enabling sign-in")
    }
}
