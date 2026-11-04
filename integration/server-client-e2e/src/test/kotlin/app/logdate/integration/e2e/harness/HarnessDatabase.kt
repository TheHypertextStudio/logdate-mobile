package app.logdate.integration.e2e.harness

import app.logdate.server.database.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.UUID

internal val harnessDatabaseApplicationName = "logdate-e2e-harness-${UUID.randomUUID()}"

internal fun harnessDatabaseUrl(): String {
    val url = checkNotNull(System.getenv("DATABASE_URL"))
    val separator = if ('?' in url) '&' else '?'
    return "$url${separator}ApplicationName=$harnessDatabaseApplicationName"
}

internal suspend fun <T> withHarnessDatabase(block: suspend (Boolean) -> T): T {
    if (System.getenv("DATABASE_URL").isNullOrBlank()) return block(false)

    val source = DatabaseConfig.createDataSource(databaseUrl = harnessDatabaseUrl())
    var database: Database? = null
    try {
        database = DatabaseConfig.initializeDatabase(source)
        TransactionManager.defaultDatabase = database
        return block(true)
    } finally {
        try {
            database?.let(TransactionManager::closeAndUnregister)
        } finally {
            (source as AutoCloseable).close()
        }
    }
}
