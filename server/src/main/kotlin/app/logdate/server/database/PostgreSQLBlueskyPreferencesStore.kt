package app.logdate.server.database

import app.logdate.server.atproto.BlueskyPreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class PostgreSQLBlueskyPreferencesStore(
    private val database: Database? = null,
) : BlueskyPreferencesStore {
    override suspend fun read(accountId: Uuid): List<JsonElement> =
        withContext(Dispatchers.IO) {
            transaction(db = database) {
                BlueskyPreferencesTable
                    .selectAll()
                    .where { BlueskyPreferencesTable.accountId eq accountId.toJavaUUID() }
                    .singleOrNull()
                    ?.let { Json.decodeFromString<List<JsonElement>>(it[BlueskyPreferencesTable.preferences]) }
                    .orEmpty()
            }
        }

    override suspend fun replace(
        accountId: Uuid,
        preferences: List<JsonElement>,
    ) {
        val document = Json.encodeToString(preferences)
        withContext(Dispatchers.IO) {
            transaction(db = database) {
                BlueskyPreferencesTable.upsert(BlueskyPreferencesTable.accountId) {
                    it[BlueskyPreferencesTable.accountId] = accountId.toJavaUUID()
                    it[BlueskyPreferencesTable.preferences] = document
                }
            }
        }
    }
}

private object BlueskyPreferencesTable : Table("bluesky_preferences") {
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val preferences = text("preferences")

    override val primaryKey = PrimaryKey(accountId)
}
