package app.logdate.server.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Runs [statement] in a database transaction on [Dispatchers.IO], and joins the caller's
 * transaction when there is one.
 *
 * JDBC blocks its thread until PostgreSQL answers. Ktor sizes its request workers to the CPU count,
 * so a query run on one of them stalls every request queued behind it, `/health` included. Suspend
 * code reaches the database through this function rather than through `transaction {}`.
 */
suspend fun <T> dbQuery(
    database: Database? = null,
    statement: JdbcTransaction.() -> T,
): T = withContext(Dispatchers.IO) { transaction(database) { statement() } }
