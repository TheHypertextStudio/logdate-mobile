@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.logdate.client.sync.space.migration.JournalDataKind
import app.logdate.client.sync.space.migration.JournalMoveProblem
import app.logdate.client.sync.space.migration.JournalMoveState
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.util.formatDateLocalized
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.journal_data_drafts
import logdate.client.feature.core.generated.resources.journal_data_entries
import logdate.client.feature.core.generated.resources.journal_data_journals
import logdate.client.feature.core.generated.resources.journal_data_location_history
import logdate.client.feature.core.generated.resources.journal_data_media
import logdate.client.feature.core.generated.resources.journal_data_memberships
import logdate.client.feature.core.generated.resources.journal_data_moments
import logdate.client.feature.core.generated.resources.journal_storage_checking
import logdate.client.feature.core.generated.resources.journal_storage_enter_recovery_phrase
import logdate.client.feature.core.generated.resources.journal_storage_paused
import logdate.client.feature.core.generated.resources.journal_storage_paused_title
import logdate.client.feature.core.generated.resources.journal_storage_previous
import logdate.client.feature.core.generated.resources.journal_storage_previous_title
import logdate.client.feature.core.generated.resources.journal_storage_problem_another_device
import logdate.client.feature.core.generated.resources.journal_storage_problem_copy_differs
import logdate.client.feature.core.generated.resources.journal_storage_problem_identity_key
import logdate.client.feature.core.generated.resources.journal_storage_problem_media_unavailable
import logdate.client.feature.core.generated.resources.journal_storage_problem_missing_here
import logdate.client.feature.core.generated.resources.journal_storage_problem_not_on_server
import logdate.client.feature.core.generated.resources.journal_storage_problem_not_synced_here
import logdate.client.feature.core.generated.resources.journal_storage_problem_offline
import logdate.client.feature.core.generated.resources.journal_storage_problem_refused
import logdate.client.feature.core.generated.resources.journal_storage_problem_storage_full
import logdate.client.feature.core.generated.resources.journal_storage_problem_window_closed
import logdate.client.feature.core.generated.resources.journal_storage_sealed
import logdate.client.feature.core.generated.resources.journal_storage_sealed_title
import logdate.client.feature.core.generated.resources.journal_storage_switch_back
import logdate.client.feature.core.generated.resources.journal_storage_switch_back_dialog_message
import logdate.client.feature.core.generated.resources.journal_storage_switch_back_dialog_title
import logdate.client.feature.core.generated.resources.journal_storage_switch_back_row
import logdate.client.feature.core.generated.resources.journal_storage_switch_back_row_title
import logdate.client.feature.core.generated.resources.journal_storage_switching_back
import logdate.client.feature.core.generated.resources.journal_storage_switching_back_title
import logdate.client.feature.core.generated.resources.journal_storage_title
import logdate.client.feature.core.generated.resources.journal_storage_upgrade_title
import logdate.client.feature.core.generated.resources.journal_storage_upgrading_title
import logdate.client.feature.core.generated.resources.journal_storage_uploading
import logdate.client.feature.core.generated.resources.journal_storage_waiting
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_try_again
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import logdate.client.ui.generated.resources.Res as UiRes

object JournalStorageTags {
    const val SECTION = "journal-storage"
}

/**
 * Where the upgrade of the account's journal to sealed storage stands on this device, with what the
 * person can do about it: try a paused upgrade again, enter the recovery phrase it needs, or switch
 * back while the previous copy is kept. Each action is its own row under the status, so a long
 * explanation keeps the full width. Nothing is shown when the upgrade isn't offered.
 */
@Composable
fun JournalStorageSection(
    state: JournalMoveState,
    onTryAgain: () -> Unit,
    onSwitchBack: () -> Unit,
    onEnterRecoveryPhrase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == JournalMoveState.NotOffered) return
    var confirmingSwitchBack by remember { mutableStateOf(false) }
    if (confirmingSwitchBack) {
        SwitchBackDialog(
            onConfirm = {
                confirmingSwitchBack = false
                onSwitchBack()
            },
            onDismiss = { confirmingSwitchBack = false },
        )
    }
    SettingsSection(title = stringResource(Res.string.journal_storage_title), modifier = modifier.testTag(JournalStorageTags.SECTION)) {
        when (state) {
            JournalMoveState.NotOffered -> {
                Unit
            }

            JournalMoveState.WaitingForSync -> {
                SimpleSettingsItem(
                    title = stringResource(Res.string.journal_storage_upgrade_title),
                    description = stringResource(Res.string.journal_storage_waiting),
                    action = {},
                )
            }

            is JournalMoveState.Uploading -> {
                SimpleSettingsItem(
                    title = stringResource(Res.string.journal_storage_upgrading_title),
                    description = stringResource(Res.string.journal_storage_uploading, state.done, state.total),
                    action = {},
                )
            }

            JournalMoveState.Checking -> {
                SimpleSettingsItem(
                    title = stringResource(Res.string.journal_storage_upgrading_title),
                    description = stringResource(Res.string.journal_storage_checking),
                    action = {},
                )
            }

            is JournalMoveState.Moved -> {
                SimpleSettingsItem(
                    title = stringResource(Res.string.journal_storage_sealed_title),
                    description = stringResource(Res.string.journal_storage_sealed),
                    action = {},
                )
                state.previousCopyKeptUntil?.let { keptUntil ->
                    SimpleSettingsItem(
                        title = stringResource(Res.string.journal_storage_switch_back_row_title),
                        description = stringResource(Res.string.journal_storage_switch_back_row, keptUntil.localDate()),
                        onClick = { confirmingSwitchBack = true },
                        action = {},
                    )
                }
            }

            JournalMoveState.SwitchingBack -> {
                SimpleSettingsItem(
                    title = stringResource(Res.string.journal_storage_switching_back_title),
                    description = stringResource(Res.string.journal_storage_switching_back),
                    action = {},
                )
            }

            JournalMoveState.SwitchedBack -> {
                SimpleSettingsItem(
                    title = stringResource(Res.string.journal_storage_previous_title),
                    description = stringResource(Res.string.journal_storage_previous),
                    action = {},
                )
            }

            is JournalMoveState.Stopped -> {
                SimpleSettingsItem(
                    title = stringResource(Res.string.journal_storage_paused_title),
                    description = stringResource(Res.string.journal_storage_paused, problemText(state.problem)),
                    action = {},
                )
                if (state.problem == JournalMoveProblem.IdentityKeyMissing) {
                    SimpleSettingsItem(
                        title = stringResource(Res.string.journal_storage_enter_recovery_phrase),
                        description = null,
                        onClick = onEnterRecoveryPhrase,
                        action = {},
                    )
                } else {
                    SimpleSettingsItem(
                        title = stringResource(UiRes.string.common_try_again),
                        description = null,
                        onClick = onTryAgain,
                        action = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchBackDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.journal_storage_switch_back_dialog_title)) },
        text = { Text(stringResource(Res.string.journal_storage_switch_back_dialog_message)) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(Res.string.journal_storage_switch_back)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.common_cancel)) } },
    )
}

@Composable
private fun problemText(problem: JournalMoveProblem): String =
    when (problem) {
        is JournalMoveProblem.MissingOnThisDevice -> {
            pluralStringResource(Res.plurals.journal_storage_problem_missing_here, problem.count, problem.count, kindName(problem.kind))
        }

        is JournalMoveProblem.NotOnServer -> {
            pluralStringResource(Res.plurals.journal_storage_problem_not_on_server, problem.count, problem.count, kindName(problem.kind))
        }

        is JournalMoveProblem.CopyDiffers -> {
            pluralStringResource(Res.plurals.journal_storage_problem_copy_differs, problem.count, problem.count, kindName(problem.kind))
        }

        is JournalMoveProblem.MediaUnavailable -> {
            pluralStringResource(Res.plurals.journal_storage_problem_media_unavailable, problem.count, problem.count)
        }

        is JournalMoveProblem.NotSyncedHere -> {
            stringResource(Res.string.journal_storage_problem_not_synced_here, kindName(problem.kind))
        }

        JournalMoveProblem.AnotherDeviceMoving -> {
            stringResource(Res.string.journal_storage_problem_another_device)
        }

        JournalMoveProblem.StorageFull -> {
            stringResource(Res.string.journal_storage_problem_storage_full)
        }

        JournalMoveProblem.IdentityKeyMissing -> {
            stringResource(Res.string.journal_storage_problem_identity_key)
        }

        JournalMoveProblem.Offline -> {
            stringResource(Res.string.journal_storage_problem_offline)
        }

        is JournalMoveProblem.ServerRefused -> {
            stringResource(Res.string.journal_storage_problem_refused, problem.code)
        }

        JournalMoveProblem.SwitchBackWindowClosed -> {
            stringResource(Res.string.journal_storage_problem_window_closed)
        }
    }

@Composable
private fun kindName(kind: JournalDataKind): String = stringResource(kind.label())

private fun JournalDataKind.label(): StringResource =
    when (this) {
        JournalDataKind.ENTRIES -> Res.string.journal_data_entries
        JournalDataKind.JOURNALS -> Res.string.journal_data_journals
        JournalDataKind.MEMBERSHIPS -> Res.string.journal_data_memberships
        JournalDataKind.DRAFTS -> Res.string.journal_data_drafts
        JournalDataKind.MOMENTS -> Res.string.journal_data_moments
        JournalDataKind.LOCATION_HISTORY -> Res.string.journal_data_location_history
        JournalDataKind.MEDIA -> Res.string.journal_data_media
    }

private fun Instant.localDate(): String = formatDateLocalized(toLocalDateTime(TimeZone.currentSystemDefault()).date)
