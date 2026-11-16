package app.logdate.feature.core.people.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.knowledge.InferredPeopleRepository
import app.logdate.client.util.runCatchingUnlessCancelled
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
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

    val suggestions =
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
                initialValue = emptyList(),
            )

    init {
        viewModelScope.launch {
            runCatchingUnlessCancelled { inferredPeopleRepository.refresh() }
                .onFailure { failure -> Napier.w("Could not refresh people suggestions", failure) }
        }
    }

    fun confirm(clusterId: Uuid) {
        viewModelScope.launch {
            runCatchingUnlessCancelled { inferredPeopleRepository.confirmClusterAsPerson(clusterId) }
                .onFailure { failure -> Napier.e("Could not confirm a suggested person", failure) }
        }
    }

    fun dismiss(clusterId: Uuid) {
        viewModelScope.launch {
            runCatchingUnlessCancelled { inferredPeopleRepository.rejectCluster(clusterId) }
                .onFailure { failure -> Napier.e("Could not dismiss a suggested person", failure) }
        }
    }
}
