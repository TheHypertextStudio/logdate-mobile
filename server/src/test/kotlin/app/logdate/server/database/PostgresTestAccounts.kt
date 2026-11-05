package app.logdate.server.database

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Gives a PostgreSQL test an account row for [id]. The sync index refers to accounts,
 * so tests that keep their accounts in memory still need the row in the database.
 */
internal fun insertTestAccountRow(
    id: UUID,
    did: String? = null,
) {
    transaction {
        exec(
            "INSERT INTO accounts(id, username, display_name, created_at) " +
                "VALUES ('$id', 't${id.toString().replace("-", "").take(20)}', 'Test', now())",
        )
        if (did != null) AccountsTable.update({ AccountsTable.id eq id }) { it[AccountsTable.did] = did }
    }
}

/**
 * Deletes a test account's row, and with it the account's sync index, then the blob cleanup entry
 * that deleting an account queues, so a shared test database keeps nothing from the test.
 */
internal fun deleteTestAccountRow(id: UUID) {
    transaction {
        exec("DELETE FROM accounts WHERE id = '$id'")
        exec("DELETE FROM account_blob_cleanup WHERE owner_id = '$id'")
    }
}
