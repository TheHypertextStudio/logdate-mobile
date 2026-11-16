package app.logdate.client.feature.widgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.transcription.TranscriptionRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/** One entry the pinned-memory setup can offer, with the words its row shows. */
internal data class FixedMemoryChoice(
    val note: JournalNote,
    val content: ChosenEntryContent,
    val transcript: String?,
)

internal data class FixedMemorySetupState(
    val isLoading: Boolean = true,
    val query: String = "",
    val choices: List<FixedMemoryChoice> = emptyList(),
    val loadedCount: Int = 0,
    val canLoadMore: Boolean = false,
    val selected: FixedMemoryChoice? = null,
)

/**
 * Pages the entries a pinned-memory widget can show, newest first, and resolves recording
 * transcripts once per entry instead of once per composed row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FixedMemorySetupViewModel(
    private val notesRepository: JournalNotesRepository,
    private val transcriptionRepository: TranscriptionRepository,
    initialSelection: String?,
    private val workContext: CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    private val pageLimit = MutableStateFlow(PAGE_SIZE)
    private val query = MutableStateFlow("")
    private val selectedId = MutableStateFlow(initialSelection?.let(Uuid::parseOrNull))
    private val storedTranscripts = ConcurrentHashMap<Uuid, String>()

    private val page =
        pageLimit.flatMapLatest { limit ->
            notesRepository.observeNotesStream(limit).map { notes ->
                LoadedPage(notes.map { note -> note.toChoice() }, canLoadMore = notes.size >= limit)
            }
        }

    private val selected =
        selectedId.flatMapLatest { id ->
            if (id == null) flowOf(null) else notesRepository.observeNoteById(id).map { note -> note?.toChoice() }
        }

    val state: StateFlow<FixedMemorySetupState> =
        combine(page, query, selected) { loaded, currentQuery, chosen ->
            FixedMemorySetupState(
                isLoading = false,
                query = currentQuery,
                choices = loaded.choices.filter { it.matches(currentQuery) },
                loadedCount = loaded.choices.size,
                canLoadMore = loaded.canLoadMore,
                selected = chosen,
            )
        }.flowOn(workContext)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FixedMemorySetupState())

    fun select(noteId: Uuid) {
        selectedId.value = noteId
    }

    fun search(text: String) {
        query.value = text
    }

    /** Loads the next page once the list shows the end of what is loaded. */
    fun loadMore() {
        val current = state.value
        if (!current.canLoadMore) return
        pageLimit.update { limit -> maxOf(limit, current.loadedCount + PAGE_SIZE) }
    }

    private suspend fun JournalNote.toChoice(): FixedMemoryChoice {
        val transcript = (this as? JournalNote.Audio)?.let { audio -> transcriptFor(audio) }
        return FixedMemoryChoice(this, toChosenEntryContent(transcript), transcript)
    }

    private suspend fun transcriptFor(audio: JournalNote.Audio): String? {
        audio.transcript
            ?.plainText
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        storedTranscripts[audio.uid]?.let { stored -> return stored.ifEmpty { null } }
        val stored =
            try {
                transcriptionRepository.getTranscription(audio.uid)?.displayText()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Napier.w("Could not read a recording transcript for widget setup", failure)
                null
            }
        storedTranscripts[audio.uid] = stored.orEmpty()
        return stored
    }

    private fun FixedMemoryChoice.matches(text: String): Boolean =
        text.isBlank() || content.summary.contains(text, ignoreCase = true) || content.dateIso.contains(text)

    private class LoadedPage(
        val choices: List<FixedMemoryChoice>,
        val canLoadMore: Boolean,
    )

    private companion object {
        const val PAGE_SIZE = 50
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
