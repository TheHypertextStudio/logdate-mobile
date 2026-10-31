package app.logdate.server.database

import app.logdate.server.oauth.OAuthReplayStore
import app.logdate.server.oauth.replayKeyHash
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The replay store every server instance shares. Recording a value is one insert that does
 * nothing when the value is already there, so two instances racing to accept the same value
 * cannot both succeed.
 *
 * Expired values of other keys are left for [purgeExpired], which the OAuth maintenance loop runs,
 * so a check on every request costs two statements rather than four.
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

    override suspend fun purgeExpired(now: Instant): Int = dbQuery { OAuthUsedValuesTable.deleteWhere { expiresAt lessEq now } }
}
