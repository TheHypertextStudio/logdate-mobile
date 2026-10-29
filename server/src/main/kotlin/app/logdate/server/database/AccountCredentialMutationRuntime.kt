package app.logdate.server.database

import app.logdate.server.bluesky.connection.AccountCredentialMutationLock
import app.logdate.server.bluesky.connection.InProcessAccountCredentialMutationLock
import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

internal class AccountCredentialMutationRuntime(
    val lock: AccountCredentialMutationLock,
    private val ownedPool: HikariDataSource? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) ownedPool?.close()
    }
}

internal fun createAccountCredentialMutationRuntime(
    isDatabaseAvailable: Boolean,
    sourceProvider: () -> DataSource? = { DatabaseConfig.initializedDataSource },
    poolFactory: () -> HikariDataSource = { DatabaseConfig.createDataSource() as HikariDataSource },
): AccountCredentialMutationRuntime {
    if (!isDatabaseAvailable) return AccountCredentialMutationRuntime(InProcessAccountCredentialMutationLock())
    val source = checkNotNull(sourceProvider()) { "Credential coordination requires the initialized application database" }
    val pool = poolFactory()
    require(pool !== source) { "Credential coordination needs a separate owned pool" }
    try {
        check(!pool.isClosed)
        pool.minimumIdle = 0
        pool.maximumPoolSize = 2
        pool.connectionTimeout = 5000
        pool.validationTimeout = 2000
        return AccountCredentialMutationRuntime(PostgreSQLAccountCredentialMutationLock(pool), pool)
    } catch (failure: Throwable) {
        pool.close()
        throw failure
    }
}
