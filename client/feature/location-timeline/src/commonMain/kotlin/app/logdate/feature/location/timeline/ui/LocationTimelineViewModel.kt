package app.logdate.feature.location.timeline.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.location.CaptureLocationForTimelineReviewUseCase
import app.logdate.client.domain.location.DeleteLocationRangeUseCase
import app.logdate.client.domain.location.LocationMemoryPlace
import app.logdate.client.domain.location.LocationMemoryTimeFilter
import app.logdate.client.domain.location.LocationStop
import app.logdate.client.domain.location.ObserveLocationMemoryPlacesUseCase
import app.logdate.client.domain.location.ObserveLocationStopsUseCase
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.domain.places.PlaceResolutionResult
import app.logdate.client.domain.world.ObserveLocationUseCase
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.feature.location.timeline.ui.model.CurrentLocationUiModel
import app.logdate.feature.location.timeline.ui.model.LocationLabelSource
import app.logdate.feature.location.timeline.ui.model.LocationMemoryPreviewUiModel
import app.logdate.feature.location.timeline.ui.model.LocationPlaceUiModel
import app.logdate.feature.location.timeline.ui.model.LocationStopUiModel
import app.logdate.feature.location.timeline.ui.model.LocationTimelineErrorUiState
import app.logdate.feature.location.timeline.ui.model.LocationTimelineUiState
import app.logdate.feature.location.timeline.ui.model.toLocationTimelineErrorUiState
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.util.localTime
import app.logdate.util.toReadableDateShort
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.pinned_memory
import logdate.client.feature.location.timeline.generated.resources.places_current_location
import logdate.client.feature.location.timeline.generated.resources.places_pinned_location
import logdate.client.feature.location.timeline.generated.resources.places_source_coordinates
import logdate.client.feature.location.timeline.generated.resources.places_source_google_places
import logdate.client.feature.location.timeline.generated.resources.places_source_location
import logdate.client.feature.location.timeline.generated.resources.places_source_nearby_place
import logdate.client.feature.location.timeline.generated.resources.places_source_place
import logdate.client.feature.location.timeline.generated.resources.places_source_saved_place
import org.jetbrains.compose.resources.StringResource
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class LocationTimelineViewModel(
    private val observeLocationUseCase: ObserveLocationUseCase,
    private val observeLocationStopsUseCase: ObserveLocationStopsUseCase,
    private val observeLocationMemoryPlacesUseCase: ObserveLocationMemoryPlacesUseCase,
    private val placeResolutionCache: PlaceResolutionCache,
    private val deleteLocationRangeUseCase: DeleteLocationRangeUseCase,
    private val captureLocationForTimelineReviewUseCase: CaptureLocationForTimelineReviewUseCase,
    private val workContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    private sealed interface SectionState<out T> {
        data object Loading : SectionState<Nothing>

        data class Loaded<T>(
            val value: T,
        ) : SectionState<T>

        data class Failed(
            val throwable: Throwable,
        ) : SectionState<Nothing>

        val isLoading get() = this is Loading

        fun valueOrNull(): T? = (this as? Loaded)?.value
    }

    private val _uiState = MutableStateFlow<LocationTimelineUiState>(LocationTimelineUiState.Content())
    val uiState: StateFlow<LocationTimelineUiState> = _uiState.asStateFlow()
    private val selectedFilter = MutableStateFlow<LocationMemoryTimeFilter>(LocationMemoryTimeFilter.Last30Days)
    private val visiblePlaceCount = MutableStateFlow(DEFAULT_PLACE_PAGE_SIZE)
    private var observeTimelineJob: Job? = null

    init {
        captureLocationForTimelineReview()
        observeTimeline()
    }

    fun selectPlace(placeId: String) {
        transformContentState { copy(selectedPlaceId = placeId) }
    }

    fun dismissPlaceDetail() {
        transformContentState { copy(selectedPlaceId = null) }
    }

    fun selectFilter(filter: LocationMemoryTimeFilter) {
        selectedFilter.value = filter
        visiblePlaceCount.value = DEFAULT_PLACE_PAGE_SIZE
    }

    fun loadMorePlaces() {
        withContentState {
            if (canLoadMorePlaces) visiblePlaceCount.value += DEFAULT_PLACE_PAGE_SIZE
        }
    }

    fun deleteStop(stopId: String) {
        withContentState {
            val stop = recentStops.firstOrNull { it.id == stopId } ?: return@withContentState
            viewModelScope.launch {
                deleteLocationRangeUseCase(
                    startTime = stop.startTime,
                    endTime = stop.endTime + 1.milliseconds,
                ).onFailure { Napier.e("Failed to delete location stop ${stop.id}", it) }
            }
        }
    }

    private inline fun transformContentState(transform: LocationTimelineUiState.Content.() -> LocationTimelineUiState.Content) {
        val state = _uiState.value as? LocationTimelineUiState.Content ?: return
        _uiState.value = state.transform()
    }

    private inline fun withContentState(block: LocationTimelineUiState.Content.() -> Unit) {
        (_uiState.value as? LocationTimelineUiState.Content)?.block()
    }

    fun retry() {
        _uiState.value = LocationTimelineUiState.Content()
        observeTimeline()
    }

    private fun captureLocationForTimelineReview() {
        viewModelScope.launch {
            runCatching {
                captureLocationForTimelineReviewUseCase()
            }.onFailure { error ->
                Napier.w("Failed to capture location for timeline review", error)
            }
        }
    }

    private fun observeTimeline() {
        observeTimelineJob?.cancel()
        observeTimelineJob =
            viewModelScope.launch {
                combine(
                    observeCurrentLocationState(),
                    observeLocationStopsState(),
                    selectedFilter.flatMapLatest { filter ->
                        observeLocationMemoryPlacesState(filter)
                            .mapLatest { placesState -> filter to placesState }
                    },
                    visiblePlaceCount,
                ) { currentLocationState, stopsState, filterAndPlacesState, visibleCount ->
                    buildUiState(
                        currentLocationState = currentLocationState,
                        stopsState = stopsState,
                        selectedFilter = filterAndPlacesState.first,
                        placesState = filterAndPlacesState.second,
                        visibleCount = visibleCount,
                    )
                }.flowOn(workContext)
                    .catch { error ->
                        Napier.e("Failed to build location timeline", error)
                        emit(LocationTimelineUiState.Error(error.toLocationTimelineErrorUiState()))
                    }.collect { state ->
                        _uiState.value = state
                    }
            }
    }

    private fun observeCurrentLocationState(): Flow<SectionState<Location?>> =
        observeLocationUseCase()
            .distinctUntilChanged { a, b -> a.distanceTo(b) < 50.0 }
            .toSectionState()

    private fun observeLocationStopsState(): Flow<SectionState<List<LocationStop>>> = observeLocationStopsUseCase().toSectionState()

    private fun observeLocationMemoryPlacesState(filter: LocationMemoryTimeFilter) =
        observeLocationMemoryPlacesUseCase(filter).toSectionState()

    private fun <T> Flow<T>.toSectionState(): Flow<SectionState<T>> =
        mapLatest<T, SectionState<T>> { SectionState.Loaded(it) }
            .catch { error ->
                Napier.w("Failed to observe section", error)
                emit(SectionState.Failed(error))
            }.onStart { emit(SectionState.Loading) }

    private suspend fun buildUiState(
        currentLocationState: SectionState<Location?>,
        stopsState: SectionState<List<LocationStop>>,
        selectedFilter: LocationMemoryTimeFilter,
        placesState: SectionState<List<LocationMemoryPlace>>,
        visibleCount: Int,
    ): LocationTimelineUiState {
        detectBlockingError(currentLocationState, stopsState)?.let { error ->
            return LocationTimelineUiState.Error(error)
        }

        val currentLocation = currentLocationState.valueOrNull()
        val stops = stopsState.valueOrNull().orEmpty()
        val memoryPlaces = placesState.valueOrNull().orEmpty()

        val mappedStops = stops.map { stop -> stop.toUiModel() }
        val visibleLimit = visibleCount.coerceAtLeast(DEFAULT_PLACE_PAGE_SIZE)
        // Only a shown place lists its nearby stops, so places past the visible page skip that search.
        val mappedPlaces =
            memoryPlaces.mapIndexed { index, place -> place.toUiModel(if (index < visibleLimit) mappedStops else emptyList()) }
        val mappedCurrentLocation = currentLocation?.toCurrentLocationUiModel()
        val visiblePlaces = mappedPlaces.take(visibleLimit)
        val existingSelection = (_uiState.value as? LocationTimelineUiState.Content)?.selectedPlaceId
        val selectedPlaceId =
            visiblePlaces.firstOrNull { it.id == existingSelection }?.id
                ?: visiblePlaces.firstOrNull()?.id

        return LocationTimelineUiState.Content(
            isLoadingCurrentLocation = currentLocationState.isLoading,
            isLoadingPlaces = placesState.isLoading,
            isLoadingStops = stopsState.isLoading,
            currentLocation = mappedCurrentLocation,
            selectedFilter = selectedFilter,
            places = mappedPlaces,
            visiblePlaces = visiblePlaces,
            recentStops = mappedStops,
            selectedPlaceId = selectedPlaceId,
            canLoadMorePlaces = mappedPlaces.size > visiblePlaces.size,
        )
    }

    private fun detectBlockingError(
        currentLocationState: SectionState<Location?>,
        stopsState: SectionState<List<LocationStop>>,
    ): LocationTimelineErrorUiState? =
        sequenceOf(currentLocationState, stopsState)
            .filterIsInstance<SectionState.Failed>()
            .map { it.throwable.toLocationTimelineErrorUiState() }
            .firstOrNull {
                it is LocationTimelineErrorUiState.PermissionRequired ||
                    it is LocationTimelineErrorUiState.LocationServicesDisabled
            }

    private suspend fun Location.toCurrentLocationUiModel(): CurrentLocationUiModel {
        val resolvedPlace = placeResolutionCache.resolve(this)
        val title =
            when (resolvedPlace) {
                is PlaceResolutionResult.UserDefinedPlace -> resolvedPlace.place.name.toUiText()
                is PlaceResolutionResult.ExternalSuggestion -> resolvedPlace.suggestion.name.toUiText()
                is PlaceResolutionResult.CoarseLocation -> formatCoarseLocation(resolvedPlace.address)
                is PlaceResolutionResult.UnknownLocation -> Res.string.places_current_location.toUiText()
            }
        val subtitle =
            when (resolvedPlace) {
                is PlaceResolutionResult.ExternalSuggestion -> resolvedPlace.suggestion.address
                is PlaceResolutionResult.UserDefinedPlace -> formatCoordinates(latitude, longitude)
                is PlaceResolutionResult.CoarseLocation -> null
                is PlaceResolutionResult.UnknownLocation -> formatCoordinates(latitude, longitude)
            }

        return CurrentLocationUiModel(
            title = title,
            subtitle = subtitle,
            latitude = latitude,
            longitude = longitude,
        )
    }

    private suspend fun LocationStop.toUiModel(): LocationStopUiModel {
        val resolvedPlace = placeResolutionCache.resolve(location)
        val title: UiText
        val subtitle: String
        val sourceLabel: StringResource
        val source: LocationLabelSource

        when (resolvedPlace) {
            is PlaceResolutionResult.UserDefinedPlace -> {
                title = resolvedPlace.place.name.toUiText()
                subtitle = formatCoordinates(location.latitude, location.longitude)
                sourceLabel = Res.string.places_source_saved_place
                source = LocationLabelSource.USER_DEFINED
            }

            is PlaceResolutionResult.ExternalSuggestion -> {
                title = resolvedPlace.suggestion.name.toUiText()
                subtitle = resolvedPlace.suggestion.address
                sourceLabel = Res.string.places_source_google_places
                source = LocationLabelSource.GOOGLE_PLACES
            }

            is PlaceResolutionResult.CoarseLocation -> {
                title = formatCoarseLocation(resolvedPlace.address)
                subtitle = resolvedPlace.address.locality ?: resolvedPlace.address.adminArea ?: ""
                sourceLabel = Res.string.places_source_location
                source = LocationLabelSource.COORDINATES
            }

            is PlaceResolutionResult.UnknownLocation -> {
                title = Res.string.places_pinned_location.toUiText()
                subtitle = formatCoordinates(location.latitude, location.longitude)
                sourceLabel = Res.string.places_source_coordinates
                source = LocationLabelSource.COORDINATES
            }
        }

        return LocationStopUiModel(
            id = id,
            title = title,
            subtitle = subtitle,
            latitude = location.latitude,
            longitude = location.longitude,
            startedAt = startTime.localTime,
            endedAt = endTime.localTime,
            timeRange = formatTimeRange(startTime, endTime),
            duration = formatEvidence(this@toUiModel),
            sourceLabel = sourceLabel,
            source = source,
            sampleCount = sampleCount,
            startTime = startTime,
            endTime = endTime,
            hasReliableDuration = hasReliableDuration,
        )
    }

    private suspend fun LocationMemoryPlace.toUiModel(mappedStops: List<LocationStopUiModel>): LocationPlaceUiModel {
        val resolvedPlace =
            if (semanticName.isNullOrBlank()) {
                placeResolutionCache.resolve(
                    Location(
                        latitude = latitude,
                        longitude = longitude,
                        altitude = LocationAltitude(0.0, AltitudeUnit.METERS),
                    ),
                )
            } else {
                null
            }

        val title: UiText
        val subtitle: String
        val sourceLabel: StringResource
        val source: LocationLabelSource

        when {
            !semanticName.isNullOrBlank() -> {
                title = semanticName.orEmpty().toUiText()
                subtitle = formatCoordinates(latitude, longitude)
                sourceLabel = Res.string.places_source_place
                source = LocationLabelSource.USER_DEFINED
            }

            resolvedPlace is PlaceResolutionResult.UserDefinedPlace -> {
                title = resolvedPlace.place.name.toUiText()
                subtitle = formatCoordinates(latitude, longitude)
                sourceLabel = Res.string.places_source_saved_place
                source = LocationLabelSource.USER_DEFINED
            }

            resolvedPlace is PlaceResolutionResult.ExternalSuggestion -> {
                title = resolvedPlace.suggestion.name.toUiText()
                subtitle = resolvedPlace.suggestion.address
                sourceLabel = Res.string.places_source_nearby_place
                source = LocationLabelSource.GOOGLE_PLACES
            }

            resolvedPlace is PlaceResolutionResult.CoarseLocation -> {
                title = formatCoarseLocation(resolvedPlace.address)
                subtitle = formatCoordinates(latitude, longitude)
                sourceLabel = Res.string.places_source_location
                source = LocationLabelSource.COORDINATES
            }

            else -> {
                title = Res.string.pinned_memory.toUiText()
                subtitle = formatCoordinates(latitude, longitude)
                sourceLabel = Res.string.places_source_coordinates
                source = LocationLabelSource.COORDINATES
            }
        }

        return LocationPlaceUiModel(
            id = id,
            title = title,
            subtitle = subtitle,
            latitude = latitude,
            longitude = longitude,
            lastVisitedLabel = lastVisitedAt.toReadableDateShort(),
            memoryCount = memoryCount,
            sourceLabel = sourceLabel,
            source = source,
            memories =
                memories.map { memory ->
                    LocationMemoryPreviewUiModel(
                        noteId = memory.noteId,
                        title = memory.preview,
                        subtitle = memory.timestamp.toReadableDateShort(),
                        timestamp = memory.timestamp,
                        latitude = memory.latitude,
                        longitude = memory.longitude,
                        kind = memory.type.toUiKind(),
                    )
                },
            relatedStops =
                mappedStops.filter { stop ->
                    calculateDistanceMeters(
                        latitude,
                        longitude,
                        stop.latitude,
                        stop.longitude,
                    ) <= PLACE_TO_STOP_MATCH_RADIUS_METERS
                },
        )
    }

    private companion object {
        const val DEFAULT_PLACE_PAGE_SIZE = 12
        const val PLACE_TO_STOP_MATCH_RADIUS_METERS = 160.0
    }
}
