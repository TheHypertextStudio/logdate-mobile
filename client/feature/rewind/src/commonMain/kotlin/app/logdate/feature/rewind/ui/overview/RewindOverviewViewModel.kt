@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.rewind.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.rewind.GenerateBasicRewindResult
import app.logdate.client.domain.rewind.GetCurrentWeekRewindUseCase
import app.logdate.client.domain.rewind.GetPastRewindsUseCase
import app.logdate.client.domain.rewind.GetWeekRewindUseCase
import app.logdate.client.domain.rewind.RefreshCurrentWeekRewindUseCase
import app.logdate.client.domain.rewind.RewindQueryResult
import app.logdate.client.intelligence.milestones.MilestoneKind
import app.logdate.client.intelligence.milestones.parseMilestoneSignal
import app.logdate.client.intelligence.rewind.RewindMessageGenerator
import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindContent
import app.logdate.util.getLocaleFirstDayOfWeek
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.nanoseconds

/** Presents the selected week, saved history and explicit current-week updates. */
@OptIn(ExperimentalCoroutinesApi::class)
class RewindOverviewViewModel(
    private val getWeekRewindUseCase: GetWeekRewindUseCase,
    private val getPastRewindsUseCase: GetPastRewindsUseCase,
    private val rewindMessageGenerator: RewindMessageGenerator,
    private val getCurrentWeekRewindUseCase: GetCurrentWeekRewindUseCase,
    private val refreshCurrentWeekRewindUseCase: RefreshCurrentWeekRewindUseCase,
    private val preferencesDataSource: LogdatePreferencesDataSource,
) : ViewModel() {
    // Tracks whether a rewind generation is in progress
    private val isGeneratingRewindState = MutableStateFlow(false)
    private val generationFailedState = MutableStateFlow(false)
    private val selectedPeriodState = MutableStateFlow(RewindOverviewPeriod.PreviousWeek)
    val selectedPeriod: StateFlow<RewindOverviewPeriod> = selectedPeriodState
    val firstDayOfWeek =
        preferencesDataSource
            .observeFirstDayOfWeek()
            .map { it ?: getLocaleFirstDayOfWeek() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), getLocaleFirstDayOfWeek())
    private val retryRevision = MutableStateFlow(0)
    private var refreshJob: Job? = null
    private var refreshRevision = 0
    private val selectedQuery =
        observeSelectedRewind(
            selectedPeriodState,
            retryRevision,
            getWeekRewindUseCase::invoke,
            getCurrentWeekRewindUseCase::invoke,
        )

    private val pastRewinds: StateFlow<List<RewindHistoryUiState>> =
        getPastRewindsUseCase()
            .map { pastRewinds ->
                pastRewinds
                    .map { rewind ->
                        val summary = rewind.cardSummary()
                        val milestoneSignal = parseMilestoneSignal(rewind.metadata?.milestones?.firstOrNull())
                        RewindHistoryUiState(
                            uid = rewind.uid,
                            title = rewind.title,
                            label = rewind.label,
                            startDate = summary.start,
                            endDate = summary.end,
                            message = summary.message(rewindMessageGenerator),
                            isViewed = rewind.isViewed,
                            entryCount = summary.textCount,
                            photoCount = summary.photoCount,
                            audioCount = summary.audioCount,
                            peopleCount = summary.peopleCount,
                            primaryLocation = summary.primaryLocation,
                            milestone =
                                milestoneSignal?.let {
                                    MilestoneSummaryUiState(
                                        kind =
                                            when (it.kind) {
                                                MilestoneKind.LOCATION_CHANGE -> MilestoneKindUiState.LOCATION_CHANGE
                                            },
                                        summary = it.summary,
                                    )
                                },
                            heroImageUri = summary.heroImageUri,
                            highlightedQuote = summary.highlightedQuote,
                            dominantActivity = rewind.metadata?.detectedActivities?.firstOrNull(),
                        )
                    }.forWeeklyLaunch()
            }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    private val rewindUiState =
        combine(
            selectedQuery,
            pastRewinds,
            isGeneratingRewindState,
            generationFailedState,
            selectedPeriodState,
        ) { (queryPeriod, rewindResult), pastRewinds, isGenerating, generationFailed, selectedPeriod ->
            if (queryPeriod != selectedPeriod) {
                return@combine RewindOverviewScreenUiState.NotReady(pastRewinds, isGeneratingRewind = true)
            }
            when (rewindResult) {
                is RewindQueryResult.Success -> {
                    val rewind = rewindResult.rewind
                    val summary = rewind.cardSummary()
                    RewindOverviewScreenUiState.Ready(
                        pastRewinds = pastRewinds,
                        isRefreshing = isGenerating,
                        refreshFailed = generationFailed,
                        mostRecentRewind =
                            RewindPreviewUiState(
                                rewindId = rewind.uid,
                                message = summary.message(rewindMessageGenerator),
                                label = rewind.label,
                                title = rewind.title,
                                start = summary.start,
                                end = summary.end,
                                rewindAvailable = rewind.content.isNotEmpty(),
                                isViewed = rewind.isViewed,
                                entryCount = summary.textCount,
                                photoCount = summary.photoCount,
                                audioCount = summary.audioCount,
                                peopleCount = summary.peopleCount,
                                primaryLocation = summary.primaryLocation,
                                heroImageUri = summary.heroImageUri,
                                highlightedQuote = summary.highlightedQuote,
                                dominantActivity = rewind.metadata?.detectedActivities?.firstOrNull(),
                            ),
                    )
                }

                RewindQueryResult.NotReady -> {
                    RewindOverviewScreenUiState.NotReady(
                        pastRewinds = pastRewinds,
                        isGeneratingRewind = isGenerating,
                    )
                }

                RewindQueryResult.Generating -> {
                    RewindOverviewScreenUiState.NotReady(
                        pastRewinds = pastRewinds,
                        isGeneratingRewind = true,
                    )
                }

                RewindQueryResult.NoneAvailable -> {
                    RewindOverviewScreenUiState.NotReady(
                        pastRewinds = pastRewinds,
                        isGeneratingRewind = false,
                        generationFailed = generationFailed,
                    )
                }

                RewindQueryResult.Failed -> {
                    RewindOverviewScreenUiState.NotReady(
                        pastRewinds = pastRewinds,
                        isGeneratingRewind = isGenerating,
                        generationFailed = !isGenerating,
                    )
                }
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = RewindOverviewScreenUiState.Loading,
            )

    val uiState: StateFlow<RewindOverviewScreenUiState> = rewindUiState

    fun selectPeriod(period: RewindOverviewPeriod) {
        if (period == selectedPeriodState.value) return
        refreshRevision++
        refreshJob?.cancel()
        isGeneratingRewindState.value = false
        generationFailedState.value = false
        selectedPeriodState.value = period
    }

    fun retrySelectedPeriod() {
        generationFailedState.value = false
        retryRevision.update { it + 1 }
    }

    fun refreshCurrentWeek() {
        if (selectedPeriodState.value != RewindOverviewPeriod.ThisWeek || isGeneratingRewindState.value) return
        val revision = ++refreshRevision
        isGeneratingRewindState.value = true
        generationFailedState.value = false
        refreshJob =
            viewModelScope.launch {
                try {
                    val result = refreshCurrentWeekRewindUseCase()
                    if (revision == refreshRevision) {
                        generationFailedState.value = result is GenerateBasicRewindResult.Error
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Could not update this week's Rewind", error)
                    if (revision == refreshRevision) generationFailedState.value = true
                } finally {
                    if (revision == refreshRevision) isGeneratingRewindState.value = false
                }
            }
    }
}

private const val STOP_TIMEOUT_MILLIS = 5_000L

/** What the latest Rewind's preview and every history card say about one Rewind, counted once. */
internal data class RewindCardSummary(
    val start: LocalDate,
    val end: LocalDate,
    val photoCount: Int,
    val textCount: Int,
    val audioCount: Int,
    val peopleCount: Int,
    val primaryLocation: String?,
    val themes: List<String>,
    val heroImageUri: String?,
    val highlightedQuote: String?,
) {
    suspend fun message(generator: RewindMessageGenerator): String =
        generator.generateContextualMessage(
            rewindAvailable = true,
            photoCount = photoCount,
            textCount = textCount,
            peopleCount = peopleCount,
            themes = themes,
        )
}

internal fun Rewind.cardSummary(zone: TimeZone = TimeZone.currentSystemDefault()): RewindCardSummary {
    var photos = 0
    var texts = 0
    var recordings = 0
    content.forEach { item ->
        when (item) {
            is RewindContent.Image -> photos++
            is RewindContent.TextNote -> texts++
            is RewindContent.AudioNote -> recordings++
            else -> Unit
        }
    }
    return RewindCardSummary(
        start = startDate.toLocalDateTime(zone).date,
        end = (endDate - 1.nanoseconds).toLocalDateTime(zone).date,
        photoCount = photos,
        textCount = texts,
        audioCount = recordings,
        peopleCount = metadata?.peopleHighlighted?.size ?: 0,
        primaryLocation = metadata?.locationSummary?.primaryLocation,
        themes = metadata?.detectedActivities?.map { it.name }.orEmpty(),
        heroImageUri = heroImageUri(),
        highlightedQuote = metadata?.highlightedQuotes?.firstOrNull()?.text,
    )
}

/**
 * A real photo from this rewind's week, preferring the most significant one, falling
 * back to a narrative panel's background image — the card's primary background.
 */
private fun Rewind.heroImageUri(): String? =
    content
        .filterIsInstance<RewindContent.Image>()
        .maxByOrNull { it.significanceScore ?: 0f }
        ?.uri
        ?: content.filterIsInstance<RewindContent.NarrativeContext>().firstNotNullOfOrNull { it.backgroundImage }
