package app.logdate.feature.core.assistants

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.assistants.DeviceAssistantAccess
import app.logdate.client.repository.assistants.AssistantContentScope
import app.logdate.shared.model.Journal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The journals the person can share and what they allow assistants on this phone today. */
data class AndroidAssistantsUiState(
    val journals: List<AssistantJournalOption>,
    val access: AssistantAccessSelection,
)

/** Shows and saves what assistants on this phone may see and do. */
class AndroidAssistantsViewModel(
    access: Flow<DeviceAssistantAccess>,
    private val saveAccess: suspend (DeviceAssistantAccess) -> Unit,
    journals: Flow<List<Journal>>,
) : ViewModel() {
    /** Null until the saved access and the journals have loaded. */
    val state: StateFlow<AndroidAssistantsUiState?> =
        combine(access, journals) { allowed, all ->
            AndroidAssistantsUiState(
                journals = all.map { AssistantJournalOption(it.id, it.title) },
                access = allowed.toSelection(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAccess(selection: AssistantAccessSelection) {
        viewModelScope.launch { saveAccess(selection.toDeviceAccess()) }
    }
}

internal fun DeviceAssistantAccess.toSelection() =
    AssistantAccessSelection(capabilities, contentScope.journalIds, contentScope.includeUnfiled)

internal fun AssistantAccessSelection.toDeviceAccess() =
    DeviceAssistantAccess(capabilities, AssistantContentScope(journalIds, includeUnfiled = includeUnfiled))
