@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.LocalEditorInputFocusBookmarks
import app.logdate.feature.editor.ui.blocks.memoryBlockLabel
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AutoSaveStatus
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.layout.LocalEntryChromeHeight
import app.logdate.ui.common.IconTooltip
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.entry_page_block_focus
import logdate.client.feature.editor.generated.resources.entry_page_draft_error
import logdate.client.feature.editor.generated.resources.entry_page_draft_saved
import logdate.client.feature.editor.generated.resources.entry_page_draft_saving
import logdate.client.feature.editor.generated.resources.entry_page_return_to_entry
import logdate.client.feature.editor.generated.resources.save_entry
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FocusedEntryHeader(
    block: EntryBlockUiState,
    recording: Boolean,
    onDone: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    saving: Boolean,
    draftStatus: AutoSaveStatus,
) {
    val focusBookmarks = LocalEditorInputFocusBookmarks.current
    val returnDescription = stringResource(Res.string.entry_page_return_to_entry)
    val saveDescription = stringResource(Res.string.save_entry)
    val focusTitle = stringResource(Res.string.entry_page_block_focus)
    val statusLabel =
        when (draftStatus) {
            AutoSaveStatus.IDLE -> null
            AutoSaveStatus.SAVING -> Res.string.entry_page_draft_saving
            AutoSaveStatus.SAVED -> Res.string.entry_page_draft_saved
            AutoSaveStatus.ERROR -> Res.string.entry_page_draft_error
        }
    val showEntrySave = block is TextBlockUiState || block is AudioBlockUiState
    Row(
        Modifier
            .fillMaxWidth()
            .height(LocalEntryChromeHeight.current)
            .padding(horizontal = 12.dp)
            .testTag("entry_mode_focus")
            .semantics { paneTitle = focusTitle },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTooltip(returnDescription) {
            FilledTonalIconButton(
                onClick = {
                    focusBookmarks?.capture(block.id)
                    onDone()
                },
                enabled = !recording && !saving,
                modifier = Modifier.size(48.dp).semantics { contentDescription = returnDescription },
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = StoryChrome),
            ) {
                Icon(Icons.Outlined.Check, null, Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            if (block !is TextBlockUiState) {
                Text(
                    memoryBlockLabel(block),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (statusLabel != null) {
                Text(
                    stringResource(statusLabel),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (draftStatus == AutoSaveStatus.ERROR) MaterialTheme.colorScheme.error else StoryMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showEntrySave) {
            IconTooltip(saveDescription) {
                FilledIconButton(
                    onClick = onSave,
                    enabled = saveEnabled && !saving,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = saveDescription },
                ) {
                    when {
                        saving -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else -> Icon(Icons.Outlined.Save, null, Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
