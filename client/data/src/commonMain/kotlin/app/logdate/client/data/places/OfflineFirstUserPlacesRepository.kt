package app.logdate.client.data.places

import app.logdate.client.data.journals.ArchiveRecoveryReceipts
import app.logdate.client.data.journals.DirectArchiveRecoveryTransactions
import app.logdate.client.database.dao.UserPlaceDao
import app.logdate.client.database.entities.UserPlaceEntity
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.shared.model.Place
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.uuid.Uuid

class OfflineFirstUserPlacesRepository(
    private val userPlaceDao: UserPlaceDao,
    private val transactionManager: SyncTransactionManager = DirectArchiveRecoveryTransactions,
    private val recoveryReceipts: ArchiveRecoveryReceipts = ArchiveRecoveryReceipts.None,
    private val currentRecoveryScope: suspend () -> JournalMergeScope? = { null },
) : UserPlacesRepository {
    override suspend fun getAllPlaces(): List<Place> = userPlaceDao.getAllPlaces().map { place -> place.toModel() }

    override fun observeAllPlaces(): Flow<List<Place>> =
        userPlaceDao.observeAllPlaces().map { places ->
            places.map { place -> place.toModel() }
        }

    override suspend fun getPlacesNear(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
    ): List<Place> {
        val latitudeDelta = radiusMeters / METERS_PER_DEGREE_LATITUDE
        val longitudeScale = cos(latitude * PI / 180.0).let { scale -> if (scale == 0.0) 0.000001 else scale }
        val longitudeDelta = radiusMeters / (METERS_PER_DEGREE_LATITUDE * longitudeScale)

        return userPlaceDao
            .getPlacesInBoundingBox(
                minLatitude = latitude - latitudeDelta,
                maxLatitude = latitude + latitudeDelta,
                minLongitude = longitude - longitudeDelta,
                maxLongitude = longitude + longitudeDelta,
            ).map { place ->
                place.toModel()
            }.filter { place ->
                calculateDistanceMeters(
                    lat1 = latitude,
                    lon1 = longitude,
                    lat2 = place.latitude,
                    lon2 = place.longitude,
                ) <= radiusMeters
            }.sortedBy { place ->
                calculateDistanceMeters(
                    lat1 = latitude,
                    lon1 = longitude,
                    lat2 = place.latitude,
                    lon2 = place.longitude,
                )
            }
    }

    override suspend fun getPlaceById(placeId: String): Place? = userPlaceDao.getPlaceById(placeId)?.toModel()

    override suspend fun createPlace(place: Place): Result<Place> =
        runCatching {
            val userPlace = place.asUserDefinedPlace()
            userPlaceDao.insertPlace(userPlace.toEntity())
            userPlace
        }.onFailure { error ->
            Napier.e("Failed to create user place", error)
        }

    override suspend fun updatePlace(place: Place): Result<Place> =
        runCatching {
            val userPlace = place.asUserDefinedPlace()
            val existingPlace = userPlaceDao.getPlaceById(userPlace.id.toString())
            userPlaceDao.updatePlace(userPlace.toEntity(existingCreatedAt = existingPlace?.createdAt))
            userPlace
        }.onFailure { error ->
            Napier.e("Failed to update user place", error)
        }

    override suspend fun restorePlaceForArchive(
        place: Place,
        scope: JournalMergeScope?,
        recoveryId: String,
    ): Result<Unit> =
        runCatching {
            transactionManager.withTransaction {
                check(currentRecoveryScope() == scope) { "Place recovery account changed" }
                val id = place.uid.toString()
                if (scope != null && recoveryReceipts.contains(scope, "PLACE", recoveryId, id)) return@withTransaction
                if (userPlaceDao.getPlaceById(id) == null) userPlaceDao.insertPlace(place.asUserDefinedPlace().toEntity())
                if (scope != null) recoveryReceipts.record(scope, "PLACE", recoveryId, id)
                check(currentRecoveryScope() == scope) { "Place recovery account changed" }
            }
        }

    override suspend fun deletePlace(placeId: String): Result<Unit> =
        runCatching {
            userPlaceDao.deletePlaceById(placeId)
        }.onFailure { error ->
            Napier.e("Failed to delete user place", error)
        }

    override suspend fun searchPlaces(query: String): List<Place> = userPlaceDao.searchPlaces(query).map { place -> place.toModel() }

    private fun UserPlaceEntity.toModel() =
        Place.UserDefined(
            id = Uuid.parse(id),
            displayName = name,
            lat = latitude,
            lng = longitude,
            radiusMeters = radiusMeters,
            description = description,
            category = category,
        )

    private fun Place.UserDefined.toEntity(existingCreatedAt: Long? = null): UserPlaceEntity {
        val now = Clock.System.now().toEpochMilliseconds()
        return UserPlaceEntity(
            id = id.toString(),
            name = displayName,
            latitude = lat,
            longitude = lng,
            radiusMeters = radiusMeters,
            description = description,
            createdAt = existingCreatedAt ?: now,
            updatedAt = now,
            category = category,
        )
    }

    private fun Place.asUserDefinedPlace(): Place.UserDefined =
        this as? Place.UserDefined
            ?: error("UserPlacesRepository only supports Place.UserDefined instances")

    private fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val earthRadius = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * PI / 180) * cos(lat2 * PI / 180) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    }
}
