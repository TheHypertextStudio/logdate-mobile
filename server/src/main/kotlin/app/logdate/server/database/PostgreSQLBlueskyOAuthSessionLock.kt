package app.logdate.server.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.InProcessOAuthSessionLock
import studio.hypertext.atproto.oauth.client.OAuthSessionLock
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

/**
 * Serializes refreshes across instances using a pinned transaction, including behind a transaction
 * pooler. [source] must be a dedicated bounded pool: session reads/writes use the ordinary data pool.
 */
class PostgreSQLBlueskyOAuthSessionLock(
    private val source: DataSource,
) : OAuthSessionLock {
    private val local = InProcessOAuthSessionLock()

    override suspend fun <T> withLock(
        did: AtprotoDid,
        block: suspend () -> T,
    ): T =
        local.withLock(did) {
            withContext(Dispatchers.IO) {
                source.connection.use { connection ->
                    connection.autoCommit = false
                    var failure: Throwable? = null
                    try {
                        withTimeout(10_000) {
                            while (!tryLock(connection, key(did))) delay(50)
                        }
                        currentCoroutineContext().ensureActive()
                        block()
                    } catch (problem: Throwable) {
                        failure = problem
                        throw problem
                    } finally {
                        withContext(NonCancellable) { release(connection, failure) }
                    }
                }
            }
        }

    private fun tryLock(
        connection: Connection,
        key: Long,
    ): Boolean =
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use {
            it.queryTimeout = 2
            it.setLong(1, key)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }

    private fun release(
        connection: Connection,
        failure: Throwable?,
    ) {
        try {
            connection.rollback()
        } catch (problem: Throwable) {
            // A transaction whose rollback was not confirmed must never return to the pool alive.
            try {
                connection.abort { it.run() }
            } catch (abortFailure: Throwable) {
                problem.addSuppressed(abortFailure)
            }
            if (failure == null) throw problem else failure.addSuppressed(problem)
        }
    }

    private fun key(did: AtprotoDid): Long =
        ByteBuffer
            .wrap(MessageDigest.getInstance("SHA-256").digest("logdate:bluesky-oauth:$did".encodeToByteArray()))
            .long
}
