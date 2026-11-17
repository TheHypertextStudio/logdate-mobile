package app.logdate.feature.editor.ui.editor

import app.logdate.client.domain.editor.EditorData
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The state the editor screen draws: the entry being edited, joined with the journals, drafts and
 * notes loaded for it.
 *
 * [value] is worked out from the two sources each time it is read. A shared copy trails a change
 * to the entry by one dispatch, and the editor draws its first frame from its first read, so an
 * entry that was given its first block as it opened would be drawn once as an empty page.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class PresentedEditorState(
    private val entry: StateFlow<EditorState>,
    private val data: StateFlow<EditorData?>,
    private val present: (EditorState, EditorData?) -> EditorState,
) : StateFlow<EditorState> {
    override val value: EditorState
        get() = present(entry.value, data.value)

    override val replayCache: List<EditorState>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<EditorState>): Nothing {
        combine(entry, data, present).distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
