package app.logdate.feature.core.people.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.entities.GetPeopleUseCase
import app.logdate.client.repository.knowledge.InferredPeopleRepository
import app.logdate.shared.model.PersonOrigin
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class PeopleDirectoryViewModel(
    getPeopleUseCase: GetPeopleUseCase,
    private val inferredPeopleRepository: InferredPeopleRepository,
) : ViewModel() {
    /** One person in the list, with how they came to be in it. */
    data class PersonRow(
        val id: Uuid,
        val name: String,
        val origin: PersonOrigin,
    )

    /** The people in the list, or `null` until the list has been read. */
    val people: StateFlow<List<PersonRow>?> =
        getPeopleUseCase()
            .map { people -> people.map { PersonRow(id = it.uid, name = it.name, origin = it.origin) } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    private val mutableNotice = MutableStateFlow<PeopleNotice?>(null)
    val notice: StateFlow<PeopleNotice?> = mutableNotice.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                inferredPeopleRepository.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not refresh inferred people", error)
                mutableNotice.value = PeopleNotice.RefreshFailed
            }
        }
    }

    fun dismissNotice() {
        mutableNotice.value = null
    }
}
