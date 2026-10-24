@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.handoff

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlin.uuid.Uuid

/**
 * Opens the editor on a draft handed off from another device once [receiver] finds it, and shows
 * [HandoffDraftArrivalContent] until then.
 *
 * [editor] receives the draft to open, or null when the person chose to start a new entry instead
 * of waiting. Try again starts the wait over.
 */
@Composable
fun HandoffDraftGate(
    draftId: Uuid,
    receiver: HandoffDraftReceiver,
    editor: @Composable (draftId: Uuid?) -> Unit,
) {
    var startedNewEntry by rememberSaveable { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    if (startedNewEntry) {
        editor(null)
        return
    }
    val arrival by remember(draftId, attempt) { receiver.arrival(draftId) }.collectAsState(initial = null)
    when (val current = arrival) {
        // The first answer comes from this device's own drafts, so there is nothing to show yet.
        null -> {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
        }

        is HandoffDraftArrival.Ready -> {
            editor(current.draftId)
        }

        else -> {
            HandoffDraftArrivalContent(
                arrival = current,
                onRetry = { attempt++ },
                onStartNewEntry = { startedNewEntry = true },
            )
        }
    }
}
