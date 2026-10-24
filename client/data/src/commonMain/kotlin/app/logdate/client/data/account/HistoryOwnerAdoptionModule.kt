package app.logdate.client.data.account

import app.logdate.client.database.dao.HistoryOwnerAdoptionDao
import app.logdate.client.database.entities.HistoryRecordEntity
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.repository.location.HistoryOwnerAdoptionRepair
import app.logdate.shared.model.location.HistoryPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val historyOwnerAdoptionModule =
    module {
        single<HistoryOwnerAdoptionRepair> { get<HistoryOwnerAdoption>() }
        single {
            val dao = get<HistoryOwnerAdoptionDao>()
            val device = get<DeviceIdProvider>()
            HistoryOwnerAdoption(
                storage = get(),
                owner = get(),
                config = get(),
                deviceId = { device.getDeviceId().value.toString() },
                moveRows = { old, new, origin, deviceId -> dao.adopt(old, new, origin, deviceId) { it.rewriteHistoryOwner(new) } },
                countRows = dao::localHistoryCount,
            )
        }
    }

internal fun HistoryRecordEntity.rewriteHistoryOwner(newOwner: String): HistoryRecordEntity {
    val plaintext = payload ?: return this
    val rewritten =
        when (val decoded = Json.decodeFromString<HistoryPayload>(plaintext)) {
            is HistoryPayload.Observation -> {
                check(decoded.value.ownerId == ownerId) { "Observation owner does not match its local scope" }
                decoded.copy(value = decoded.value.copy(ownerId = newOwner))
            }

            is HistoryPayload.Activity -> {
                check(decoded.value.ownerId == ownerId) { "Activity owner does not match its local scope" }
                decoded.copy(value = decoded.value.copy(ownerId = newOwner))
            }

            else -> {
                decoded
            }
        }
    return copy(payload = Json.encodeToString<HistoryPayload>(rewritten))
}
