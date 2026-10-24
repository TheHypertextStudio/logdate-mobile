package app.logdate.feature.location.timeline.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.location.history.LocationHistoryService
import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.domain.location.history.SuggestNearbyHistoryPlacesUseCase
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.domain.places.PlaceResolutionResult
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.text.toUiText
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.VisitMemoryContext
import app.logdate.util.toReadableDateShort
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_change_not_saved
import logdate.client.feature.location.timeline.generated.resources.history_day_summary
import logdate.client.feature.location.timeline.generated.resources.history_load_failed
import logdate.client.feature.location.timeline.generated.resources.history_loading_day
import logdate.client.feature.location.timeline.generated.resources.history_location_clue_count
import logdate.client.feature.location.timeline.generated.resources.history_range_days
import logdate.client.feature.location.timeline.generated.resources.history_review_changes
import logdate.client.feature.location.timeline.generated.resources.history_today
import logdate.client.feature.location.timeline.generated.resources.history_visit_count
import logdate.client.ui.generated.resources.common_try_again
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

class HumanLocationHistoryViewModel(
    private val history: LocationHistoryService,
    private val settings: LocationTrackingSettingsRepository,
    private val placeResolution: PlaceResolutionCache,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val nearbyPlaces: SuggestNearbyHistoryPlacesUseCase? = null,
) : ViewModel() {
    private val zone = TimeZone.currentSystemDefault()
    var date: LocalDate =
        savedState.get<String>("historyDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: Clock.System
                .now()
                .toLocalDateTime(zone)
                .date
        private set
    private val mutableState =
        MutableStateFlow(
            HumanLocationHistoryState(
                Res.string.history_today.toUiText(),
                recoveryMessage = Res.string.history_loading_day.toUiText(),
                selectedItemId = savedState["historySelection"],
                detailVisible = savedState["historyDetail"] ?: false,
                tab = HistoryTab.entries.firstOrNull { it.name == savedState.get<String>("historyTab") } ?: HistoryTab.Day,
            ),
        )
    val state = mutableState.asStateFlow()
    val mapSnapshot = MutableStateFlow<Pair<String, LocationHistorySnapshot>?>(null)
    val snapshot = MutableStateFlow<LocationHistorySnapshot?>(null)
    val recordingEnabled = MutableStateFlow(false)
    val completeDayEnabled = MutableStateFlow(false)
    private var observeJob: Job? = null
    private var enrichmentJob: Job? = null
    private var replayJob: Job? = null
    private var failedAction: (suspend () -> Unit)? = null
    private var rangeDays = 30
    private var selectedSource: String? = savedState["historySource"]
    private val resolving = mutableSetOf<String>()
    private val resolvedExternalPlaces = mutableMapOf<String, String>()

    init {
        observe()
    }

    fun select(
        id: String,
        detail: Boolean = false,
    ) {
        pauseReplay()
        mutableState.update { it.copy(selectedItemId = id, detailVisible = detail, selectionRevision = it.selectionRevision + 1) }
        savedState["historySelection"] = id
        savedState["historySelectionEvidence"] =
            snapshot.value
                ?.items
                ?.firstOrNull { it.id == id }
                ?.evidenceIds
                ?.firstOrNull()
        savedState["historyDetail"] = detail
    }

    fun dismiss() {
        mutableState.update { it.copy(detailVisible = false) }
        savedState["historyDetail"] = false
    }

    fun changeDay(offset: Int) = setDate(date.plus(DatePeriod(days = offset)))

    fun setDate(
        value: LocalDate,
        tab: HistoryTab = HistoryTab.Day,
    ) {
        pauseReplay()
        date = value
        savedState["historyDate"] = value.toString()
        savedState["historySelection"] = null
        savedState["historyDetail"] = false
        savedState["historyTab"] = tab.name
        mutableState.update { it.copy(selectedItemId = null, detailVisible = false, tab = tab) }
        observe()
    }

    fun setTab(tab: HistoryTab) {
        savedState["historyTab"] = tab.name
        savedState["historySelection"] = null
        savedState["historyDetail"] = false
        pauseReplay()
        mutableState.update { it.copy(tab = tab, detailVisible = false, selectedItemId = null) }
        observe()
    }

    fun setQuery(query: String) = mutableState.update { it.copy(placesQuery = query) }

    fun setMapVisible(visible: Boolean) = mutableState.update { it.copy(placesMapVisible = visible) }

    fun cycleRange() {
        rangeDays = if (rangeDays == 30) 90 else 30
        observe()
    }

    fun selectSource(id: String) {
        selectedSource = id
        savedState["historySource"] = id
        observe()
    }

    fun replay(play: Boolean) {
        pauseReplay()
        if (!play) return
        if (state.value.selectedItemId ==
            state.value.items
                .lastOrNull()
                ?.id
        ) {
            mutableState.update { it.copy(selectedItemId = null) }
        }
        mutableState.update { it.copy(replayPlaying = true) }
        replayJob =
            viewModelScope.launch {
                do {
                    val next = if (state.value.selectedItemId == null) state.value.items.firstOrNull() else state.value.adjacentItem(1)
                    if (next == null) break
                    mutableState.update {
                        it.copy(selectedItemId = next.id, detailVisible = false, selectionRevision = it.selectionRevision + 1)
                    }
                    delay(2500)
                } while (state.value.replayPlaying)
                mutableState.update { it.copy(replayPlaying = false) }
            }
    }

    fun pauseReplay() {
        replayJob?.cancel()
        mutableState.update { it.copy(replayPlaying = false) }
    }

    suspend fun nearbyPlacesFor(item: LocationDayItem?): List<SemanticPlace> {
        val visit = item as? PlaceVisit ?: return emptyList()
        return nearbyPlaces?.invoke(visit.latitude, visit.longitude, snapshot.value?.places.orEmpty()).orEmpty()
    }

    fun contextFor(id: String): VisitMemoryContext? =
        snapshot.value?.items?.filterIsInstance<PlaceVisit>()?.firstOrNull { it.id == id }?.let {
            VisitMemoryContext(it.evidenceIds.first(), it.place?.id, it.place?.name, it.latitude, it.longitude, it.start)
        }

    fun delete(id: String) =
        mutate {
            snapshot.value
                ?.items
                ?.firstOrNull { it.id == id }
                ?.let { history.deleteVisit(it) }
            dismiss()
        }

    fun linkMemory(
        visitId: String,
        noteId: String,
    ) = mutate {
        anchor(visitId)?.let { history.linkMemory(it, noteId) }
    }

    fun changeActivity(
        id: String,
        mode: String,
    ) = mutate { anchor(id)?.let { history.correct(it, HistoryField.ACTIVITY, mode) } }

    fun namePlace(
        id: String,
        name: String,
    ) = mutate {
        val visit =
            snapshot.value
                ?.items
                ?.filterIsInstance<PlaceVisit>()
                ?.firstOrNull { it.id == id } ?: return@mutate
        val place =
            SemanticPlace(visit.place?.id ?: Uuid.random().toString(), name.trim(), visit.latitude, visit.longitude, userConfirmed = true)
        history.savePlace(place)
        history.correct(visit.evidenceIds.first(), HistoryField.PLACE, place.id)
    }

    fun changeTime(
        id: String,
        start: Instant,
        end: Instant,
    ) = mutate {
        require(end >= start) { "The end must be after the start." }
        anchor(id)?.let {
            history.correct(it, HistoryField.START, start.toString())
            history.correct(it, HistoryField.END, end.toString())
        }
    }

    fun addVisit(
        start: Instant,
        end: Instant,
        place: SemanticPlace,
    ) = mutate { history.addVisit(start, end, place) }

    fun enableRecording() =
        mutate {
            val previous = settings.getSettings()
            settings.updateSettings(previous.copy(backgroundTrackingEnabled = true, captureMode = LocationCaptureMode.ACTIVE))
        }

    fun retry() {
        failedAction?.let {
            mutate(it)
            return
        }
        resolving.clear()
        observe()
    }

    fun choosePlace(
        id: String,
        place: SemanticPlace,
    ) = mutate {
        history.savePlace(place.copy(userConfirmed = true))
        anchor(id)?.let { history.correct(it, HistoryField.PLACE, place.id) }
    }

    fun mergePlace(
        from: String,
        into: SemanticPlace,
    ) = mutate { history.mergePlaces(from, into) }

    fun resolveConflict(
        evidenceId: String,
        field: HistoryField,
        value: String,
    ) = mutate {
        history.correct(evidenceId, field, value)
    }

    fun pauseRecording() = mutate { settings.setBackgroundTrackingEnabled(false) }

    private fun anchor(id: String) =
        snapshot.value
            ?.items
            ?.firstOrNull { it.id == id }
            ?.evidenceIds
            ?.firstOrNull()

    private fun mutate(action: suspend () -> Unit) {
        if (failedAction != null) mutableState.update { it.copy(recoveryMessage = null, recoveryActionLabel = null, canRetry = false) }
        failedAction = null
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Location history action failed", error)
                failedAction = action
                mutableState.update {
                    it.copy(
                        recoveryMessage = Res.string.history_change_not_saved.toUiText(),
                        recoveryActionLabel = UiRes.string.common_try_again.toUiText(),
                        canRetry = true,
                    )
                }
            }
        }
    }

    private fun observe() {
        observeJob?.cancel()
        enrichmentJob?.cancel()
        resolving.clear()
        val requestedKey = "$date:${state.value.tab}:$rangeDays:$selectedSource"
        observeJob =
            viewModelScope.launch {
                try {
                    val first = if (state.value.tab == HistoryTab.Places) date.minus(DatePeriod(days = rangeDays - 1)) else date
                    combine(history.observeRange(first, date, selectedSource, zone), settings.observeSettings()) { data, tracking ->
                        data to
                            tracking
                    }.collect { (data, tracking) ->
                        mapSnapshot.value = requestedKey to data
                        applySnapshot(data, tracking)
                        enrich(data)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not read location history", error)
                    mutableState.update {
                        it.copy(
                            recoveryMessage = Res.string.history_load_failed.toUiText(),
                            recoveryActionLabel = UiRes.string.common_try_again.toUiText(),
                            canRetry = true,
                        )
                    }
                }
            }
    }

    private fun selectedDateLabel(): String {
        val label = date.atStartOfDayIn(zone).toReadableDateShort()
        return if (date.year ==
            Clock.System
                .now()
                .toLocalDateTime(zone)
                .year
        ) {
            label
        } else {
            "$label, ${date.year}"
        }
    }

    private fun applySnapshot(
        data: LocationHistorySnapshot,
        tracking: app.logdate.client.location.settings.LocationTrackingSettings,
    ) {
        val notesById = data.notes.associateBy { it.uid.toString() }
        val selection =
            remapHistorySelection(
                state.value.selectedItemId,
                snapshot.value?.items.orEmpty(),
                data.items,
                savedState["historySelectionEvidence"],
            )
        snapshot.value = data
        savedState["historySelection"] = selection
        recordingEnabled.value = tracking.backgroundTrackingEnabled
        completeDayEnabled.value = tracking.backgroundTrackingEnabled && tracking.captureMode == LocationCaptureMode.ACTIVE
        mutableState.update { current ->
            current.copy(
                selectedItemId = selection,
                detailVisible = current.detailVisible && selection != null,
                dateLabel = selectedDateLabel().toUiText(),
                daySummary =
                    data.items.filterIsInstance<PlaceVisit>().let { visits ->
                        val confirmed = visits.count { it.confirmedStay }
                        val clues = visits.size - confirmed
                        val visitCount = Res.plurals.history_visit_count.toUiText(confirmed, confirmed)
                        if (clues > 0) {
                            val clueCount = Res.plurals.history_location_clue_count.toUiText(clues, clues)
                            Res.string.history_day_summary.toUiText(visitCount, clueCount)
                        } else {
                            visitCount
                        }
                    },
                items =
                    data.items.map { item ->
                        item.toHistoryUi(
                            if (item is PlaceVisit) item.memoryIds.mapNotNull(notesById::get) else emptyList(),
                        )
                    },
                places = data.placeRows(),
                placesFilterLabel = Res.plurals.history_range_days.toUiText(rangeDays, rangeDays),
                recoveryActionLabel =
                    when {
                        failedAction != null -> UiRes.string.common_try_again.toUiText()
                        data.failedSections.isNotEmpty() -> UiRes.string.common_try_again.toUiText()
                        data.conflicts.isNotEmpty() -> Res.string.history_review_changes.toUiText()
                        else -> null
                    },
                canRetry = failedAction != null || data.failedSections.isNotEmpty(),
                recoveryMessage = snapshotRecoveryMessage(data, tracking, hasFailedAction = failedAction != null),
            )
        }
    }

    private fun enrich(data: LocationHistorySnapshot) {
        if (state.value.tab != HistoryTab.Day) return
        data.places.forEach { place -> place.externalId?.let { resolvedExternalPlaces.getOrPut(it) { place.id } } }
        val unresolved = data.items.filterIsInstance<PlaceVisit>().filter { it.place == null && resolving.add(it.id) }
        if (unresolved.isEmpty()) return
        enrichmentJob =
            viewModelScope.launch {
                unresolved.forEach { visit ->
                    try {
                        val result =
                            placeResolution.resolve(
                                Location(visit.latitude, visit.longitude, LocationAltitude(0.0, AltitudeUnit.METERS)),
                            )
                        if (result is PlaceResolutionResult.UserDefinedPlace && data.places.any { it.id == result.place.uid.toString() }) {
                            return@forEach
                        }
                        resolvedPlace(result, visit)?.let { history.savePlace(it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Napier.w("Place name unavailable; keeping the visit", error)
                    }
                }
            }
    }

    private suspend fun resolvedPlace(
        result: PlaceResolutionResult,
        visit: PlaceVisit,
    ): SemanticPlace? {
        val name = resolvedHistoryPlaceName(result)
        if (name != null) {
            val place =
                when (result) {
                    is PlaceResolutionResult.UserDefinedPlace -> {
                        SemanticPlace(
                            result.place.uid.toString(),
                            result.place.name,
                            result.place.latitude,
                            result.place.longitude,
                            userConfirmed = true,
                        )
                    }

                    is PlaceResolutionResult.ExternalSuggestion -> {
                        SemanticPlace(
                            result.suggestion.externalId?.let {
                                resolvedExternalPlaces.getOrPut(
                                    it,
                                ) { Uuid.random().toString() }
                            }
                                ?: "evidence:${visit.evidenceIds.first()}",
                            name,
                            result.suggestion.latitude,
                            result.suggestion.longitude,
                            locality = result.suggestion.address,
                            externalId = result.suggestion.externalId,
                        )
                    }

                    else -> {
                        SemanticPlace("evidence:${visit.evidenceIds.first()}", name, visit.latitude, visit.longitude)
                    }
                }
            return place
        }
        return null
    }
}
