@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.journals.DeleteJournalUseCase
import app.logdate.client.domain.journals.GetJournalByIdUseCase
import app.logdate.client.domain.journals.UpdateJournalUseCase
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sharing.JournalLinkSharingAvailability
import app.logdate.client.sharing.SharingLauncher
import app.logdate.shared.model.Journal
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.Uuid

/**
 * View model for managing journal settings.
 * Provides functions to update journal properties and delete the journal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalSettingsViewModel(
    private val getJournalByIdUseCase: GetJournalByIdUseCase,
    private val updateJournalUseCase: UpdateJournalUseCase,
    private val deleteJournalUseCase: DeleteJournalUseCase,
    private val journalContentRepository: JournalContentRepository,
    private val sharingLauncher: SharingLauncher,
    private val linkSharingAvailability: JournalLinkSharingAvailability,
) : ViewModel() {
    private val journalIdState = MutableStateFlow<Uuid?>(null)

    private val editedNameState = MutableStateFlow<String?>(null)
    private val editedDescriptionState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<JournalSettingsUiState> =
        journalIdState
            .filterNotNull()
            .flatMapLatest { id ->
                getJournalByIdUseCase(id)
                    .combine(editedNameState) { journal, editedName -> journal to editedName }
                    .combine(editedDescriptionState) { (journal, editedName), editedDesc ->
                        Triple(journal, editedName, editedDesc)
                    }.combine(journalContentRepository.observeContentForJournal(id)) { (journal, editedName, editedDesc), notes ->
                        val nameToUse = editedName ?: journal.title
                        val descToUse = editedDesc ?: journal.description
                        val hasChanges = nameToUse != journal.title || descToUse != journal.description

                        JournalSettingsUiState.Loaded(
                            journal = journal,
                            editedName = nameToUse,
                            editedDescription = descToUse,
                            hasUnsavedChanges = hasChanges,
                            insights = computeInsights(notes),
                        )
                    }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                JournalSettingsUiState.Unknown,
            )

    private fun computeInsights(notes: List<JournalNote>): JournalInsights {
        val textCount = notes.count { it is JournalNote.Text }
        val imageCount = notes.count { it is JournalNote.Image }
        val videoCount = notes.count { it is JournalNote.Video }
        val audioCount = notes.count { it is JournalNote.Audio }

        val timestamps = notes.map { it.creationTimestamp }
        val oldest = timestamps.minOrNull()
        val newest = timestamps.maxOrNull()

        val tz = TimeZone.currentSystemDefault()
        val mostActiveDay =
            timestamps
                .groupBy { it.toLocalDateTime(tz).date }
                .maxByOrNull { it.value.size }
                ?.key

        return JournalInsights(
            entryCount = notes.size,
            textCount = textCount,
            imageCount = imageCount,
            videoCount = videoCount,
            audioCount = audioCount,
            oldestEntry = oldest,
            newestEntry = newest,
            mostActiveDay = mostActiveDay,
        )
    }

    /**
     * Sets the journal ID to load.
     */
    fun setSelectedJournalId(journalId: Uuid) {
        journalIdState.value = journalId
    }

    /**
     * Whether the journal can be shared as a link that reaches someone else.
     */
    val canShareJournalLink: Boolean
        get() = linkSharingAvailability.isAvailable()

    /**
     * Shares the journal using the platform-specific sharing launcher.
     * Launches a system share sheet with the journal link.
     */
    fun shareJournal() {
        if (!canShareJournalLink) return
        val journalId = journalIdState.value ?: return

        try {
            sharingLauncher.shareJournalLink(journalId)
        } catch (e: Exception) {
            Napier.e("Failed to share journal", e)
        }
    }

    /**
     * Updates the journal name in local state without saving to repository.
     * This tracks the changes in the UI but doesn't persist them.
     *
     * @param newName The new name for the journal.
     */
    fun updateJournalName(newName: String) {
        editedNameState.value = newName
    }

    fun updateJournalDescription(newDescription: String) {
        editedDescriptionState.value = newDescription
    }

    /**
     * Saves the current journal name using the update use case.
     *
     * @param onSuccess Callback to be invoked when the journal is successfully updated
     */
    fun saveJournalChanges(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            if (journalIdState.value == null) return@launch
            val currentState = uiState.value

            // Only save if we have unsaved changes
            if (currentState is JournalSettingsUiState.Loaded && currentState.hasUnsavedChanges) {
                // Get the current journal and update its name
                val updatedJournal =
                    currentState.journal.copy(
                        title = currentState.editedName,
                        description = currentState.editedDescription,
                    )

                val success = updateJournalUseCase(updatedJournal)

                if (success) {
                    editedNameState.value = null
                    editedDescriptionState.value = null
                    onSuccess()
                } else {
                    Napier.e("Failed to update journal name")
                }
            } else {
                // If no changes, still call success
                onSuccess()
            }
        }
    }

    /**
     * Deletes the current journal.
     *
     * @param onSuccess Callback to be invoked when the journal is successfully deleted.
     */
    fun deleteJournal(onSuccess: () -> Unit) {
        viewModelScope.launch {
            journalIdState.value?.let { id ->
                val success = deleteJournalUseCase(id)

                if (success) {
                    onSuccess()
                } else {
                    Napier.e("Failed to delete journal")
                }
            }
        }
    }
}

/**
 * UI state for the journal settings screen.
 */
sealed class JournalSettingsUiState {
    /**
     * State when the journal is loaded and ready for editing
     *
     * @param journal The journal being edited
     * @param editedName Current value of the journal name field (may be different from journal.title)
     * @param hasUnsavedChanges Whether there are unsaved changes to the journal
     */
    data class Loaded(
        val journal: Journal,
        val editedName: String = journal.title,
        val editedDescription: String = journal.description,
        val hasUnsavedChanges: Boolean = false,
        val insights: JournalInsights? = null,
    ) : JournalSettingsUiState()

    data object Unknown : JournalSettingsUiState()
}

/**
 * Aggregate statistics about a journal's contents.
 */
data class JournalInsights(
    val entryCount: Int,
    val textCount: Int,
    val imageCount: Int,
    val videoCount: Int,
    val audioCount: Int,
    val oldestEntry: kotlin.time.Instant?,
    val newestEntry: kotlin.time.Instant?,
    val mostActiveDay: kotlinx.datetime.LocalDate?,
)
