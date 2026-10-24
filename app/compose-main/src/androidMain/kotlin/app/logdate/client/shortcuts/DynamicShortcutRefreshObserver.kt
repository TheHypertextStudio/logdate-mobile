package app.logdate.client.shortcuts

import app.logdate.client.domain.journals.GetCurrentUserJournalsUseCase
import app.logdate.client.domain.notes.drafts.FetchMostRecentDraftUseCase
import app.logdate.client.domain.rewind.GetWeekRewindUseCase
import app.logdate.client.domain.rewind.RewindQueryResult
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.shared.model.Journal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Watches shortcut-relevant app state and schedules a refresh when it changes.
 *
 * This keeps the launcher and Direct Share targets closer to live app state
 * while the process is running, without requiring UI code to remember to poke
 * the shortcut worker.
 */
class DynamicShortcutRefreshObserver(
    private val fetchMostRecentDraft: FetchMostRecentDraftUseCase,
    private val currentWeekRewind: GetWeekRewindUseCase,
    private val observeJournals: GetCurrentUserJournalsUseCase,
    private val shortcutScheduler: DynamicShortcutScheduler,
    private val applicationScope: CoroutineScope,
) {
    private var observationJob: Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (observationJob != null) return
        observationJob =
            applicationScope.launch {
                combine(
                    fetchMostRecentDraft().mapToDraftSnapshot(),
                    currentWeekRewind().mapToRewindSnapshot(),
                    observeJournals().mapToJournalSnapshots(),
                ) { draft, rewind, journals ->
                    ShortcutRefreshState(draft = draft, rewind = rewind, journals = journals)
                }.distinctUntilChanged()
                    .drop(1)
                    .debounce(500.milliseconds)
                    .collect {
                        shortcutScheduler.enqueueImmediateRefresh()
                    }
            }
    }

    private fun Flow<EntryDraft?>.mapToDraftSnapshot(): Flow<DraftShortcutSnapshot> =
        map { draft ->
            DraftShortcutSnapshot(
                id = draft?.id,
                updatedAtMillis = draft?.updatedAt?.toEpochMilliseconds(),
                noteCount = draft?.notes?.size ?: 0,
            )
        }

    private fun Flow<RewindQueryResult>.mapToRewindSnapshot(): Flow<RewindShortcutSnapshot> =
        map { result ->
            when (result) {
                is RewindQueryResult.Success -> {
                    RewindShortcutSnapshot(
                        state = "success",
                        rewindId = result.rewind.uid,
                    )
                }

                RewindQueryResult.NotReady -> {
                    RewindShortcutSnapshot(state = "not_ready")
                }

                RewindQueryResult.Generating -> {
                    RewindShortcutSnapshot(state = "generating")
                }

                RewindQueryResult.NoneAvailable -> {
                    RewindShortcutSnapshot(state = "none")
                }

                RewindQueryResult.Failed -> {
                    RewindShortcutSnapshot(state = "failed")
                }
            }
        }

    private fun Flow<List<Journal>>.mapToJournalSnapshots(): Flow<List<JournalShortcutSnapshot>> =
        map { journals ->
            journals.map { journal ->
                JournalShortcutSnapshot(
                    id = journal.id,
                    title = journal.title,
                    isFavorited = journal.isFavorited,
                    lastUpdatedMillis = journal.lastUpdated.toEpochMilliseconds(),
                    coverImageUri = journal.coverImageUri,
                )
            }
        }

    private data class ShortcutRefreshState(
        val draft: DraftShortcutSnapshot,
        val rewind: RewindShortcutSnapshot,
        val journals: List<JournalShortcutSnapshot>,
    )

    private data class DraftShortcutSnapshot(
        val id: Uuid?,
        val updatedAtMillis: Long?,
        val noteCount: Int,
    )

    private data class RewindShortcutSnapshot(
        val state: String,
        val rewindId: Uuid? = null,
    )

    private data class JournalShortcutSnapshot(
        val id: Uuid,
        val title: String,
        val isFavorited: Boolean,
        val lastUpdatedMillis: Long,
        val coverImageUri: String?,
    )
}
