package app.logdate.feature.core.people.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.repository.knowledge.DeviceContactsReader
import app.logdate.client.repository.knowledge.InferredPeopleRepository
import app.logdate.client.repository.knowledge.PeopleContactsAccessMode
import app.logdate.client.repository.knowledge.PeopleContactsRepository
import app.logdate.client.repository.knowledge.PeopleRepository
import app.logdate.shared.model.PersonOrigin
import app.logdate.ui.platform.PlatformKind
import app.logdate.ui.platform.currentPlatform
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State for the People page in Settings: how many people are in the list, how many suggested names
 * wait for review, and the contact import.
 *
 * The page exists only while People is turned on in Labs, so nothing here reads or writes that
 * switch.
 *
 * @param contactImportAvailable whether this platform can import contacts. The page offers the
 *   import, and this view model runs it, only when this is true.
 */
class PeopleSettingsViewModel(
    private val preferencesDataSource: LogdatePreferencesDataSource,
    peopleRepository: PeopleRepository,
    private val peopleContactsRepository: PeopleContactsRepository,
    private val deviceContactsReader: DeviceContactsReader,
    private val inferredPeopleRepository: InferredPeopleRepository,
    private val contactImportAvailable: Boolean = currentPlatform.supportsContactImport(),
) : ViewModel() {
    data class UiState(
        val contactImportAvailable: Boolean = false,
        val totalPeopleCount: Int = 0,
        val pendingReviewCount: Int = 0,
        val notice: PeopleNotice? = null,
        val isImporting: Boolean = false,
    )

    private val notice = MutableStateFlow<PeopleNotice?>(null)
    private val importing = MutableStateFlow(false)
    private var importAwaitsContactsAccess = false
    private var refreshJob: Job? = null

    val uiState: StateFlow<UiState> =
        combine(
            peopleRepository.getAllPeople().map { it.size },
            inferredPeopleRepository.observeOpenClusters().map { it.size },
            notice,
            importing,
        ) { totalCount, pendingReviewCount, currentNotice, isImporting ->
            UiState(
                contactImportAvailable = contactImportAvailable,
                totalPeopleCount = totalCount,
                pendingReviewCount = pendingReviewCount,
                notice = currentNotice,
                isImporting = isImporting,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UiState(contactImportAvailable = contactImportAvailable),
        )

    /** Looks for names in entries and transcripts again, unless that or an import is already running. */
    fun refreshPeople() {
        if (refreshJob?.isActive == true || importing.value) return
        refreshJob =
            viewModelScope.launch {
                try {
                    inferredPeopleRepository.refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Could not refresh inferred people", error)
                    notice.value = PeopleNotice.RefreshFailed
                }
            }
    }

    /** Imports every contact on the device. The caller has already confirmed contacts access. */
    fun importAllContacts() {
        if (!contactImportAvailable || importing.value) return
        importing.value = true
        importAwaitsContactsAccess = false
        notice.value = null
        viewModelScope.launch {
            try {
                val summary =
                    peopleContactsRepository.importContacts(
                        contacts = deviceContactsReader.readAllContacts(),
                        origin = PersonOrigin.CONTACT_FULL,
                    )
                preferencesDataSource.setPeopleContactsAccessMode(PeopleContactsAccessMode.FULL.name)
                refreshAfterImport()
                notice.value = summary.toNotice()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not import contacts", error)
                notice.value = PeopleNotice.ImportContactsFailed
            } finally {
                importing.value = false
            }
        }
    }

    /**
     * Records that an import was asked for before contacts access was granted, so the import starts
     * as soon as [onContactsAccessChanged] reports a grant.
     *
     * The system can refuse a repeated request without showing anything. When [deniedBefore] is
     * true, the notice that says where to allow access is shown right away instead of waiting for
     * an answer that may never be reported.
     */
    fun importWhenContactsAllowed(deniedBefore: Boolean) {
        if (!contactImportAvailable) return
        importAwaitsContactsAccess = true
        notice.value = PeopleNotice.ContactsAccessNeeded.takeIf { deniedBefore }
    }

    /**
     * Reports the system's contacts permission after it changed.
     *
     * @param granted whether contacts can be read now
     * @param answered whether the system has answered a request during this visit
     */
    fun onContactsAccessChanged(
        granted: Boolean,
        answered: Boolean,
    ) {
        if (!importAwaitsContactsAccess) return
        if (granted) {
            importAllContacts()
            return
        }
        if (!answered) return
        importAwaitsContactsAccess = false
        notice.value = PeopleNotice.ContactsAccessNeeded
    }

    fun dismissMessage() {
        notice.value = null
    }

    /**
     * Links the imported contacts to the entries that mention them. A failure here leaves the
     * import result on screen: the contacts are in the list, and the next visit looks again.
     */
    private suspend fun refreshAfterImport() {
        try {
            inferredPeopleRepository.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Could not refresh inferred people after a contact import", error)
        }
    }
}

/**
 * Contact import is offered on Android, the only platform that offers People. Desktop has no
 * contacts to read.
 */
internal fun PlatformKind.supportsContactImport(): Boolean = this == PlatformKind.Android
