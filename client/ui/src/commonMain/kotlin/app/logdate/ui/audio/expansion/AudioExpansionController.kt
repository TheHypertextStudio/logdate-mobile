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
     * The inline state to return to when dismissing an overlay.
     * Tracks whether the user was in COLLAPSED or SPATIAL_EXPANDED
     * before entering ELEVATED or IMMERSIVE.
     */
    private var inlineState: AudioExpansionState = initialState

    /**
     * Called when audio playback starts.
     * If [expandOnPlayback] is true, transitions COLLAPSED → SPATIAL_EXPANDED.
     */
    fun onPlaybackStarted() {
        if (expandOnPlayback && currentState == AudioExpansionState.COLLAPSED) {
            inlineState = AudioExpansionState.SPATIAL_EXPANDED
            currentState = AudioExpansionState.SPATIAL_EXPANDED
        }
    }

    /**
     * Called when audio playback completes (reaches end).
     * If [expandOnPlayback] is true, transitions SPATIAL_EXPANDED → COLLAPSED.
     */
    fun onPlaybackCompleted() {
        if (expandOnPlayback && currentState == AudioExpansionState.SPATIAL_EXPANDED) {
            inlineState = AudioExpansionState.COLLAPSED
            currentState = AudioExpansionState.COLLAPSED
        }
    }

    /**
     * Toggles between COLLAPSED and SPATIAL_EXPANDED.
     * No-op if currently in an overlay state.
     */
    fun onCollapseToggle() {
        when (currentState) {
            AudioExpansionState.COLLAPSED -> {
                inlineState = AudioExpansionState.SPATIAL_EXPANDED
                currentState = AudioExpansionState.SPATIAL_EXPANDED
            }

            AudioExpansionState.SPATIAL_EXPANDED -> {
                inlineState = AudioExpansionState.COLLAPSED
                currentState = AudioExpansionState.COLLAPSED
            }

            else -> {} // No-op in overlay states
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

    /**
     * Called when the long press is released.
     * Returns to the previous inline state unless the user explicitly
     * kept the elevated card open or transitioned to immersive.
     */
    fun onLongPressRelease() {
        if (currentState == AudioExpansionState.ELEVATED) {
            currentState = inlineState
        }
    }

    /**
     * Transitions to IMMERSIVE from any state.
     * Can be triggered from ELEVATED (swipe up) or any inline state
     * (explicit fullscreen action).
     */
    fun onExpandToImmersive() {
        currentState = AudioExpansionState.IMMERSIVE
    }

    /**
     * Dismisses any overlay state, returning to the previous inline state.
     */
    fun onDismissOverlay() {
        if (currentState.isOverlay) {
            currentState = inlineState
        }
    }
}
