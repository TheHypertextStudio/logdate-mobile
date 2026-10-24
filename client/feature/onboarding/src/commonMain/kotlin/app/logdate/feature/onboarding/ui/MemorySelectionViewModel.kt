package app.logdate.feature.onboarding.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaObject
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val SELECTED_MEMORY_IDS_KEY = "memory_selection_selected_ids"
private const val MEMORY_NOTE_IDS_KEY = "memory_selection_note_ids"

/**
 * ViewModel for the memory selection screen during onboarding.
 */
class MemorySelectionViewModel(
    private val mediaManager: MediaManager,
    private val aiClient: GenerativeAIChatClient,
    private val savedStateHandle: SavedStateHandle,
    private val notesRepository: JournalNotesRepository,
    private val mediaImporter: SelectedMemoryMediaImporter,
) : ViewModel() {
    private val _uiState = MutableStateFlow(savedStateHandle.restoreUiState())
    val uiState: StateFlow<MemorySelectionUiState> = _uiState.asStateFlow()

    private var availableMemories: List<MediaObject> = emptyList()
    private var currentPage = 0
    private val pageSize = 20

    /**
     * Loads initial memories and AI-curated content.
     */
    private fun loadInitialMemories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }

            try {
                val recentMemories = loadAvailableMemories()
                availableMemories = recentMemories

                // Load first page of all memories
                val initialMemories = recentMemories.take(pageSize)

                // Get AI-curated memories with high emotional salience
                val aiCuratedMemories = getCuratedMemories(recentMemories)

                _uiState.update {
                    it.copy(
                        allMemories = initialMemories,
                        aiCuratedMemories = aiCuratedMemories,
                        isLoading = false,
                        hasMoreMemories = recentMemories.size > pageSize,
                        loadFailed = false,
                    )
                }

                currentPage = if (initialMemories.isEmpty()) 0 else 1

                Napier.d(
                    tag = "MemorySelectionViewModel",
                    message = "Loaded ${initialMemories.size} memories, ${aiCuratedMemories.size} AI-curated",
                )
            } catch (e: Exception) {
                Napier.e(
                    tag = "MemorySelectionViewModel",
                    message = "Failed to load memories",
                    throwable = e,
                )
                availableMemories = emptyList()
                currentPage = 0
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasMoreMemories = false,
                        allMemories = emptyList(),
                        aiCuratedMemories = emptyList(),
                        loadFailed = true,
                    )
                }
            }
        }
    }

    fun refreshMemories() {
        loadInitialMemories()
    }

    private suspend fun loadAvailableMemories(): List<MediaObject> {
        val libraryMemories =
            mediaManager
                .queryMediaByDate(
                    start = Instant.DISTANT_PAST,
                    end = Instant.DISTANT_FUTURE,
                ).first()

        if (libraryMemories.isNotEmpty()) {
            return libraryMemories.sortedByDescending(MediaObject::timestamp)
        }

        return mediaManager.getRecentMedia().first().sortedByDescending(MediaObject::timestamp)
    }

    /**
     * Uses AI to identify memories with high emotional salience.
     */
    private suspend fun getCuratedMemories(allMemories: List<MediaObject>): List<MediaObject> =
        try {
            // For now, use a simple heuristic - in real implementation would use AI analysis
            // Select diverse content types and recent important-looking memories
            val candidates = allMemories.take(20) // Analyze first 20 for performance

            // Simple heuristic: prefer larger files (likely higher quality) and diverse types
            val images =
                candidates
                    .filterIsInstance<MediaObject.Image>()
                    .sortedByDescending { it.size }
                    .take(4)

            val videos =
                candidates
                    .filterIsInstance<MediaObject.Video>()
                    .sortedByDescending { it.duration }
                    .take(2)

            (images + videos).shuffled().take(6)
        } catch (e: Exception) {
            Napier.e(
                tag = "MemorySelectionViewModel",
                message = "Failed to get AI-curated memories",
                throwable = e,
            )
            // Fallback to simple selection
            allMemories.take(6)
        }

    /**
     * Loads more memories for infinite scroll.
     */
    fun loadMoreMemories() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMoreMemories) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            try {
                val allRecentMemories =
                    if (availableMemories.isNotEmpty()) {
                        availableMemories
                    } else {
                        loadAvailableMemories().also { availableMemories = it }
                    }

                val startIndex = currentPage * pageSize
                val endIndex = (startIndex + pageSize).coerceAtMost(allRecentMemories.size)

                if (startIndex < allRecentMemories.size) {
                    val newMemories = allRecentMemories.subList(startIndex, endIndex)

                    _uiState.update {
                        it.copy(
                            allMemories = it.allMemories + newMemories,
                            isLoadingMore = false,
                            hasMoreMemories = endIndex < allRecentMemories.size,
                        )
                    }

                    currentPage++

                    Napier.d(
                        tag = "MemorySelectionViewModel",
                        message = "Loaded ${newMemories.size} more memories, page $currentPage",
                    )
                } else {
                    _uiState.update {
                        it.copy(isLoadingMore = false, hasMoreMemories = false)
                    }
                }
            } catch (e: Exception) {
                Napier.e(
                    tag = "MemorySelectionViewModel",
                    message = "Failed to load more memories",
                    throwable = e,
                )
                _uiState.update {
                    it.copy(isLoadingMore = false)
                }
            }
        }
    }

    /**
     * Toggles selection state of a memory.
     */
    fun toggleMemorySelection(memoryUri: String) {
        if (_uiState.value.isImporting) return
        val previous = _uiState.value.selectedMemoryIds
        val selected = if (memoryUri in previous) previous - memoryUri else previous + memoryUri
        _uiState.update { it.copy(selectedMemoryIds = selected) }
        savedStateHandle.persistSelectedMemoryIds(selected)

        Napier.d(
            tag = "MemorySelectionViewModel",
            message = "Toggled selection for $memoryUri, now have ${selected.size} selected",
        )
    }

    /**
     * Gets the currently selected memories.
     */
    fun getSelectedMemories(): List<MediaObject> {
        val currentState = _uiState.value
        val allMemories = availableMemories + currentState.allMemories + currentState.aiCuratedMemories
        return allMemories.filter { it.uri in currentState.selectedMemoryIds }.distinctBy { it.uri }
    }

    /**
     * Processes the selected memories for import.
     *
     * Guards against a double-tap firing a second concurrent import pass while one is already
     * in flight.
     */
    suspend fun processSelectedMemories(): Result<Unit> {
        if (_uiState.value.isImporting) {
            return Result.failure(IllegalStateException("Import already in progress"))
        }

        _uiState.update { it.copy(isImporting = true, importFailed = false) }

        val result =
            try {
                val selectedMemories = getSelectedMemories()
                check(selectedMemories.size == _uiState.value.selectedMemoryIds.size) {
                    "Some selected memories are no longer available"
                }

                Napier.i(
                    tag = "MemorySelectionViewModel",
                    message = "Processing ${selectedMemories.size} selected memories for import",
                )

                selectedMemories.forEach { memory -> importSelectedMemory(memory) }

                Napier.i(
                    tag = "MemorySelectionViewModel",
                    message = "Successfully imported ${selectedMemories.size} memories",
                )
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(isImporting = false) }
                throw cancelled
            } catch (error: Exception) {
                Napier.e(
                    tag = "MemorySelectionViewModel",
                    message = "Failed to import selected memories",
                    throwable = error,
                )
                Result.failure(error)
            }

        _uiState.update { it.copy(isImporting = false, importFailed = result.isFailure) }
        return result
    }

    private suspend fun importSelectedMemory(memory: MediaObject) {
        val noteId = savedStateHandle.noteIdFor(memory.uri)
        if (notesRepository.getNoteById(noteId) != null) return

        val managedUri = mediaImporter.import(memory.uri)
        check(managedUri.isNotBlank() && managedUri != memory.uri) {
            "Memory import did not create an app-managed copy"
        }
        val note =
            when (memory) {
                is MediaObject.Image -> {
                    JournalNote.Image(
                        uid = noteId,
                        creationTimestamp = memory.timestamp,
                        lastUpdated = Clock.System.now(),
                        mediaRef = managedUri,
                    )
                }

                is MediaObject.Video -> {
                    JournalNote.Video(
                        uid = noteId,
                        creationTimestamp = memory.timestamp,
                        lastUpdated = Clock.System.now(),
                        mediaRef = managedUri,
                    )
                }
            }
        try {
            notesRepository.create(note)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    val published = notesRepository.getNoteById(noteId) != null
                    val shared = notesRepository.notesReferencingMediaPaths(setOf(managedUri)).isNotEmpty()
                    if (!published && !shared) mediaImporter.discard(managedUri)
                }.onFailure { error -> Napier.w("Could not clean up an unpublished imported photo or video", error) }
            }
            throw failure
        }
    }
}

private fun SavedStateHandle.noteIdFor(sourceUri: String): Uuid {
    val saved = get<List<String>>(MEMORY_NOTE_IDS_KEY).orEmpty()
    saved.chunked(2).firstOrNull { it.size == 2 && it[0] == sourceUri }?.let { return Uuid.parse(it[1]) }
    val noteId = Uuid.random()
    set(MEMORY_NOTE_IDS_KEY, saved + sourceUri + noteId.toString())
    return noteId
}

private fun SavedStateHandle.restoreUiState(): MemorySelectionUiState =
    MemorySelectionUiState(
        isLoading = false,
        loadFailed = false,
        selectedMemoryIds = get<List<String>>(SELECTED_MEMORY_IDS_KEY)?.toSet() ?: emptySet(),
    )

private fun SavedStateHandle.persistSelectedMemoryIds(selectedMemoryIds: Set<String>) {
    set(SELECTED_MEMORY_IDS_KEY, selectedMemoryIds.toList())
}
