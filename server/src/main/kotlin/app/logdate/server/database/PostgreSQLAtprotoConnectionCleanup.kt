package app.logdate.server.database

import app.logdate.server.bluesky.connection.AtprotoConnectionProvider
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.OAuthSessionLock
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

internal class PostgreSQLAtprotoConnectionCleanup(
    private val source: DataSource,
    private val sessionLock: OAuthSessionLock,
    private val clock: Clock = Clock.System,
    provider: AtprotoConnectionProvider? = null,
) {
    private val unclaimed = provider?.let { PostgreSQLUnclaimedAtprotoCleanup(source, it, clock) }

    suspend fun removeUnclaimed() {
        unclaimed?.runOnce()
    }

    suspend fun expireDecisions() {
        val candidates =
            source.blueskyTransaction {
                prepareStatement(
                    "SELECT DISTINCT did FROM atproto_disconnect_decisions " +
                        "WHERE (completed_at IS NULL AND expires_at <= ?) OR completed_at <= ? ORDER BY did LIMIT 100",
                ).use {
                    val now = clock.now()
                    it.setTimestamp(1, now.timestamp())
                    it.setTimestamp(2, (now - 24.hours).timestamp())
                    it.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(AtprotoDid.require(rows.getString(1))) }
                    }
                }
            }
        for (did in candidates) {
            // The same provider-work lock protects an authorized operation still completing after expiry.
            sessionLock.withLock(did) {
                source.atprotoStateTransaction(did) {
                    prepareStatement(
                        "DELETE FROM atproto_disconnect_decisions WHERE did = ? AND " +
                            "((completed_at IS NULL AND expires_at <= ?) OR completed_at <= ?)",
                    ).use {
                        val now = clock.now()
                        it.setString(1, did.toString())
                        it.setTimestamp(2, now.timestamp())
                        it.setTimestamp(3, (now - 24.hours).timestamp())
                        it.executeUpdate()
                    }
                }
            }
        }
    }
}
