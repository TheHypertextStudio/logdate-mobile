package app.logdate.client.sync.datalayer

import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * Converts [HealthSnapshotSyncData] instances to and from flat string maps
 * suitable for the Wear Data Layer API's DataMap format.
 *
 * Health snapshots flow watch-to-phone only (the watch captures sensor data).
 * The phone persists them as [app.logdate.client.database.entities.HealthSnapshotEntity].
 */
class HealthSnapshotDataMapper(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) {
    fun toDataMap(snapshot: HealthSnapshotSyncData): Map<String, String> {
        val jsonPayload = json.encodeToString(HealthSnapshotSyncData.serializer(), snapshot)
        return mapOf(
            KEY_UID to snapshot.id.toString(),
            KEY_JSON_PAYLOAD to jsonPayload,
        )
    }

    fun fromDataMap(map: Map<String, String>): HealthSnapshotSyncData {
        val jsonPayload =
            requireNotNull(map[KEY_JSON_PAYLOAD]) {
                "DataMap missing required key: $KEY_JSON_PAYLOAD"
            }
        return json.decodeFromString(HealthSnapshotSyncData.serializer(), jsonPayload)
    }

    companion object {
        const val KEY_UID = "uid"
        const val KEY_JSON_PAYLOAD = "jsonPayload"

        private const val HEALTH_PATH_PREFIX = "/logdate/health"

        fun healthPath(snapshotId: Uuid): String = "$HEALTH_PATH_PREFIX/$snapshotId"

        fun isHealthPath(path: String): Boolean = path.startsWith(HEALTH_PATH_PREFIX)

        fun healthIdFromPath(path: String): Uuid {
            val segment = path.removePrefix("$HEALTH_PATH_PREFIX/")
            return Uuid.parse(segment)
        }
    }
}
