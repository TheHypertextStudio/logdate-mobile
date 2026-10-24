package app.logdate.client.domain.rewind

import app.logdate.client.repository.rewind.RewindGenerationManager
import app.logdate.client.repository.rewind.RewindRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A use case that retrieves or constructs a Rewind for a given time period or by ID.
 *
 * This use case implements temporal logic to automatically generate rewinds on-demand
 * for current and past time periods, while preventing anticipation of future rewinds.
 */
class GetRewindUseCase(
    private val rewindRepository: RewindRepository,
    private val generationManager: RewindGenerationManager,
    private val generateBasicRewindUseCase: GenerateBasicRewindUseCase,
) {
    /**
     * Retrieves a Rewind for a given time period with temporal logic and instant UX.
     *
     * This method implements the following behavior:
     * - Future dates: Return NotReady (prevents anticipation)
     * - Current/Past dates with existing rewind: Return Success immediately
     * - Current/Past dates without rewind: Return Generating immediately and trigger background generation
     *
     * This provides an "instant" UX where users can immediately navigate to rewind detail
     * views with loading states while generation happens in the background.
     *
     * @param params Information used to retrieve the Rewind.
     */
    operator fun invoke(params: RewindParams): Flow<RewindQueryResult> {
        val now = Clock.System.now()

        // Check if this is a future rewind (prevent anticipation)
        if (params.end > now) {
            Napier.d("Rewind requested for future period, returning NotReady")
            return flow { emit(RewindQueryResult.NotReady) }
        }

        return channelFlow {
            // Track whether we've already attempted generation to prevent infinite retries
            var generationAttempted = false

            // Always collect from the repository so we pick up newly saved rewinds
            rewindRepository.getRewindBetween(params.start, params.end).collect { rewind ->
                if (rewind != null) {
                    Napier.d("Found existing rewind for period")
                    send(RewindQueryResult.Success(rewind))
                    generationAttempted = false // Reset in case preferences change and we re-collect
                } else if (generationAttempted) {
                    // We already tried generating and no rewind appeared — nothing more to do
                    Napier.d("Generation already attempted, no rewind produced")
                    send(RewindQueryResult.NoneAvailable)
                } else if (generationManager.isGenerationInProgress(params.start, params.end)) {
                    // Another caller is generating — wait for the repo to re-emit
                    Napier.d("Generation already in progress for period, waiting")
                    send(RewindQueryResult.Generating)
                } else {
                    // No rewind and no generation in progress — trigger generation once
                    Napier.d("No rewind exists for period, triggering generation")
                    generationAttempted = true
                    send(RewindQueryResult.Generating)

                    launch {
                        try {
                            when (val result = generateBasicRewindUseCase(params.start, params.end)) {
                                is GenerateBasicRewindResult.Success -> {
                                    Napier.i("Successfully generated rewind — UI will auto-update via repo flow")
                                }

                                is GenerateBasicRewindResult.AlreadyInProgress -> {
                                    Napier.d("Generation already in progress")
                                }

                                is GenerateBasicRewindResult.NoContent -> {
                                    Napier.w("No content available for rewind period")
                                    // Emit NoneAvailable so UI stops showing the loading state
                                    send(RewindQueryResult.NoneAvailable)
                                }

                                is GenerateBasicRewindResult.Error -> {
                                    Napier.e("Failed to generate rewind: ${result.error}", result.exception)
                                    send(RewindQueryResult.Failed)
                                }
                            }
                        } catch (e: Exception) {
                            Napier.e("Error during background rewind generation", e)
                            send(RewindQueryResult.Failed)
                        }
                    }
                }
            }
        }
    }

    /**
     * Retrieves a Rewind by its unique identifier.
     *
     * @param rewindId The unique identifier of the rewind to retrieve.
     */
    operator fun invoke(rewindId: kotlin.uuid.Uuid): Flow<app.logdate.shared.model.Rewind> = rewindRepository.getRewind(rewindId)
}

/**
 * Request parameters for fetching a [Rewind].
 */
data class RewindParams(
    /**
     * The start of the time period for the Rewind.
     */
    val start: Instant,
    /**
     * The end of the time period for the Rewind.
     */
    val end: Instant,
)
