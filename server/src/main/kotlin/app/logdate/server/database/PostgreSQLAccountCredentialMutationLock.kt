@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.bluesky.connection.AccountCredentialMutationLock
import app.logdate.server.bluesky.connection.InProcessAccountCredentialMutationLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource
import kotlin.uuid.Uuid

/**
 * Acquire before the OAuth DID lock. [source] must be its own bounded lock pool: sharing the
 * nested DID pool could fill it with account holders that still need a DID connection.
 */
internal class PostgreSQLAccountCredentialMutationLock(
    private val source: DataSource,
) : AccountCredentialMutationLock {
    private val local = InProcessAccountCredentialMutationLock()

    override suspend fun <T> withLock(
        accountId: Uuid,
        block: suspend () -> T,
    ): T = local.withLock(accountId) { withDatabaseLock(accountId, block) }

    private suspend fun <T> withDatabaseLock(
        accountId: Uuid,
        block: suspend () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            source.connection.use { connection ->
                connection.autoCommit = false
                var failure: Throwable? = null
                try {
                    val digest =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest("logdate:account-credential-mutation:$accountId".encodeToByteArray())
                    val key = ByteBuffer.wrap(digest).long
                    withTimeout(10_000) {
                        while (!tryLock(connection, key)) delay(50)
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
            try {
                connection.abort { it.run() }
            } catch (abortFailure: Throwable) {
                problem.addSuppressed(abortFailure)
            }
            if (failure == null) throw problem else failure.addSuppressed(problem)
        }
    }
}
