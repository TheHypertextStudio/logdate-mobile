package app.logdate.feature.library.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.media.IndexedMediaRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.CoroutineContext

/**
 * ViewModel for the Library screen.
 *
 * Observes the Library's visible media set, preferring indexed media when available and
 * falling back to note-backed photos/videos so existing media still appears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val notesRepository: JournalNotesRepository,
    private val indexedMediaRepository: IndexedMediaRepository,
    private val workContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    private val retryRequests = MutableStateFlow(0)

    fun retry() {
        retryRequests.value += 1
    }

    val uiState: StateFlow<LibraryUiState> =
        retryRequests
            .flatMapLatest {
                combine(
                    indexedMediaRepository.observeAllMedia(),
                    notesRepository.allNotesObserved,
                ) { indexedMedia, notes ->
                    toUiState(buildLibraryMediaSources(indexedMedia, notes))
                }.flowOn(workContext)
                    .onStart { emit(LibraryUiState.Loading) }
                    .catch { e ->
                        Napier.e("Failed to load library media", e)
                        emit(LibraryUiState.Error)
                    }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LibraryUiState.Loading,
            )

    private fun toUiState(mediaSources: List<LibraryMediaSource>): LibraryUiState =
        if (mediaSources.isEmpty()) {
            LibraryUiState.Empty
        } else {
            LibraryUiState.Content(
                groups = groupByMonth(mediaSources),
                totalCount = mediaSources.size,
            )
        }

    /** Groups media that [buildLibraryMediaSources] already returns newest first. */
    private fun groupByMonth(mediaItems: List<LibraryMediaSource>): List<LibraryGridGroup> {
        val timeZone = TimeZone.currentSystemDefault()
        val grouped =
            mediaItems.groupBy { media ->
                val localDate = media.timestamp.toLocalDateTime(timeZone)
                "${localDate.year}-${localDate.month}"
            }
        return grouped.map { (_, items) ->
            val firstItem = items.first()
            val localDate = firstItem.timestamp.toLocalDateTime(timeZone)
            val label = "${localDate.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${localDate.year}"
            LibraryGridGroup(
                label = label,
                items = items.map { it.toGridItem() },
            )
        }
    }

    private fun LibraryMediaSource.toGridItem(): LibraryMediaItem =
        LibraryMediaItem(
            uid = id,
            uri = uri,
            thumbnailUri = null,
            isVideo = isVideo,
            isAudio = isAudio,
            durationMs = durationMs,
            timestamp = timestamp,
        )
}
