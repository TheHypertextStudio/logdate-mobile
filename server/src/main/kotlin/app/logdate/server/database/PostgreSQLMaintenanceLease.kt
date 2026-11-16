package app.logdate.server.database

import app.logdate.server.MaintenanceLease
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

/**
 * A [MaintenanceLease] that every instance sharing the database respects: a PostgreSQL advisory lock
 * named [name], held on one pooled connection while the pass runs and released when it ends. The lock
 * belongs to the connection's session, so an instance that stops mid-pass frees it too.
 */
internal class PostgreSQLMaintenanceLease(
    private val name: String,
    dataSource: DataSource? = null,
) : MaintenanceLease {
    private val source: DataSource by lazy {
        dataSource ?: checkNotNull(DatabaseConfig.initializedDataSource) { "Maintenance database has not been initialized" }
    }

    override suspend fun runExclusively(pass: suspend () -> Unit): Boolean {
        val connection = withContext(Dispatchers.IO) { source.connection.apply { autoCommit = true } }
        try {
            if (!withContext(Dispatchers.IO) { connection.tryLock() }) return false
            try {
                pass()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { connection.unlock() }
            }
            return true
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { connection.close() }
        }
    }

    private fun Connection.tryLock(): Boolean =
        prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { it.next() && it.getBoolean(1) }
        }

    /** A connection that cannot release the lock is discarded, which ends its session and the lock with it. */
    private fun Connection.unlock() {
        try {
            prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().close()
            }
        } catch (failure: Exception) {
            Napier.e("Releasing the maintenance lease failed; discarding its connection", failure)
            runCatching { abort(Runnable::run) }
        }
    }
}
