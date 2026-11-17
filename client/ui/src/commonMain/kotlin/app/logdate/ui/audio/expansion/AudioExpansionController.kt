package app.logdate.ui.audio.expansion

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Controls the expansion state machine for an audio block.
 *
 * Manages transitions between [AudioExpansionState] values according to
 * the two-group model (inline vs overlay). See [AudioExpansionState] KDoc
 * for the full transition map.
 *
 * @param initialState Starting state, defaults to [AudioExpansionState.COLLAPSED]
 * @param expandOnPlayback When true, automatically transitions from COLLAPSED to
 *   SPATIAL_EXPANDED when playback starts, and back when playback completes.
 */
@Stable
class AudioExpansionController(
    initialState: AudioExpansionState = AudioExpansionState.COLLAPSED,
    val expandOnPlayback: Boolean = true,
) {
    /** Current expansion state. */
    var currentState by mutableStateOf(initialState)
        private set

    /**
     * Called when audio playback starts.
     * If [expandOnPlayback] is true, transitions COLLAPSED → SPATIAL_EXPANDED.
     */
    fun onPlaybackStarted() {
        if (expandOnPlayback && currentState == AudioExpansionState.COLLAPSED) {
            currentState = AudioExpansionState.SPATIAL_EXPANDED
        }
    }

    /**
     * Called when audio playback completes (reaches end).
     * If [expandOnPlayback] is true, transitions SPATIAL_EXPANDED → COLLAPSED.
     */
    fun onPlaybackCompleted() {
        if (expandOnPlayback && currentState == AudioExpansionState.SPATIAL_EXPANDED) {
            currentState = AudioExpansionState.COLLAPSED
        }
    }

    /**
     * Called on long press from any inline state.
     * Transitions to ELEVATED (hold-to-preview).
     */
    fun onLongPress() {
        if (currentState.isInline) {
            currentState = AudioExpansionState.ELEVATED
        }
    }
}
