package app.logdate.server.atproto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
internal data class BlueskyPreferences(
    val preferences: List<JsonElement>,
)

@OptIn(ExperimentalUuidApi::class)
interface BlueskyPreferencesStore {
    suspend fun read(accountId: Uuid): List<JsonElement>

    suspend fun replace(
        accountId: Uuid,
        preferences: List<JsonElement>,
    )
}

@OptIn(ExperimentalUuidApi::class)
internal class InMemoryBlueskyPreferencesStore : BlueskyPreferencesStore {
    private val documents = ConcurrentHashMap<Uuid, List<JsonElement>>()

    override suspend fun read(accountId: Uuid): List<JsonElement> = documents[accountId].orEmpty()

    override suspend fun replace(
        accountId: Uuid,
        preferences: List<JsonElement>,
    ) {
        if (preferences.isEmpty()) {
            documents.remove(accountId)
        } else {
            documents[accountId] = preferences.toList()
        }
    }
}
