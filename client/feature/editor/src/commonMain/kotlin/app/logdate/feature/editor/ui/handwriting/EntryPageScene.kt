@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.editor.EntryBlockUiState

/**
 * The entry page as the app shows it: its top bar, what has been added so far, and the row of
 * ways to add more. It holds [blocks] in memory and saves nothing.
 *
 * Screenshot scenes and the debug build's entry-start screen draw the editor through this, so what
 * they show is the page a person gets when they start an entry and not a stand-in built from the
 * editor's parts.
 *
 * @param defaultMode the way to add that the row has ready, which is the person's setting in the app.
 * @param onExit called when the page's close control is used.
 */
@Composable
fun EntryPageScene(
    blocks: List<EntryBlockUiState> = emptyList(),
    defaultMode: EntryStartMode = EntryStartMode.TEXT,
    onExit: () -> Unit = {},
) {
    val overview = remember { EntryPageOverviewState(initiallyExpanded = false) }
    EntryStartPrototype(
        initialBlocks = blocks,
        onExit = onExit,
        focusAnyBlock = true,
        continuousOverview = true,
        overviewStudyState = overview,
        studyDefaultMode = defaultMode,
        pageTopBar = true,
        overviewContent = { EntryPageOverview(it, emptyList(), overview, defaultMode) },
    )
}
