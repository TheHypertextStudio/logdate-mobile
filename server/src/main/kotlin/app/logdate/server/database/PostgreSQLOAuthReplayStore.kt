package app.logdate.server.database

import app.logdate.server.oauth.OAuthReplayStore
import app.logdate.server.oauth.replayKeyHash
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * The replay store every server instance shares. Recording a value is one insert that does
 * nothing when the value is already there, so two instances racing to accept the same value
 * cannot both succeed.
 */
internal class PostgreSQLOAuthReplayStore(
    private val clock: Clock = Clock.System,
) : OAuthReplayStore {
    override suspend fun firstUse(
        key: String,
        ttl: Duration,
    ): Boolean =
        dbQuery {
            val now = clock.now()
            val hash = replayKeyHash(key)
            removeExpired(now)
            // An expired record of this same value no longer blocks it.
            OAuthUsedValuesTable.deleteWhere {
                (OAuthUsedValuesTable.valueHash eq hash) and (OAuthUsedValuesTable.expiresAt lessEq now)
            }
            OAuthUsedValuesTable
                .insertIgnore {
                    it[valueHash] = hash
                    it[expiresAt] = now + ttl
                }.insertedCount == 1
        }

    /** Deletes up to [CLEANUP_BATCH] expired rows, so each use does a bounded amount of cleanup. */
    private fun removeExpired(now: kotlin.time.Instant) {
        val expired =
            OAuthUsedValuesTable
                .select(OAuthUsedValuesTable.valueHash)
                .where { OAuthUsedValuesTable.expiresAt lessEq now }
                .limit(CLEANUP_BATCH)
                .map { it[OAuthUsedValuesTable.valueHash] }
        if (expired.isEmpty()) return
        // Another instance may record one of these values again after the select, so the delete
        // re-checks expiry and leaves a fresh record alone.
        OAuthUsedValuesTable.deleteWhere {
            (OAuthUsedValuesTable.valueHash inList expired) and (OAuthUsedValuesTable.expiresAt lessEq now)
        }
    }

    private companion object {
        const val CLEANUP_BATCH = 100
    }
}
