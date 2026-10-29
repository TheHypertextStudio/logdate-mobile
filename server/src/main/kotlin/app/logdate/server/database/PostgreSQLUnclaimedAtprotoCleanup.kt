package app.logdate.server.database

import app.logdate.server.bluesky.connection.AtprotoConnectionProvider
import app.logdate.server.bluesky.connection.ConnectionChanged
import io.github.aakira.napier.Napier
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.OAuthRevocationException
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

internal class PostgreSQLUnclaimedAtprotoCleanup(
    private val source: DataSource,
    private val provider: AtprotoConnectionProvider,
    private val clock: Clock,
) {
    private data class Candidate(
        val did: AtprotoDid,
        val revision: Long,
    )

    suspend fun runOnce() {
        val candidates =
            source.blueskyTransaction {
                prepareStatement("$ELIGIBLE ORDER BY 1 LIMIT 100").use {
                    it.setTimestamp(1, (clock.now() - 1.hours).timestamp())
                    it.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(Candidate(AtprotoDid.require(rows.getString(1)), rows.getLong(2))) }
                    }
                }
            }
        for (candidate in candidates) {
            try {
                provider.revokeIf(
                    candidate.did,
                    beforeRevoke = { source.atprotoStateTransaction(candidate.did) { matches(candidate) } },
                    removeLocal = {
                        source.atprotoStateTransaction(candidate.did) {
                            if (!matches(candidate)) throw ConnectionChanged()
                            eraseAtprotoConnection(candidate.did)
                        }
                    },
                )
            } catch (_: OAuthRevocationException) {
                Napier.w("Unclaimed connection removed; remote revocation was not confirmed")
            }
        }
    }

    private fun Connection.matches(candidate: Candidate): Boolean =
        prepareStatement(
            "$ELIGIBLE AND COALESCE(c.did, s.did) = ? AND COALESCE(c.revision, 0) = ?",
        ).use {
            it.setTimestamp(1, (clock.now() - 1.hours).timestamp())
            it.setString(2, candidate.did.toString())
            it.setLong(3, candidate.revision)
            it.executeQuery().use { rows -> rows.next() }
        }

    private companion object {
        const val ELIGIBLE =
            "SELECT COALESCE(c.did, s.did), COALESCE(c.revision, 0) " +
                "FROM atproto_connections c FULL OUTER JOIN bluesky_oauth_sessions s ON c.did = s.did " +
                "WHERE COALESCE(c.last_activity_at, s.updated_at, c.observed_at) <= ? " +
                "AND NOT EXISTS (SELECT 1 FROM accounts a WHERE a.did = COALESCE(c.did, s.did) " +
                "AND a.identity_kind = 'external' AND a.is_active = TRUE)"
    }
}
