package app.logdate.feature.editor.ui.dialog.alert

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.logdate.ui.common.AlertDialog
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.editor_action_discard
import logdate.client.feature.editor.generated.resources.editor_delete_block_dialog_description
import logdate.client.feature.editor.generated.resources.editor_delete_block_dialog_title
import logdate.client.feature.editor.generated.resources.editor_discard_dialog_description
import logdate.client.feature.editor.generated.resources.editor_discard_dialog_title
import logdate.client.feature.editor.generated.resources.save_draft
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_delete
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * A dialog that informs the user if they have unsaved changes and allows them to dismiss the screen.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ConfirmEntryExitDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onSaveAsDraft: (() -> Unit)? = null,
    dialogType: ConfirmDialogType = ConfirmDialogType.EXIT_EDITOR,
    actionsEnabled: Boolean = true,
) {
    val (title, description) =
        when (dialogType) {
            ConfirmDialogType.EXIT_EDITOR -> {
                Pair(
                    stringResource(Res.string.editor_discard_dialog_title),
                    stringResource(Res.string.editor_discard_dialog_description),
                )
            }

            ConfirmDialogType.DELETE_BLOCK -> {
                Pair(
                    stringResource(Res.string.editor_delete_block_dialog_title),
                    stringResource(Res.string.editor_delete_block_dialog_description),
                )
            }
        }

    AlertDialog(
        title = { Text(title) },
        text = { Text(description) },
        onDismissRequest = {
            if (actionsEnabled) onCancel()
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = actionsEnabled,
                modifier = Modifier.testTag("exit_dialog_cancel"),
            ) {
                Text(stringResource(UiRes.string.common_cancel))
            }
        },
        confirmButton = {
            if (onSaveAsDraft != null && dialogType == ConfirmDialogType.EXIT_EDITOR) {
                TextButton(
                    onClick = onConfirm,
                    enabled = actionsEnabled,
                    modifier = Modifier.testTag("exit_dialog_discard"),
                ) {
                    Text(stringResource(Res.string.editor_action_discard))
                }
                TextButton(
                    onClick = onSaveAsDraft,
                    enabled = actionsEnabled,
                    modifier = Modifier.testTag("exit_dialog_save_draft"),
                ) {
                    Text(stringResource(Res.string.save_draft))
                }
            } else {
                TextButton(
                    onClick = onConfirm,
                    enabled = actionsEnabled,
                    modifier = Modifier.testTag("exit_dialog_discard"),
                ) {
                    Text(
                        when (dialogType) {
                            ConfirmDialogType.EXIT_EDITOR -> stringResource(Res.string.editor_action_discard)
                            ConfirmDialogType.DELETE_BLOCK -> stringResource(UiRes.string.common_delete)
                        },
                    )
                }
            }
        },
    )
}

/**
 * Types of confirmation dialogs for the editor
 */
enum class ConfirmDialogType {
    EXIT_EDITOR,
    DELETE_BLOCK,
}
