@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import app.logdate.client.repository.transcription.TranscriptionStatus
import kotlin.uuid.Uuid

/**
 * State for transcription functionality that is shared across the app.
 */
data class TranscriptionState(
    val requestTranscription: (noteId: Uuid) -> Unit = { _ -> },
    val getTranscriptionText: (noteId: Uuid) -> String? = { _ -> null },
    val isTranscriptionInProgress: (noteId: Uuid) -> Boolean = { _ -> false },
    val getTranscriptionError: (noteId: Uuid) -> String? = { _ -> null },
    val getTranscriptionStatus: (noteId: Uuid) -> TranscriptionStatus? = { _ -> null },
)

/**
 * CompositionLocal for accessing the transcription state from anywhere in the app.
 */
val LocalTranscriptionState =
    compositionLocalOf {
        TranscriptionState()
    }

/**
 * Provider composable that makes transcription functionality available to all descendants.
 */
@Composable
fun TranscriptionProvider(
    state: TranscriptionState,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTranscriptionState provides state) {
        content()
    }
}
