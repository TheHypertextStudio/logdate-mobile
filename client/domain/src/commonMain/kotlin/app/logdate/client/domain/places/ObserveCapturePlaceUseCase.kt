package app.logdate.client.domain.places

import app.logdate.client.location.ClientLocationProvider
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.shared.model.Location
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlin.uuid.Uuid

/**
 * Where a capture is being taken: the coordinates, a name to show when one is known, and the
 * person's own saved place when the capture is inside one.
 */
data class CapturePlace(
    val location: Location,
    val name: String?,
    val savedPlaceId: Uuid? = null,
)

/**
 * Follows where the person is while they capture, named the way the rest of the app names places.
 *
 * Emits nothing when the app can't use location, or the person turned off recording where entries
 * are written, so callers show no place rather than an error or a prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveCapturePlaceUseCase(
    private val locationProvider: ClientLocationProvider,
    private val resolvePlace: suspend (Location) -> PlaceResolutionResult,
    private val settingsRepository: LocationTrackingSettingsRepository,
) {
    /** Follows the place while the person lets journal entries record where they were written. */
    operator fun invoke(): Flow<CapturePlace> =
        settingsRepository
            .observeSettings()
            .map { it.autoTrackForJournalEntries }
            .catch {
                Napier.w("Location settings are unreadable; the camera shows no place", it)
                emit(false)
            }.distinctUntilChanged()
            .flatMapLatest { allowed -> if (allowed && locationProvider.hasLocationPermission()) places() else emptyFlow() }

    private fun places(): Flow<CapturePlace> =
        locationProvider.currentLocation
            .onStart { freshFix()?.let { emit(it) } }
            .distinctUntilChanged { a, b -> a.distanceTo(b) < SAME_PLACE_METERS }
            .mapLatest { location -> name(location) }

    private suspend fun freshFix(): Location? =
        try {
            locationProvider.getCurrentLocation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("No location fix for the camera's place", e)
            null
        }

    private suspend fun name(location: Location): CapturePlace =
        try {
            when (val result = resolvePlace(location)) {
                is PlaceResolutionResult.UserDefinedPlace -> {
                    CapturePlace(location, result.place.name, result.place.uid)
                }

                is PlaceResolutionResult.ExternalSuggestion -> {
                    CapturePlace(location, result.suggestion.name)
                }

                is PlaceResolutionResult.CoarseLocation -> {
                    CapturePlace(location, result.address.run { subLocality ?: locality ?: adminArea })
                }

                is PlaceResolutionResult.UnknownLocation -> {
                    CapturePlace(location, null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not name the camera's place", e)
            CapturePlace(location, null)
        }

    private companion object {
        const val SAME_PLACE_METERS = 100.0
    }
}
