package app.logdate.feature.location.timeline.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.location.history.LocationHistoryService
import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.domain.location.history.SuggestNearbyHistoryPlacesUseCase
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.repository.user.devices.UserDevice
import app.logdate.client.repository.user.devices.UserDeviceRepository
import app.logdate.client.text.UiText
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.VisitMemoryContext
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_loading_day
import logdate.client.feature.location.timeline.generated.resources.history_partial_load_failed
import logdate.client.feature.location.timeline.generated.resources.history_save_failed
import logdate.client.feature.location.timeline.generated.resources.history_today
import logdate.client.feature.location.timeline.generated.resources.history_try_again
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class HumanLocationHistoryViewModel(
    private val history: LocationHistoryService,
    private val settings: LocationTrackingSettingsRepository,
    private val placeResolution: PlaceResolutionCache,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val nearbyPlaces: SuggestNearbyHistoryPlacesUseCase? = null,
    private val userDevices: UserDeviceRepository? = null,
) : ViewModel() {
    private fun currentZone(): TimeZone = TimeZone.currentSystemDefault()

    var date: LocalDate =
        savedState.get<String>("historyDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: Clock.System
                .now()
                .toLocalDateTime(currentZone())
                .date
        private set

    private val mutableState =
        MutableStateFlow(
            HumanLocationHistoryState(
                dateLabel = UiText.Resource(Res.string.history_today),
                recoveryMessage = UiText.Resource(Res.string.history_loading_day),
                selectedItemId = savedState["historySelection"],
                detailVisible = savedState["historyDetail"] ?: false,
                tab = HistoryTab.entries.firstOrNull { it.name == savedState.get<String>("historyTab") } ?: HistoryTab.Day,
            ),
        )
    val state = mutableState.asStateFlow()
    private val mapSnapshotState = MutableStateFlow<Pair<String, LocationHistorySnapshot>?>(null)
    val mapSnapshot: StateFlow<Pair<String, LocationHistorySnapshot>?> = mapSnapshotState.asStateFlow()
    private val snapshotState = MutableStateFlow<LocationHistorySnapshot?>(null)
    val snapshot: StateFlow<LocationHistorySnapshot?> = snapshotState.asStateFlow()
    private val recordingEnabledState = MutableStateFlow(false)
    val recordingEnabled: StateFlow<Boolean> = recordingEnabledState.asStateFlow()
    private val completeDayEnabledState = MutableStateFlow(false)
    val completeDayEnabled: StateFlow<Boolean> = completeDayEnabledState.asStateFlow()
    private var observeJob: Job? = null
    private var replayJob: Job? = null
    private var replayTimeline: ReplayTimeline = ReplayTimeline(emptyList(), 0.0)
    private var failedAction: (suspend () -> Unit)? = null
    private var rangeDays = 30
    private var selectedSource: String? = savedState["historySource"]
    private var calendarMonth: LocalDate = LocalDate(date.year, date.month, 1)
    private val placeEnricher = HistoryVisitPlaceEnricher(history, placeResolution, viewModelScope)
    private val summaryObserver = HistorySummaryObserver(history, viewModelScope, mutableState)

    init {
        observe()
        summaryObserver.observeWeek(date, currentZone())
        summaryObserver.observeCalendar(calendarMonth, currentZone())
    }

    fun select(
        id: String,
        detail: Boolean = false,
    ) {
        pauseReplay()
        val progress = replayTimeline.progressForItem(id)
        val sample = replayTimeline.sampleAt(progress)
        val zone = activeDisplayZone()
        mutableState.update {
            it.copy(
                selectedItemId = id,
                detailVisible = detail,
                selectionRevision = it.selectionRevision + 1,
                replayProgress = progress,
                replaySample = sample,
                replayTimeLabel = replayTimeDescription(sample, zone),
            )
        }
        savedState["historySelection"] = id
        savedState["historySelectionEvidence"] = anchor(id)
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
        calendarMonth = LocalDate(value.year, value.month, 1)
        savedState["historyDate"] = value.toString()
        savedState["historySelection"] = null
        savedState["historyDetail"] = false
        savedState["historyTab"] = tab.name
        mutableState.update { it.copy(selectedItemId = null, detailVisible = false, tab = tab, selectedDate = value) }
        observe()
        summaryObserver.observeWeek(date, currentZone())
        summaryObserver.observeCalendar(calendarMonth, currentZone())
    }

    fun jumpToToday() =
        setDate(
            Clock.System
                .now()
                .toLocalDateTime(currentZone())
                .date,
            HistoryTab.Day,
        )

    fun shiftCalendarMonth(offsetMonths: Int) {
        val shifted = calendarMonth.plus(DatePeriod(months = offsetMonths))
        calendarMonth = LocalDate(shifted.year, shifted.month, 1)
        summaryObserver.observeCalendar(calendarMonth, currentZone())
    }

    fun jumpToLatestRecordedDay() {
        val latest = state.value.latestRecordedDate
        if (latest != null) {
            setDate(latest, HistoryTab.Day)
        } else {
            viewModelScope.launch {
                runCatching { history.latestRecordedDate(currentZone()) }.getOrNull()?.let { setDate(it, HistoryTab.Day) }
            }
        }
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
        val presets = PlacesRangePreset.entries
        val currentIdx = presets.indexOfFirst { it.days == rangeDays }.coerceAtLeast(0)
        setPlacesRangePreset(presets[(currentIdx + 1) % presets.size])
    }

    fun setPlacesRangePreset(preset: PlacesRangePreset) {
        rangeDays = preset.days
        mutableState.update { it.copy(placesRangePreset = preset, placesFilterLabel = UiText.Resource(preset.labelRes)) }
        observe()
    }

    fun setPlacesSort(sort: PlacesSortOption) = mutableState.update { it.copy(placesSort = sort) }

    fun setPlacesCategoryFilter(category: String?) = mutableState.update { it.copy(placesCategoryFilter = category) }

    fun confirmPlaceSelection(
        visitId: String,
        place: SemanticPlace,
    ) = choosePlace(visitId, place)

    fun mergeVisits(
        primaryAnchorId: String,
        secondaryAnchorId: String,
    ) = mutate { history.mergeVisits(primaryAnchorId, secondaryAnchorId) }

    fun splitVisit(
        anchorId: String,
        splitAt: Instant,
    ) = mutate { history.splitVisit(anchorId, splitAt) }

    fun updatePlaceName(
        place: SemanticPlace,
        newName: String,
    ) = mutate {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) history.savePlace(place.copy(name = trimmed, userConfirmed = true))
    }

    fun updatePlaceRadius(
        placeId: String,
        radiusMeters: Double,
    ) = mutate { history.updatePlaceRadius(placeId, radiusMeters) }

    fun updatePlaceCategory(
        placeId: String,
        category: String?,
    ) = mutate { history.updatePlaceCategory(placeId, category) }

    fun selectSource(id: String) {
        selectedSource = id
        savedState["historySource"] = id
        observe()
    }

    fun replay(play: Boolean) {
        pauseReplay()
        if (!play || replayTimeline.segments.isEmpty()) return
        val lastItemId =
            state.value.items
                .lastOrNull()
                ?.id
        val startProgress =
            if (state.value.replayProgress >= 0.999f || state.value.selectedItemId == lastItemId) 0f else state.value.replayProgress
        mutableState.update { it.copy(replayPlaying = true, replayProgress = startProgress) }
        replayJob = viewModelScope.launch { runReplayLoop(startProgress) }
    }

    private suspend fun runReplayLoop(startProgress: Float) {
        val frameDelayMs = 33L
        val baseDurationMs = 20_000f
        var progress = startProgress
        val zone = activeDisplayZone()
        while (state.value.replayPlaying && progress <= 1f) {
            mutableState.update { applyReplayStep(it, replayTimeline, progress, zone, resetDetail = true) }
            if (progress >= 1f) break
            delay(frameDelayMs)
            progress = (progress + (frameDelayMs.toFloat() / baseDurationMs) * state.value.replaySpeed).coerceAtMost(1f)
        }
        mutableState.update { it.copy(replayPlaying = false) }
    }

    fun scrubReplay(progress: Float) {
        pauseReplay()
        val zone = activeDisplayZone()
        mutableState.update { applyReplayStep(it, replayTimeline, progress, zone) }
    }

    fun cycleReplaySpeed() = mutableState.update { it.copy(replaySpeed = nextReplaySpeed(it.replaySpeed)) }

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
        noteId: Uuid,
    ) = mutate { anchor(visitId)?.let { history.linkMemory(it, noteId) } }

    fun changeActivity(
        id: String,
        mode: String,
    ) = mutate { anchor(id)?.let { history.correct(it, HistoryField.ACTIVITY, mode) } }

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
        placeEnricher.clearResolving()
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
    ) = mutate { history.correct(evidenceId, field, value) }

    fun pauseRecording() = mutate { settings.setBackgroundTrackingEnabled(false) }

    private fun anchor(id: String) =
        snapshot.value
            ?.items
            ?.firstOrNull { it.id == id }
            ?.evidenceIds
            ?.firstOrNull()

    private fun mutate(action: suspend () -> Unit) {
        if (failedAction != null) mutableState.update { it.copy(recoveryMessage = null, recoveryActionLabel = null) }
        failedAction = null
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Location history action failed", error)
                failedAction = action
                showRetryRecovery(Res.string.history_save_failed)
            }
        }
    }

    private fun showRetryRecovery(messageRes: StringResource) {
        mutableState.update {
            it.copy(
                recoveryMessage = UiText.Resource(messageRes),
                recoveryActionLabel = UiText.Resource(Res.string.history_try_again),
                isRetryRecovery = true,
            )
        }
    }

    private fun observe() {
        observeJob?.cancel()
        placeEnricher.reset()
        val requestedKey = "$date:${state.value.tab}:$rangeDays:$selectedSource"
        observeJob =
            viewModelScope.launch {
                try {
                    val first = if (state.value.tab == HistoryTab.Places) date.minus(DatePeriod(days = rangeDays - 1)) else date
                    val zone = currentZone()
                    val devicesFlow = userDevices?.allDevices?.catch { emit(emptyList()) } ?: flowOf(emptyList())
                    combine(
                        history.observeRange(first, date, selectedSource, zone),
                        settings.observeSettings(),
                        devicesFlow,
                    ) { data, tracking, knownDevices ->
                        Triple(data, tracking, knownDevices)
                    }.collect { (data, tracking, knownDevices) ->
                        mapSnapshotState.value = requestedKey to data
                        applySnapshot(data, tracking, knownDevices)
                        if (state.value.tab == HistoryTab.Day) placeEnricher.enqueue(data, currentZone())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not read location history", error)
                    showRetryRecovery(Res.string.history_partial_load_failed)
                }
            }
    }

    private fun activeDisplayZone(): TimeZone =
        snapshot.value?.effectiveZoneId?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: currentZone()

    private fun applySnapshot(
        data: LocationHistorySnapshot,
        tracking: LocationTrackingSettings,
        knownDevices: List<UserDevice> = emptyList(),
    ) {
        val selection =
            remapHistorySelection(
                state.value.selectedItemId,
                snapshot.value?.items.orEmpty(),
                data.items,
                savedState["historySelectionEvidence"],
            )
        snapshotState.value = data
        replayTimeline = buildReplayTimeline(data.items)
        savedState["historySelection"] = selection
        recordingEnabledState.value = tracking.backgroundTrackingEnabled
        completeDayEnabledState.value = tracking.backgroundTrackingEnabled && tracking.captureMode == LocationCaptureMode.ACTIVE
        val activeZone = activeDisplayZone()
        mutableState.update { current ->
            mapSnapshotToUiState(
                current,
                data,
                tracking,
                knownDevices,
                date,
                activeZone,
                currentZone(),
                rangeDays,
                selection,
                replayTimeline,
                failedAction != null,
            )
        }
    }
}
