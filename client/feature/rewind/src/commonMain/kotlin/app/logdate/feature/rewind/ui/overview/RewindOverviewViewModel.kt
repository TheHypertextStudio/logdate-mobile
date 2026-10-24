@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.rewind.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.rewind.GenerateAnnualRewindUseCase
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val generateAnnualRewindUseCase: GenerateAnnualRewindUseCase,
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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), getLocaleFirstDayOfWeek())
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
                        val milestoneSignal = parseMilestoneSignal(rewind.metadata?.milestones?.firstOrNull())
                        val photoCount = rewind.content.count { it is RewindContent.Image }
                        val textCount = rewind.content.count { it is RewindContent.TextNote }
                        val audioCount = rewind.content.count { it is RewindContent.AudioNote }
                        val peopleCount = rewind.metadata?.peopleHighlighted?.size ?: 0
                        val primaryLocation = rewind.metadata?.locationSummary?.primaryLocation
                        val themes =
                            rewind.metadata
                                ?.detectedActivities
                                ?.map { it.name }
                                .orEmpty()
                        RewindHistoryUiState(
                            uid = rewind.uid,
                            title = rewind.title,
                            label = rewind.label,
                            startDate = rewind.startDate.toLocalDateTime(TimeZone.currentSystemDefault()).date,
                            endDate = (rewind.endDate - 1.nanoseconds).toLocalDateTime(TimeZone.currentSystemDefault()).date,
                            message =
                                rewindMessageGenerator.generateContextualMessage(
                                    rewindAvailable = true,
                                    photoCount = photoCount,
                                    textCount = textCount,
                                    peopleCount = peopleCount,
                                    themes = themes,
                                ),
                            isViewed = rewind.isViewed,
                            entryCount = textCount,
                            photoCount = photoCount,
                            audioCount = audioCount,
                            peopleCount = peopleCount,
                            primaryLocation = primaryLocation,
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
                            heroImageUri = rewind.heroImageUri(),
                            highlightedQuote = rewind.highlightedQuote(),
                            dominantActivity = rewind.metadata?.detectedActivities?.firstOrNull(),
                        )
                    }.forWeeklyLaunch()
            }.stateIn(
                viewModelScope,
                started = SharingStarted.WhileSubscribed(),
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
                    val photoCount = rewind.content.count { it is RewindContent.Image }
                    val textCount = rewind.content.count { it is RewindContent.TextNote }
                    val audioCount = rewind.content.count { it is RewindContent.AudioNote }
                    val peopleCount = rewind.metadata?.peopleHighlighted?.size ?: 0
                    val primaryLocation = rewind.metadata?.locationSummary?.primaryLocation
                    val themes =
                        rewind.metadata
                            ?.detectedActivities
                            ?.map { it.name }
                            .orEmpty()
                    RewindOverviewScreenUiState.Ready(
                        pastRewinds = pastRewinds,
                        isRefreshing = isGenerating,
                        refreshFailed = generationFailed,
                        mostRecentRewind =
                            RewindPreviewUiState(
                                rewindId = rewind.uid,
                                message =
                                    rewindMessageGenerator.generateContextualMessage(
                                        rewindAvailable = true,
                                        photoCount = photoCount,
                                        textCount = textCount,
                                        peopleCount = peopleCount,
                                        themes = themes,
                                    ),
                                label = rewind.label,
                                title = rewind.title,
                                start =
                                    rewind.startDate
                                        .toLocalDateTime(TimeZone.currentSystemDefault())
                                        .date,
                                end =
                                    rewind.endDate
                                        .minus(1.nanoseconds)
                                        .toLocalDateTime(TimeZone.currentSystemDefault())
                                        .date,
                                rewindAvailable = rewind.content.isNotEmpty(),
                                isViewed = rewind.isViewed,
                                entryCount = textCount,
                                photoCount = photoCount,
                                audioCount = audioCount,
                                peopleCount = peopleCount,
                                primaryLocation = primaryLocation,
                                heroImageUri = rewind.heroImageUri(),
                                highlightedQuote = rewind.highlightedQuote(),
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
        }.stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
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

    /**
     * Generates a Year in Review for [year]. The annual rewind appears in pastRewinds
     * automatically via the repository flow once generation completes.
     */
    fun generateAnnualRewind(year: Int) {
        viewModelScope.launch {
            try {
                when (val result = generateAnnualRewindUseCase(year)) {
                    is GenerateBasicRewindResult.Success -> {
                        Napier.i("Generated annual rewind for $year: ${result.rewind.uid}")
                    }

                    is GenerateBasicRewindResult.NoContent -> {
                        Napier.w("Not enough weekly rewinds for annual rewind $year")
                    }

                    is GenerateBasicRewindResult.Error -> {
                        Napier.e("Annual rewind generation failed: ${result.error}")
                    }

                    is GenerateBasicRewindResult.AlreadyInProgress -> {
                        Napier.d("Annual rewind generation already in progress for $year")
                    }
                }
            } catch (e: Exception) {
                Napier.e("Error generating annual rewind for $year", e)
            }
        }
    }
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

/** A verbatim quote from this rewind's week, shown in place of the generic message. */
private fun Rewind.highlightedQuote(): String? = metadata?.highlightedQuotes?.firstOrNull()?.text
