package app.logdate.server.database

import app.logdate.server.bluesky.connection.ConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import studio.hypertext.atproto.identity.AtprotoDid
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.time.Instant

/** Short SQL serialization, in a namespace distinct from the OAuth client's provider-work lock. */
internal suspend fun <T> DataSource.atprotoStateTransaction(
    did: AtprotoDid,
    block: Connection.() -> T,
): T =
    blueskyTransaction {
        val digest = MessageDigest.getInstance("SHA-256").digest("atproto-connection-state:$did".encodeToByteArray())
        prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
            it.queryTimeout = 5
            it.setLong(1, ByteBuffer.wrap(digest).long)
            it.executeQuery().close()
        }
        block()
    }

internal fun Connection.atprotoConnectionSnapshot(did: AtprotoDid): ConnectionSnapshot? =
    prepareStatement("SELECT did, state, revision, observed_at FROM atproto_connections WHERE did = ?").use {
        it.setString(1, did.toString())
        it.executeQuery().use { rows -> if (rows.next()) rows.connectionSnapshot() else null }
    }

internal fun Connection.advanceAtprotoConnection(
    did: AtprotoDid,
    state: AtprotoConnectionState,
    observedAt: Instant,
    activityAt: Instant = observedAt,
): ConnectionSnapshot =
    prepareStatement(
        "INSERT INTO atproto_connections(did, state, revision, observed_at, last_activity_at) VALUES (?, ?, 1, ?, ?) " +
            "ON CONFLICT(did) DO UPDATE SET state = EXCLUDED.state, revision = atproto_connections.revision + 1, " +
            "observed_at = EXCLUDED.observed_at, " +
            "last_activity_at = CASE WHEN EXCLUDED.state = 'connected' THEN EXCLUDED.last_activity_at " +
            "ELSE atproto_connections.last_activity_at END RETURNING did, state, revision, observed_at",
    ).use {
        it.setString(1, did.toString())
        it.setString(2, state.name.lowercase())
        it.setTimestamp(3, observedAt.timestamp())
        it.setTimestamp(4, activityAt.timestamp())
        it.executeQuery().use { rows ->
            check(rows.next()) { "The connection state could not be saved" }
            rows.connectionSnapshot()
        }
    }

internal fun Timestamp.connectionInstant(): Instant {
    val value = toInstant()
    return Instant.fromEpochSeconds(value.epochSecond, value.nano)
}

internal fun Connection.eraseAtprotoConnection(did: AtprotoDid) {
    for (table in listOf("bluesky_oauth_sessions", "atproto_disconnect_decisions", "atproto_connections")) {
        prepareStatement("DELETE FROM $table WHERE did = ?").use {
            it.setString(1, did.toString())
            it.executeUpdate()
        }
    }
}

private fun ResultSet.connectionSnapshot(): ConnectionSnapshot =
    ConnectionSnapshot(
        did = AtprotoDid.require(getString("did")),
        state = AtprotoConnectionState.valueOf(getString("state").uppercase()),
        revision = getLong("revision"),
        observedAt = getTimestamp("observed_at").connectionInstant(),
    )
