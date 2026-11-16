package app.logdate.feature.core.people.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.knowledge.InferredPeopleRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleInboxViewModel(
    private val inferredPeopleRepository: InferredPeopleRepository,
) : ViewModel() {
    data class SuggestionUiState(
        val id: Uuid,
        val nameHint: String,
        val evidencePreview: List<String>,
    )

    /** The names waiting for review, or `null` until they have been read. */
    val suggestions: StateFlow<List<SuggestionUiState>?> =
        inferredPeopleRepository
            .observeOpenClusters()
            .mapLatest { clusters ->
                coroutineScope {
                    clusters
                        .map { cluster ->
                            async {
                                val evidence =
                                    inferredPeopleRepository
                                        .observeEvidence(cluster.uid)
                                        .first()
                                        .mapNotNull { item -> item.label }
                                        .distinct()
                                        .take(2)
                                SuggestionUiState(
                                    id = cluster.uid,
                                    nameHint = cluster.displayNameHint,
                                    evidencePreview = evidence,
                                )
                            }
                        }.awaitAll()
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    private val mutableNotice = MutableStateFlow<PeopleNotice?>(null)
    val notice: StateFlow<PeopleNotice?> = mutableNotice.asStateFlow()

    private val decisionsInFlight = mutableSetOf<Uuid>()

    init {
        runReporting(PeopleNotice.RefreshFailed, "Could not refresh inferred people") {
            inferredPeopleRepository.refresh()
        }
    }

    /** Adds the suggested name to the list of people. */
    fun confirm(clusterId: Uuid) {
        decide(clusterId, PeopleNotice.AddSuggestionFailed, "Could not add a suggested name to people") {
            inferredPeopleRepository.confirmClusterAsPerson(clusterId)
        }
    }

    /** Rejects the suggested name, so it is not suggested again. */
    fun dismiss(clusterId: Uuid) {
        decide(clusterId, PeopleNotice.DismissSuggestionFailed, "Could not dismiss a suggested name") {
            inferredPeopleRepository.rejectCluster(clusterId)
        }
    }

    fun dismissNotice() {
        mutableNotice.value = null
    }

    /** Runs one decision per suggestion at a time, so a second tap cannot decide it twice. */
    private fun decide(
        clusterId: Uuid,
        failureNotice: PeopleNotice,
        logMessage: String,
        work: suspend () -> Unit,
    ) {
        if (!decisionsInFlight.add(clusterId)) return
        runReporting(failureNotice, logMessage) {
            try {
                work()
            } finally {
                decisionsInFlight.remove(clusterId)
            }
        }
    }

    /**
     * Runs [work] and, when it fails, logs [logMessage] and shows [failureNotice]. Starting new work
     * clears the notice left by an earlier failure.
     */
    private fun runReporting(
        failureNotice: PeopleNotice,
        logMessage: String,
        work: suspend () -> Unit,
    ) {
        mutableNotice.value = null
        viewModelScope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e(logMessage, error)
                mutableNotice.value = failureNotice
            }
        }
    }
}
