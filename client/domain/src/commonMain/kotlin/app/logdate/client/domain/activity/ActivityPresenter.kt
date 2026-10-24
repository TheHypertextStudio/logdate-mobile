package app.logdate.client.domain.activity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** What Today and Home show for the person's activity. Everything is empty while activity awareness is off. */
data class ActivityUiState(
    /** Voice capture comes first, while the person is moving. */
    val voiceFirst: Boolean = false,
    /** The offer to add a moment from a walk, ride or trip that just ended. */
    val suggestion: ActivitySuggestion? = null,
    /** The night the person just woke from, while the morning start shows. */
    val morningStart: Activity? = null,
)

/** An offer to add a moment from [activity], which lasted [minutes]. */
data class ActivitySuggestion(
    val activity: Activity,
    val minutes: Int,
)

/**
 * Turns the agreed activity into what Today and Home show. A suggestion appears once per activity:
 * it goes away when the person adds a moment, dismisses or uses it, another activity starts, or
 * [SHOWN_FOR] passes. The morning start shows after a night's sleep until the first capture or
 * [SHOWN_FOR] later.
 *
 * @param capturedSince Whether anything was saved at or after an instant.
 * @param ticks Re-evaluates the state, so a suggestion expires on time; every minute outside tests.
 */
class ActivityPresenter(
    private val agreed: Flow<AgreedActivity>,
    private val outcomes: SuggestionOutcomes,
    private val capturedSince: (kotlin.time.Instant) -> Flow<Boolean>,
    private val clock: Clock = Clock.System,
    private val ticks: Flow<Unit> = everyMinute(),
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<ActivityUiState> =
        agreed
            .flatMapLatest { activity ->
                val ended = activity.lastEnded?.endedAt
                val captured = if (ended == null) flowOf(false) else capturedSince(ended)
                combine(captured, outcomes.outcomes, ticks) { capturedAfter, history, _ ->
                    present(activity, capturedAfter, history)
                }
            }.distinctUntilChanged()
            .onEach { state -> recordFirstShowing(state) }

    /** The person dismissed what is showing. */
    suspend fun dismiss() = recordShowing(SuggestionResult.DISMISSED)

    /** The person added a moment from what is showing. */
    suspend fun actedOn() = recordShowing(SuggestionResult.ACTED_ON)

    private fun present(
        activity: AgreedActivity,
        capturedAfter: Boolean,
        history: List<SuggestionOutcome>,
    ): ActivityUiState {
        val now = clock.now()
        val voiceFirst = activity.current?.kind == ActivityKind.MOVING
        val ended = activity.lastEnded
        val endedAt = ended?.endedAt
        val offered =
            ended != null &&
                endedAt != null &&
                activity.current == null &&
                !capturedAfter &&
                now - endedAt <= SHOWN_FOR &&
                endedAt - ended.startedAt >= ended.kind.minimum
        if (!offered || ended == null || endedAt == null) return ActivityUiState(voiceFirst = voiceFirst)
        return when (ended.kind) {
            ActivityKind.MOVING, ActivityKind.IN_VEHICLE -> {
                ActivityUiState(
                    voiceFirst = voiceFirst,
                    suggestion =
                        ActivitySuggestion(ended, (endedAt - ended.startedAt).inWholeMinutes.toInt())
                            .takeUnless { history.closed(SuggestionKind.AFTER_ACTIVITY, ended) },
                )
            }

            ActivityKind.SLEEP -> {
                ActivityUiState(
                    voiceFirst = voiceFirst,
                    morningStart =
                        ended.takeUnless {
                            history.closed(SuggestionKind.MORNING_START, it)
                        },
                )
            }
        }
    }

    private suspend fun recordFirstShowing(state: ActivityUiState) {
        val (kind, activity) = state.showing() ?: return
        if (outcomes.outcomes.first().any { it.isFor(kind, activity) }) return
        outcomes.record(kind, activity, SuggestionResult.SHOWN)
    }

    private suspend fun recordShowing(result: SuggestionResult) {
        val (kind, activity) = state.first().showing() ?: return
        outcomes.record(kind, activity, result)
    }

    private fun ActivityUiState.showing(): Pair<SuggestionKind, Activity>? =
        suggestion?.let { SuggestionKind.AFTER_ACTIVITY to it.activity } ?: morningStart?.let { SuggestionKind.MORNING_START to it }

    private fun List<SuggestionOutcome>.closed(
        kind: SuggestionKind,
        activity: Activity,
    ): Boolean = any { it.isFor(kind, activity) && it.result != SuggestionResult.SHOWN }

    companion object {
        /** How long after an activity ends its suggestion or the morning start can show. */
        val SHOWN_FOR = 2.hours

        private fun everyMinute(): Flow<Unit> =
            flow {
                while (true) {
                    emit(Unit)
                    delay(1.minutes)
                }
            }
    }
}
