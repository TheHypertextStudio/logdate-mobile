package app.logdate.server.database.support

import app.logdate.server.database.AccountsTable
import app.logdate.server.database.HostedPlcOperationsTable
import app.logdate.server.database.IdentityAdoptionOperationsTable
import app.logdate.server.database.IdentityAdoptionReservationsTable
import app.logdate.server.database.LegacyObjectDeletionsTable
import app.logdate.server.database.ObjectUploadIntentsTable
import app.logdate.server.database.RetiredAccountIdsTable
import app.logdate.server.database.RetiredIdentityDidsTable
import app.logdate.server.database.SpaceMigrationsTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

fun withH2Database(
    vararg tables: Table,
    block: () -> Unit,
) {
    val completeTables =
        (
            tables.toList() +
                if (AccountsTable in tables) {
                    listOf(
                        ObjectUploadIntentsTable,
                        LegacyObjectDeletionsTable,
                        HostedPlcOperationsTable,
                        IdentityAdoptionOperationsTable,
                        IdentityAdoptionReservationsTable,
                        RetiredIdentityDidsTable,
                        RetiredAccountIdsTable,
                        SpaceMigrationsTable,
                    )
                } else {
                    emptyList()
                }
        ).distinct().toTypedArray()
    val dbName = "server_test_${System.nanoTime()}"
    val database =
        Database.connect(
            url = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
    val previousDatabase = TransactionManager.defaultDatabase
    TransactionManager.defaultDatabase = database

    transaction {
        SchemaUtils.create(*completeTables)
    }

    try {
        block()
    } finally {
        transaction {
            SchemaUtils.drop(*completeTables)
        }
        TransactionManager.defaultDatabase = previousDatabase
    }
}
