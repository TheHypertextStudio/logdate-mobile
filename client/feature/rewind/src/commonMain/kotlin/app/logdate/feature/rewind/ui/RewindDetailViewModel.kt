@file:Suppress("ktlint:standard:max-line-length")

package app.logdate.feature.rewind.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.rewind.DeleteRewindUseCase
import app.logdate.client.domain.rewind.GetRewindUseCase
import app.logdate.client.domain.rewind.MarkRewindViewedUseCase
import app.logdate.client.domain.rewind.ObserveReflectionPromptResponsesUseCase
import app.logdate.client.domain.rewind.SaveReflectionPromptResponseUseCase
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.sharing.RewindQuote
import app.logdate.client.sharing.RewindQuoteCardRenderer
import app.logdate.client.sharing.RewindStatsSummary
import app.logdate.client.sharing.RewindStatsSummaryRenderer
import app.logdate.client.sharing.SharingLauncher
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.feature.rewind.ui.detail.ReflectionReplySheetState
import app.logdate.feature.rewind.ui.detail.RewindShareRequest
import app.logdate.feature.rewind.ui.detail.RewindShareVisual
import app.logdate.feature.rewind.ui.detail.RewindStatsShareRequest
import app.logdate.feature.rewind.ui.detail.qualifiesForMapPanel
import app.logdate.feature.rewind.ui.detail.toVideoFramePanel
import app.logdate.shared.model.MapPoint
import app.logdate.shared.model.ReflectionPrompt
import app.logdate.shared.model.ReflectionPromptKey
import app.logdate.shared.model.ReflectionPromptResponse
import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindContent
import app.logdate.shared.model.WeatherCategory
import app.logdate.shared.model.WeatherContext
import app.logdate.ui.audio.WaveformStorage
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.rewind.generated.resources.Res
import logdate.client.feature.rewind.generated.resources.rewind_state_captured_this_week
import logdate.client.feature.rewind.generated.resources.rewind_state_count_entries
import logdate.client.feature.rewind.generated.resources.rewind_state_count_people
import logdate.client.feature.rewind.generated.resources.rewind_state_count_photos
import logdate.client.feature.rewind.generated.resources.rewind_state_counts_three
import logdate.client.feature.rewind.generated.resources.rewind_state_counts_two
import logdate.client.feature.rewind.generated.resources.rewind_state_date
import logdate.client.feature.rewind.generated.resources.rewind_state_date_range
import logdate.client.feature.rewind.generated.resources.rewind_state_date_range_same_month
import logdate.client.feature.rewind.generated.resources.rewind_state_map_places
import logdate.client.feature.rewind.generated.resources.rewind_state_map_title
import logdate.client.feature.rewind.generated.resources.rewind_state_month_april
import logdate.client.feature.rewind.generated.resources.rewind_state_month_august
import logdate.client.feature.rewind.generated.resources.rewind_state_month_december
import logdate.client.feature.rewind.generated.resources.rewind_state_month_february
import logdate.client.feature.rewind.generated.resources.rewind_state_month_january
import logdate.client.feature.rewind.generated.resources.rewind_state_month_july
import logdate.client.feature.rewind.generated.resources.rewind_state_month_june
import logdate.client.feature.rewind.generated.resources.rewind_state_month_march
import logdate.client.feature.rewind.generated.resources.rewind_state_month_may
import logdate.client.feature.rewind.generated.resources.rewind_state_month_november
import logdate.client.feature.rewind.generated.resources.rewind_state_month_october
import logdate.client.feature.rewind.generated.resources.rewind_state_month_september
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_april
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_august
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_december
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_february
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_january
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_july
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_june
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_march
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_may
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_november
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_october
import logdate.client.feature.rewind.generated.resources.rewind_state_month_short_september
import logdate.client.feature.rewind.generated.resources.rewind_state_title_subtitle
import logdate.client.feature.rewind.generated.resources.rewind_state_units_entries
import logdate.client.feature.rewind.generated.resources.rewind_state_units_photos
import logdate.client.feature.rewind.generated.resources.rewind_state_wrote_this_week
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ViewModel managing rewind detail screen state and data.
 *
 * Handles loading individual rewind data and transforming it into story panels.
 *
 * ## Architecture:
 * - **Data Source**: GetRewindUseCase for rewind content
 * - **State Management**: Reactive StateFlow for UI state updates
 * - **Error Handling**: Graceful fallbacks for missing or invalid data
 *
 * ## Current Implementation:
 * Uses the domain layer's GetRewindUseCase to fetch rewind data and transforms
 * it into UI-ready panels for the story-like interface.
 *
 * @param getRewindUseCase Use case for retrieving rewind data
 * @param sharingLauncher Platform sharing entry point for panel and stats shares
 * @param quoteCardRenderer Renders quote panels to a bitmap for sharing
 * @param statsSummaryRenderer Renders the overall stats summary card for sharing
 * @param observeReflectionPromptResponses Observes the user's saved replies keyed by
 *   prompt so the story can rebuild with the latest reply state
 * @param saveReflectionPromptResponse Persists a reply typed into the story
 * @param deleteRewindUseCase Deletes a rewind and all of its attached content
 * @param markRewindViewed Records that the user has opened this rewind; bumps the
 *   view count and stamps the first-viewed timestamp on the first call
 * @param preferences User preferences data source, read for the replies-enabled toggle
 * @param audioPlaybackManager Plays back audio-note panels; at most one plays at a time
 * @param waveformStorage Reads cached waveform amplitudes for audio-note panels, keyed
 *   by audio URI. Misses (older recordings, or storage never populated) resolve to
 *   null, and the panel reads the waveform from the recording itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RewindDetailViewModel(
    private val getRewindUseCase: GetRewindUseCase,
    private val sharingLauncher: SharingLauncher,
    private val quoteCardRenderer: RewindQuoteCardRenderer,
    private val statsSummaryRenderer: RewindStatsSummaryRenderer,
    private val observeReflectionPromptResponses: ObserveReflectionPromptResponsesUseCase,
    private val saveReflectionPromptResponse: SaveReflectionPromptResponseUseCase,
    private val deleteRewindUseCase: DeleteRewindUseCase,
    private val markRewindViewed: MarkRewindViewedUseCase,
    private val preferences: LogdatePreferencesDataSource,
    private val audioPlaybackManager: AudioPlaybackManager,
    private val waveformStorage: WaveformStorage,
) : ViewModel() {
    private val rewindIdState = MutableStateFlow<Uuid?>(null)
    private val latestRewind = MutableStateFlow<Rewind?>(null)

    private val audioPlaybackStateFlow = MutableStateFlow(AudioPanelPlaybackUiState())

    /**
     * Playback progress for whichever audio-note panel is currently playing, or the
     * default (nothing playing) state. At most one audio panel plays at a time — starting
     * a new one implicitly stops the previous.
     */
    val audioPlaybackState: StateFlow<AudioPanelPlaybackUiState> = audioPlaybackStateFlow.asStateFlow()

    /**
     * The most recently loaded rewind, or null before [loadRewind] resolves. The composable
     * reads this to format date ranges for the stats summary share — keeping locale-aware
     * formatting in the UI layer where compose resources live.
     */
    val currentRewind: StateFlow<Rewind?> = latestRewind

    private val replySheetStateFlow = MutableStateFlow<ReflectionReplySheetState>(ReflectionReplySheetState.Closed)

    /**
     * Whether the reply sheet is open and what prompt it's targeting.
     *
     * The detail screen reads this to decide when to render the bottom sheet, and the story
     * view reads it to stay paused while the user is composing a reply.
     */
    val replySheetState: StateFlow<ReflectionReplySheetState> = replySheetStateFlow.asStateFlow()

    private val isFirstViewFlow = MutableStateFlow(false)

    /**
     * Whether this is the first time the user is opening this rewind.
     *
     * Captured at [loadRewind] time before the viewed flag is written to the database,
     * so it reflects the pre-navigation state rather than racing with the DB write.
     */
    val isFirstView: StateFlow<Boolean> = isFirstViewFlow.asStateFlow()

    private val deletePromptVisibleFlow = MutableStateFlow(false)

    /**
     * Whether the "delete this rewind?" confirmation dialog is currently visible.
     *
     * The story view reads this so auto-advance pauses while the user is making the call,
     * and the screen renders an [app.logdate.ui.common.AlertDialog] when it's true.
     */
    val deletePromptVisible: StateFlow<Boolean> = deletePromptVisibleFlow.asStateFlow()

    val uiState: StateFlow<RewindDetailUiState> =
        rewindIdState
            .flatMapLatest { rewindId ->
                if (rewindId == null) {
                    // Return a specific RewindNotSelected error state when rewindId is null
                    flowOf(RewindDetailUiState.Error.RewindNotSelected)
                } else {
                    // The detail screen needs three things in lockstep: the rewind itself, any
                    // typed replies the user has saved against its prompts, and the global
                    // toggle that decides whether reply chrome should appear at all. Combine
                    // them so the panel list rebuilds the moment any of them change.
                    val rewindFlow = getRewindUseCase(rewindId)
                    val panels =
                        combine(
                            rewindFlow,
                            observeReflectionPromptResponses(rewindId),
                            preferences.observeRewindReflectionRepliesEnabled(),
                        ) { rewind, responses, repliesEnabled ->
                            // Cache the raw rewind so stats summary share can read its metadata
                            // without re-fetching.
                            latestRewind.value = rewind
                            val panels = transformRewindToStoryPanels(rewind, responses, repliesEnabled)

                            if (panels.isEmpty()) {
                                RewindDetailUiState.Error.EmptyContent
                            } else {
                                RewindDetailUiState.Success(panels = panels)
                            }
                        }.flowOn(Dispatchers.Default)
                    combine(panels, rewindFlow.cachedWaveforms()) { state, waveforms -> state.withWaveforms(waveforms) }
                        .catch { e ->
                            Napier.e("Error loading rewind", e)
                            emit(RewindDetailUiState.Error.LoadingFailed)
                        }
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                RewindDetailUiState.Loading,
            )

    /**
     * Reads the cached waveform of each recording in the Rewind together, after its panels already
     * show, so opening a Rewind never waits on waveform storage.
     */
    private fun Flow<Rewind>.cachedWaveforms(): Flow<Map<String, List<Float>>> =
        map { rewind -> rewind.content.filterIsInstance<RewindContent.AudioNote>().mapTo(LinkedHashSet()) { it.uri } }
            .distinctUntilChanged()
            .transformLatest { uris ->
                emit(emptyMap())
                if (uris.isEmpty()) return@transformLatest
                val loaded =
                    coroutineScope {
                        uris.map { uri -> async { uri to loadWaveform(uri) } }.awaitAll()
                    }
                emit(loaded.mapNotNull { (uri, amplitudes) -> amplitudes?.let { uri to it } }.toMap())
            }.flowOn(Dispatchers.Default)

    private suspend fun loadWaveform(uri: String): List<Float>? =
        try {
            waveformStorage.load(uri)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Napier.w("Could not read a cached Rewind waveform", failure)
            null
        }

    /**
     * Loads rewind data for the specified ID and records that the user opened it.
     *
     * Also captures a one-shot [isFirstView] flag from the pre-write viewed state, so
     * the detail screen's first-view celebration doesn't race against the DB write.
     *
     * @param rewindId The unique identifier of the rewind to load
     */
    fun loadRewind(rewindId: Uuid) {
        // Reset the one-shot first-view flag synchronously so navigating to a new
        // rewind can't briefly display the stale value from the previous rewind.
        isFirstViewFlow.value = false
        rewindIdState.value = rewindId
        viewModelScope.launch {
            // Snapshot the viewed state before the DB write so the UI can show
            // the first-view celebration without racing against the Flow emission.
            // A short timeout guards against Room flows that don't emit promptly.
            val rewind = withTimeoutOrNull(1_500) { getRewindUseCase(rewindId).firstOrNull() }
            isFirstViewFlow.value = rewind?.isViewed == false
            markRewindViewed(rewindId)
        }
    }

    /**
     * Called by the story view after it has consumed the first-view signal (played
     * the entrance animation + haptic). Clears the flag so rotating or navigating
     * back into the same story doesn't replay the celebration.
     */
    fun onFirstViewConsumed() {
        isFirstViewFlow.value = false
    }

    /**
     * Starts or pauses playback for an audio-note panel. Tapping the panel that's
     * already playing pauses it; tapping a different one stops whatever was playing
     * and starts the new one.
     */
    fun toggleAudioPlayback(
        sourceId: Uuid,
        uri: String,
    ) {
        val currentlyPlaying = audioPlaybackStateFlow.value.playingSourceId
        if (currentlyPlaying == sourceId) {
            audioPlaybackManager.pausePlayback()
            audioPlaybackStateFlow.value = AudioPanelPlaybackUiState()
            return
        }
        audioPlaybackManager.startPlayback(
            uri = uri,
            onProgressUpdated = { progress ->
                audioPlaybackStateFlow.value = AudioPanelPlaybackUiState(sourceId, progress)
            },
            onPlaybackCompleted = {
                audioPlaybackStateFlow.value = AudioPanelPlaybackUiState()
            },
        )
        audioPlaybackStateFlow.value = AudioPanelPlaybackUiState(playingSourceId = sourceId)
    }

    override fun onCleared() {
        super.onCleared()
        if (audioPlaybackStateFlow.value.playingSourceId != null) {
            audioPlaybackManager.stopPlayback()
        }
    }

    /**
     * Shares a rewind panel through the platform share sheet.
     *
     * When the request's visual is a [RewindShareVisual.Quote], the view model first asks the
     * [RewindQuoteCardRenderer] to turn the user's words into a styled image so apps that
     * prefer media (Stories, Notes, Photos) get something visual to attach. If rendering is
     * unavailable the share still goes through with text only.
     */
    fun sharePanel(request: RewindShareRequest) {
        viewModelScope.launch {
            val resolvedMedia =
                when (val visual = request.visual) {
                    is RewindShareVisual.ExistingMedia -> visual.uri
                    is RewindShareVisual.Quote -> quoteCardRenderer.render(visual.toRewindQuote())
                    RewindShareVisual.None -> null
                }
            sharingLauncher.shareContent(
                text = request.text,
                mediaUris = resolvedMedia?.let { listOf(it) } ?: emptyList(),
                title = request.title,
                chooserTitle = request.chooserTitle,
            )
        }
    }

    private fun RewindShareVisual.Quote.toRewindQuote(): RewindQuote =
        RewindQuote(text = text, dateLabel = dateLabel, accentSeed = accentSeed)

    /**
     * Shares the current rewind as a single stats summary card.
     *
     * Reads the cached latest [Rewind], hands it (along with the localized labels resolved by
     * the composable) to the [RewindStatsSummaryRenderer], and then launches the system share
     * sheet with whatever URI the renderer produces. Falls back to the request's plain text
     * when the renderer is unavailable or returns null.
     */
    fun shareRewindStats(request: RewindStatsShareRequest) {
        val rewind = latestRewind.value ?: return
        viewModelScope.launch {
            val summary = buildStatsSummary(rewind, request)
            val mediaUri = statsSummaryRenderer.render(summary)
            sharingLauncher.shareContent(
                text = request.text,
                mediaUris = mediaUri?.let { listOf(it) } ?: emptyList(),
                title = request.title,
                chooserTitle = request.chooserTitle,
            )
        }
    }

    private fun buildStatsSummary(
        rewind: Rewind,
        request: RewindStatsShareRequest,
    ): RewindStatsSummary {
        val textCount = rewind.content.count { it is RewindContent.TextNote }
        val photoCount = rewind.content.count { it is RewindContent.Image || it is RewindContent.Video }
        val peopleCount = rewind.metadata?.peopleHighlighted?.size ?: 0

        val counters =
            buildList {
                if (textCount > 0) add(RewindStatsSummary.Counter(request.entriesLabel, textCount))
                if (photoCount > 0) add(RewindStatsSummary.Counter(request.photosLabel, photoCount))
                if (peopleCount > 0) add(RewindStatsSummary.Counter(request.peopleLabel, peopleCount))
            }

        val highlights =
            buildList {
                rewind.metadata
                    ?.detectedActivities
                    ?.firstOrNull()
                    ?.name
                    ?.let { activity ->
                        add(
                            RewindStatsSummary.Highlight(
                                heading = request.themeHeadingLabel,
                                value = activity.lowercase().replace('_', ' '),
                            ),
                        )
                    }
                rewind.metadata?.locationSummary?.primaryLocation?.let { location ->
                    add(
                        RewindStatsSummary.Highlight(
                            heading = request.locationHeadingLabel,
                            value = location,
                        ),
                    )
                }
            }

        return RewindStatsSummary(
            title = rewind.title,
            subtitle = request.subtitle,
            counters = counters,
            highlights = highlights,
            accentSeed = rewind.uid.hashCode(),
        )
    }

    /**
     * Transforms a Rewind domain model into UI panel states.
     *
     * This method converts domain-level Rewind data into a sequence of UI panels
     * for the Instagram Stories-like interface. It handles different content types
     * appropriately and creates a cohesive story flow.
     *
     * @param rewind The domain Rewind model to transform
     * @param responses Saved replies for this rewind, keyed by prompt. Used to surface
     *   the user's previous reply on the matching noticing-prompt panel.
     * @param repliesEnabled Whether the user wants reply chrome shown at all. When false,
     *   prompt panels still render but the chip and inline preview are suppressed.
     * @return List of UI panel states in presentation order
     */
    private suspend fun transformRewindToStoryPanels(
        rewind: Rewind,
        responses: Map<ReflectionPromptKey, ReflectionPromptResponse>,
        repliesEnabled: Boolean,
    ): List<RewindPanelUiState> {
        val panels = mutableListOf<RewindPanelUiState>()

        // 1. Add title panel with live stats from the rewind's actual content.
        val dateRange = formatDateRange(rewind.startDate, rewind.endDate)
        val textCount = rewind.content.count { it is RewindContent.TextNote }
        val photoCount = rewind.content.count { it is RewindContent.Image || it is RewindContent.Video }
        val peopleCount = rewind.metadata?.peopleHighlighted?.size ?: 0
        val counts =
            buildList {
                if (textCount > 0) add(Res.plurals.rewind_state_count_entries.toUiText(textCount, textCount))
                if (photoCount > 0) add(Res.plurals.rewind_state_count_photos.toUiText(photoCount, photoCount))
                if (peopleCount > 0) add(Res.plurals.rewind_state_count_people.toUiText(peopleCount, peopleCount))
            }
        panels.add(
            SubtitledRewindPanelUiState(
                title = rewind.title,
                subtitle = titleSubtitle(dateRange, counts),
                backgroundUri = null,
                weatherChip = rewind.metadata?.weatherContext?.toChipUiState(),
            ),
        )

        // 1b. Optional map panel — only when the rewind's path is geographically meaningful.
        val mapPoints =
            rewind.metadata
                ?.locationPath
                .orEmpty()
                .map { it.toPanelPoint() }
        if (mapPoints.qualifiesForMapPanel()) {
            val placeCount = rewind.metadata?.locationSummary?.distinctLocations ?: mapPoints.size
            panels.add(
                LocationMapRewindPanelUiState(
                    points = mapPoints,
                    title = Res.string.rewind_state_map_title.toUiText(),
                    subtitle = Res.plurals.rewind_state_map_places.toUiText(placeCount, placeCount),
                    accentSeed = rewind.uid.hashCode() xor MAP_SEED_OFFSET,
                ),
            )
        }

        // 2. Transform content items to panels
        val contentPanels =
            rewind.content.mapNotNull { content ->
                when (content) {
                    is RewindContent.TextNote -> {
                        TextNoteRewindPanelUiState(
                            sourceId = content.sourceId,
                            timestamp = content.timestamp,
                            content = content.content,
                            dateFormatted = formatTimestamp(content.timestamp),
                            background =
                                RewindPanelBackgroundSpec(
                                    color = 0xFF1A1A1A, // Dark gray background by default
                                ),
                        )
                    }

                    is RewindContent.Image -> {
                        ImageRewindPanelUiState(
                            sourceId = content.sourceId,
                            timestamp = content.timestamp,
                            imageUri = content.uri,
                            caption = content.caption,
                            dateFormatted = formatTimestamp(content.timestamp),
                        )
                    }

                    is RewindContent.Video -> {
                        content.toVideoFramePanel(formatTimestamp(content.timestamp))
                    }

                    is RewindContent.AudioNote -> {
                        AudioNoteRewindPanelUiState(
                            sourceId = content.sourceId,
                            timestamp = content.timestamp,
                            uri = content.uri,
                            durationMs = content.durationMs,
                            transcriptionText = content.transcriptionText,
                            dateFormatted = formatTimestamp(content.timestamp),
                        )
                    }

                    is RewindContent.NarrativeContext -> {
                        NarrativeContextRewindPanelUiState(
                            sourceId = content.sourceId,
                            timestamp = content.timestamp,
                            contextText = content.contextText,
                            backgroundImageUri = content.backgroundImage,
                        )
                    }

                    is RewindContent.Transition -> {
                        TransitionRewindPanelUiState(
                            sourceId = content.sourceId,
                            timestamp = content.timestamp,
                            transitionText = content.transitionText,
                        )
                    }

                    is RewindContent.MapPanel -> {
                        val points = content.locationPath.map { it.toPanelPoint() }
                        if (points.qualifiesForMapPanel()) {
                            LocationMapRewindPanelUiState(
                                points = points,
                                title = rewind.title.toUiText(),
                                subtitle = "".toUiText(),
                                accentSeed = content.sourceId.hashCode() xor MAP_SEED_OFFSET,
                            )
                        } else {
                            null
                        }
                    }

                    is RewindContent.WeatherPanel -> {
                        SubtitledRewindPanelUiState(
                            title = rewind.title,
                            subtitle = "".toUiText(),
                            weatherChip = content.weather.toChipUiState(),
                        )
                    }

                    is RewindContent.PersonalityCard -> {
                        BigStatisticRewindPanelUiState(
                            title = rewind.title,
                            statistic = content.stats.totalCount.toString(),
                            units = "".toUiText(),
                            description = "".toUiText(),
                            background =
                                RewindPanelBackgroundSpec(
                                    color = 0xFF4CAF50, // Green
                                ),
                        )
                    }

                    is RewindContent.TopList -> {
                        BasicTextRewindPanelUiState(
                            text = content.items.joinToString(separator = "\n") { it.label },
                            background =
                                RewindPanelBackgroundSpec(
                                    color = 0xFF2196F3, // Blue
                                ),
                        )
                    }
                }
            }

        panels.addAll(contentPanels)

        // 3. The stat panels reuse the counts from the title subtitle to stay consistent.
        if (textCount > 0) {
            panels.add(
                BigStatisticRewindPanelUiState(
                    title = rewind.title,
                    statistic = textCount.toString(),
                    units = Res.plurals.rewind_state_units_entries.toUiText(textCount),
                    description = Res.plurals.rewind_state_wrote_this_week.toUiText(textCount, textCount),
                    background =
                        RewindPanelBackgroundSpec(
                            color = 0xFF9C27B0, // Purple
                        ),
                ),
            )
        }
        if (photoCount > 0) {
            panels.add(
                BigStatisticRewindPanelUiState(
                    title = rewind.title,
                    statistic = photoCount.toString(),
                    units = Res.plurals.rewind_state_units_photos.toUiText(photoCount),
                    description = Res.plurals.rewind_state_captured_this_week.toUiText(photoCount, photoCount),
                    background =
                        RewindPanelBackgroundSpec(
                            color = 0xFF2196F3, // Blue
                        ),
                ),
            )
        }

        // 4. Beats the AI invented from the period's content: verbatim quotes first, then noticing prompts.
        rewind.metadata?.let { metadata ->
            val rewindSeed = rewind.uid.hashCode()
            metadata.highlightedQuotes.forEachIndexed { index, quote ->
                panels.add(
                    HighlightedQuoteRewindPanelUiState(
                        text = quote.text,
                        whyItHits = quote.whyItHits,
                        accentSeed = rewindSeed xor (QUOTE_SEED_OFFSET + index),
                    ),
                )
            }
            metadata.reflectionPrompts.forEachIndexed { index, prompt ->
                val existingReply = responses[prompt.key]?.responseText
                panels.add(
                    ReflectionPromptRewindPanelUiState(
                        observation = prompt.observation,
                        invitation = prompt.invitation,
                        accentSeed = rewindSeed xor (PROMPT_SEED_OFFSET + index),
                        existingResponse = existingReply,
                        repliesAllowed = repliesEnabled,
                    ),
                )
            }
        }

        // 5. Closing panel — use the AI-generated narrative when available so the
        // ending reads as a distillation of THIS specific week, not a canned template.
        val closingText =
            rewind.metadata
                ?.let {
                    // The sequencer already used the opening sentence of the narrative;
                    // the closing panel uses the last sentence(s) for a bookend effect.
                    val sentences = (rewind.content.lastOrNull() as? RewindContent.NarrativeContext)?.contextText
                    sentences ?: rewind.title
                } ?: rewind.title
        panels.add(
            BasicTextRewindPanelUiState(
                text = closingText,
                background =
                    RewindPanelBackgroundSpec(
                        color = 0xFFFF9800, // Orange
                    ),
            ),
        )

        return panels
    }

    /**
     * Describes when a panel's content was captured, such as "Nov 19, 2024".
     *
     * @param timestamp The timestamp to describe
     */
    private fun formatTimestamp(timestamp: Instant): UiText {
        val localDateTime = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
        return Res.string.rewind_state_date.toUiText(
            localDateTime.month.shortName.toUiText(),
            localDateTime.day,
            localDateTime.year,
        )
    }

    /**
     * Describes the period a rewind covers, such as "November 18-24, 2024" or
     * "November 30 - December 6, 2024".
     *
     * @param startDate Start of the date range
     * @param endDate End of the date range
     */
    private fun formatDateRange(
        startDate: Instant,
        endDate: Instant,
    ): UiText {
        val startLocal = startDate.toLocalDateTime(TimeZone.currentSystemDefault())
        val endLocal = endDate.toLocalDateTime(TimeZone.currentSystemDefault())

        if (startLocal.month == endLocal.month && startLocal.year == endLocal.year) {
            return Res.string.rewind_state_date_range_same_month.toUiText(
                startLocal.month.fullName.toUiText(),
                startLocal.day,
                endLocal.day,
                startLocal.year,
            )
        }

        return Res.string.rewind_state_date_range.toUiText(
            startLocal.month.fullName.toUiText(),
            startLocal.day,
            endLocal.month.fullName.toUiText(),
            endLocal.day,
            endLocal.year,
        )
    }

    /**
     * The title panel's subtitle: the date range, then the week's counts on the next line.
     */
    private fun titleSubtitle(
        dateRange: UiText,
        counts: List<UiText>,
    ): UiText {
        val countLine =
            when (counts.size) {
                0 -> return dateRange
                1 -> counts[0]
                2 -> Res.string.rewind_state_counts_two.toUiText(counts[0], counts[1])
                else -> Res.string.rewind_state_counts_three.toUiText(counts[0], counts[1], counts[2])
            }
        return Res.string.rewind_state_title_subtitle.toUiText(dateRange, countLine)
    }

    private val Month.fullName: StringResource
        get() =
            when (this) {
                Month.JANUARY -> Res.string.rewind_state_month_january
                Month.FEBRUARY -> Res.string.rewind_state_month_february
                Month.MARCH -> Res.string.rewind_state_month_march
                Month.APRIL -> Res.string.rewind_state_month_april
                Month.MAY -> Res.string.rewind_state_month_may
                Month.JUNE -> Res.string.rewind_state_month_june
                Month.JULY -> Res.string.rewind_state_month_july
                Month.AUGUST -> Res.string.rewind_state_month_august
                Month.SEPTEMBER -> Res.string.rewind_state_month_september
                Month.OCTOBER -> Res.string.rewind_state_month_october
                Month.NOVEMBER -> Res.string.rewind_state_month_november
                Month.DECEMBER -> Res.string.rewind_state_month_december
            }

    private val Month.shortName: StringResource
        get() =
            when (this) {
                Month.JANUARY -> Res.string.rewind_state_month_short_january
                Month.FEBRUARY -> Res.string.rewind_state_month_short_february
                Month.MARCH -> Res.string.rewind_state_month_short_march
                Month.APRIL -> Res.string.rewind_state_month_short_april
                Month.MAY -> Res.string.rewind_state_month_short_may
                Month.JUNE -> Res.string.rewind_state_month_short_june
                Month.JULY -> Res.string.rewind_state_month_short_july
                Month.AUGUST -> Res.string.rewind_state_month_short_august
                Month.SEPTEMBER -> Res.string.rewind_state_month_short_september
                Month.OCTOBER -> Res.string.rewind_state_month_short_october
                Month.NOVEMBER -> Res.string.rewind_state_month_short_november
                Month.DECEMBER -> Res.string.rewind_state_month_short_december
            }

    /**
     * Opens the reply sheet for [panel] so the user can type a response.
     *
     * Pulls the prompt out of the panel state instead of asking the caller for an index;
     * the prompt's stable [ReflectionPromptKey] takes care of finding any existing reply.
     */
    fun onReplyRequested(panel: ReflectionPromptRewindPanelUiState) {
        val prompt = ReflectionPrompt(observation = panel.observation, invitation = panel.invitation)
        replySheetStateFlow.value =
            ReflectionReplySheetState.Open(
                prompt = prompt,
                existing = panel.existingResponse,
            )
    }

    /**
     * Persists the user's typed reply to the prompt currently open in the reply sheet.
     *
     * Empty input clears the reply through the use case's blank-text contract; in either
     * case the sheet closes.
     */
    fun onReplySubmitted(text: String) {
        val state = replySheetStateFlow.value
        if (state !is ReflectionReplySheetState.Open) return
        val rewindId = rewindIdState.value ?: return
        viewModelScope.launch {
            try {
                saveReflectionPromptResponse(rewindId, state.prompt, text)
            } catch (e: Exception) {
                Napier.e("Failed to save reflection prompt response", e)
            } finally {
                replySheetStateFlow.value = ReflectionReplySheetState.Closed
            }
        }
    }

    /** Closes the reply sheet without persisting whatever the user had typed. */
    fun onReplyDismissed() {
        replySheetStateFlow.value = ReflectionReplySheetState.Closed
    }

    /** Opens the "delete this rewind?" confirmation dialog. */
    fun onDeleteRequested() {
        deletePromptVisibleFlow.value = true
    }

    /** Closes the delete confirmation dialog without doing anything. */
    fun onDeleteCancelled() {
        deletePromptVisibleFlow.value = false
    }

    /**
     * Removes the currently loaded rewind from storage and signals the screen to navigate
     * away via [onDeleted].
     *
     * The screen passes [onDeleted] instead of the view model holding a navigation callback
     * because navigation belongs to the composable layer; the view model just owns the
     * "should we exit now" timing.
     */
    fun onDeleteConfirmed(onDeleted: () -> Unit) {
        val rewindId = rewindIdState.value ?: return
        viewModelScope.launch {
            try {
                deleteRewindUseCase(rewindId)
                onDeleted()
            } catch (e: Exception) {
                Napier.e("Failed to delete rewind", e)
            } finally {
                deletePromptVisibleFlow.value = false
            }
        }
    }

    private fun MapPoint.toPanelPoint(): MapPanelPoint = MapPanelPoint(latitude = latitude, longitude = longitude)

    private fun WeatherContext.toChipUiState(): WeatherChipUiState =
        WeatherChipUiState(
            category =
                when (category) {
                    WeatherCategory.SUNNY -> WeatherChipCategory.SUNNY
                    WeatherCategory.CLOUDY -> WeatherChipCategory.CLOUDY
                    WeatherCategory.RAINY -> WeatherChipCategory.RAINY
                    WeatherCategory.SNOWY -> WeatherChipCategory.SNOWY
                    WeatherCategory.MIXED -> WeatherChipCategory.MIXED
                },
            avgTempCelsius = avgTempCelsius,
        )

    private val app.logdate.shared.model.WeekStatsSnapshot.totalCount: Int
        get() = photoCount + textNoteCount + distinctLocations + distinctPeople + newPlaces

    private companion object {
        // Per-panel-type offsets keep quote and prompt accent seeds from colliding when both
        // are derived from the same rewind uid. The exact values don't matter — only that
        // they're stable and distinct.
        const val QUOTE_SEED_OFFSET = 0x517A
        const val PROMPT_SEED_OFFSET = 0x9C0D
        const val MAP_SEED_OFFSET = 0x3F8C
    }
}

internal fun RewindDetailUiState.withWaveforms(waveforms: Map<String, List<Float>>): RewindDetailUiState {
    if (this !is RewindDetailUiState.Success || waveforms.isEmpty()) return this
    return copy(
        panels =
            panels.map { panel ->
                if (panel is AudioNoteRewindPanelUiState) panel.copy(cachedAmplitudes = waveforms[panel.uri]) else panel
            },
    )
}
