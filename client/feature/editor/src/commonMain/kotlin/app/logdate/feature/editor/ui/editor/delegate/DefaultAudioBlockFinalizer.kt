package app.logdate.feature.editor.ui.editor.delegate

import app.logdate.feature.editor.ui.editor.AudioCaptureState
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.editor_error_recording_not_finalized
import kotlin.uuid.Uuid

/**
 * Production [AudioBlockFinalizer] that defers to a [PendingAudioResolver] supplied
 * by the audio recording side.
 *
 * Resolves Recording/Stopping blocks via the resolver. When the resolver has no
 * record of the block — meaning the recording side never bound it, or has lost
 * track of it — these states surface as [AudioCaptureState.Failed]; otherwise
 * the save path would silently drop the block via the mapper's null return.
 * Empty and already-Ready blocks pass through unchanged.
 */
class DefaultAudioBlockFinalizer(
    private val resolver: PendingAudioResolver,
) : AudioBlockFinalizer {
    override suspend fun finalize(
        blockId: Uuid,
        currentState: AudioCaptureState,
    ): AudioCaptureState {
        if (currentState is AudioCaptureState.Ready) return currentState
        resolver.resolvePending(blockId)?.let { return it }
        return when (currentState) {
            is AudioCaptureState.Recording, is AudioCaptureState.Stopping -> {
                AudioCaptureState.Failed(Res.string.editor_error_recording_not_finalized)
            }

            else -> {
                currentState
            }
        }
    }
}
