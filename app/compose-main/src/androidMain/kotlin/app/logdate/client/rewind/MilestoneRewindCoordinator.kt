package app.logdate.client.rewind

import app.logdate.client.domain.rewind.GenerateBasicRewindResult
import app.logdate.client.domain.rewind.GenerateBasicRewindUseCase
import app.logdate.client.intelligence.milestones.MilestoneCandidate
import app.logdate.client.intelligence.milestones.MilestoneDetector
import app.logdate.client.intelligence.milestones.toMetadataSignal
import app.logdate.client.repository.rewind.RewindRepository
import app.logdate.client.shortcuts.DynamicShortcutScheduler
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.firstOrNull
import kotlin.time.Instant

/**
 * Bridges the milestone detector list to the rewind generation pipeline.
 *
 * Iterates the registered detectors and, for each one that returns a candidate,
 * checks whether a rewind already covers the candidate's window. If none does, it
 * triggers normal rewind generation for that window and stamps the resulting rewind
 * with the milestone signal so the overview list knows to render it distinctly.
 *
 * Called from `RewindGenerationWorker` after the weekly check, so detection runs on
 * the same cadence as the weekly worker without spinning up a second one.
 */
class MilestoneRewindCoordinator(
    private val detectors: List<MilestoneDetector>,
    private val rewindRepository: RewindRepository,
    private val generateRewind: GenerateBasicRewindUseCase,
    private val shortcutScheduler: DynamicShortcutScheduler,
) {
    /**
     * Walks the detector list and acts on the first candidate that produces a fresh
     * rewind. Returns silently in every failure mode — milestone detection is
     * best-effort and never fails the calling worker.
     */
    suspend fun detectAndGenerate(now: Instant) {
        if (detectors.isEmpty()) return
        for (detector in detectors) {
            val candidate =
                try {
                    detector.detect(now)
                } catch (e: Exception) {
                    Napier.w("Milestone detector ${detector::class.simpleName} threw", e)
                    null
                } ?: continue

            handleCandidate(candidate)
        }
    }

    private suspend fun handleCandidate(candidate: MilestoneCandidate) {
        // Skip if a rewind already exists for the candidate window — duplicate
        // milestone rewinds would be confusing on the overview.
        val existing = rewindRepository.getRewindBetween(candidate.startTime, candidate.endTime).firstOrNull()
        if (existing != null) {
            Napier.d("MilestoneRewindCoordinator: a rewind already covers ${candidate.kind} window, skipping")
            return
        }

        Napier.i("MilestoneRewindCoordinator: generating rewind for milestone ${candidate.kind}")
        val result = generateRewind(candidate.startTime, candidate.endTime)
        when (result) {
            is GenerateBasicRewindResult.Success -> {
                runCatching {
                    rewindRepository.tagAsMilestone(result.rewind.uid, candidate.toMetadataSignal())
                }.onSuccess {
                    shortcutScheduler.enqueueImmediateRefresh()
                }.onFailure { Napier.w("MilestoneRewindCoordinator: failed to tag milestone rewind", it) }
            }

            is GenerateBasicRewindResult.AlreadyInProgress -> {
                Napier.d("MilestoneRewindCoordinator: generation already in progress for window")
            }

            is GenerateBasicRewindResult.NoContent -> {
                Napier.d("MilestoneRewindCoordinator: no content for milestone window")
            }

            is GenerateBasicRewindResult.Error -> {
                Napier.w("MilestoneRewindCoordinator: generation failed — ${result.error}")
            }
        }
    }
}
