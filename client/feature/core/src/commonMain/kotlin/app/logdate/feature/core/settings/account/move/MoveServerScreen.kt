@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account.move

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.feature.core.settings.ui.CheckedServer
import app.logdate.feature.core.settings.ui.ServerProblem
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.step.StepBusyButton
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.move_account_body
import logdate.client.feature.core.generated.resources.move_account_create
import logdate.client.feature.core.generated.resources.move_account_sign_in
import logdate.client.feature.core.generated.resources.move_account_title
import logdate.client.feature.core.generated.resources.move_account_username
import logdate.client.feature.core.generated.resources.move_choose_address_label
import logdate.client.feature.core.generated.resources.move_choose_address_placeholder
import logdate.client.feature.core.generated.resources.move_choose_body
import logdate.client.feature.core.generated.resources.move_choose_continue
import logdate.client.feature.core.generated.resources.move_choose_logdate_cloud
import logdate.client.feature.core.generated.resources.move_choose_title
import logdate.client.feature.core.generated.resources.move_done_blocked_media
import logdate.client.feature.core.generated.resources.move_done_blocked_uploads
import logdate.client.feature.core.generated.resources.move_done_body
import logdate.client.feature.core.generated.resources.move_done_close
import logdate.client.feature.core.generated.resources.move_done_confirm_message
import logdate.client.feature.core.generated.resources.move_done_confirm_title
import logdate.client.feature.core.generated.resources.move_done_delete
import logdate.client.feature.core.generated.resources.move_done_delete_body
import logdate.client.feature.core.generated.resources.move_done_failed
import logdate.client.feature.core.generated.resources.move_done_failed_offline
import logdate.client.feature.core.generated.resources.move_done_keep
import logdate.client.feature.core.generated.resources.move_done_needs_sign_in
import logdate.client.feature.core.generated.resources.move_done_sign_in_and_delete
import logdate.client.feature.core.generated.resources.move_done_title
import logdate.client.feature.core.generated.resources.move_problem_account_server
import logdate.client.feature.core.generated.resources.move_problem_invalid_address
import logdate.client.feature.core.generated.resources.move_problem_invalid_username
import logdate.client.feature.core.generated.resources.move_problem_not_logdate
import logdate.client.feature.core.generated.resources.move_problem_offline
import logdate.client.feature.core.generated.resources.move_problem_out_of_date
import logdate.client.feature.core.generated.resources.move_problem_passkey
import logdate.client.feature.core.generated.resources.move_problem_passkeys_unavailable
import logdate.client.feature.core.generated.resources.move_problem_same_server
import logdate.client.feature.core.generated.resources.move_problem_switch_failed
import logdate.client.feature.core.generated.resources.move_problem_unreachable
import logdate.client.feature.core.generated.resources.move_problem_username_taken
import logdate.client.feature.core.generated.resources.move_review_continue
import logdate.client.feature.core.generated.resources.move_review_drafts
import logdate.client.feature.core.generated.resources.move_review_entries
import logdate.client.feature.core.generated.resources.move_review_journals
import logdate.client.feature.core.generated.resources.move_review_media
import logdate.client.feature.core.generated.resources.move_review_passkey
import logdate.client.feature.core.generated.resources.move_review_recovery_phrase
import logdate.client.feature.core.generated.resources.move_review_remote_only
import logdate.client.feature.core.generated.resources.move_review_source_copy
import logdate.client.feature.core.generated.resources.move_review_title
import logdate.client.feature.core.generated.resources.move_review_what_changes
import logdate.client.feature.core.generated.resources.move_review_what_moves
import logdate.client.feature.core.generated.resources.move_uploading_body
import logdate.client.feature.core.generated.resources.move_uploading_close
import logdate.client.feature.core.generated.resources.move_uploading_progress
import logdate.client.feature.core.generated.resources.move_uploading_starting
import logdate.client.feature.core.generated.resources.move_uploading_title
import logdate.client.feature.core.generated.resources.sign_in_methods_detail_pair
import logdate.client.ui.generated.resources.common_cancel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

/** Moves the signed-in account to another server, one step at a time. */
@Composable
fun MoveServerScreen(
    onClose: () -> Unit,
    viewModel: MoveServerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state == MoveServerUiState.Closed) onClose()
    }

    MoveServerContent(
        state = state,
        onClose = onClose,
        onAddressChange = viewModel::setAddress,
        onCheckServer = viewModel::checkServer,
        onBack = viewModel::back,
        onContinueToAccount = viewModel::continueToAccount,
        onCreateAccount = viewModel::createAccount,
        onSignInInstead = viewModel::signInInstead,
        onDeleteSource = viewModel::deleteSource,
        onSignInToSourceAndDelete = viewModel::signInToSourceAndDelete,
        onKeepSource = viewModel::keepSource,
    )
}

@Composable
fun MoveServerContent(
    state: MoveServerUiState,
    onClose: () -> Unit,
    onAddressChange: (String) -> Unit,
    onCheckServer: () -> Unit,
    onBack: () -> Unit,
    onContinueToAccount: () -> Unit,
    onCreateAccount: (String) -> Unit,
    onSignInInstead: () -> Unit,
    onDeleteSource: () -> Unit,
    onSignInToSourceAndDelete: () -> Unit,
    onKeepSource: () -> Unit,
) {
    when (state) {
        MoveServerUiState.Loading, MoveServerUiState.Closed -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        is MoveServerUiState.ChooseServer -> {
            ChooseServerStep(state, onClose, onAddressChange, onCheckServer)
        }

        is MoveServerUiState.Review -> {
            ReviewStep(state, onBack, onContinueToAccount)
        }

        is MoveServerUiState.CreateAccount -> {
            CreateAccountStep(state, onBack, onCreateAccount, onSignInInstead)
        }

        is MoveServerUiState.Uploading -> {
            UploadingStep(state, onClose)
        }

        is MoveServerUiState.Finished -> {
            FinishedStep(state, onDeleteSource, onSignInToSourceAndDelete, onKeepSource)
        }
    }
}

@Composable
private fun ChooseServerStep(
    state: MoveServerUiState.ChooseServer,
    onClose: () -> Unit,
    onAddressChange: (String) -> Unit,
    onCheckServer: () -> Unit,
) {
    val canUseCloud = state.source.origin != DefaultLogDateConfigRepository.DEFAULT_BACKEND_URL
    StepScaffold(
        title = stringResource(Res.string.move_choose_title),
        onBack = onClose,
        supportingText = stringResource(Res.string.move_choose_body, state.source.name),
        hero = { StepHeroIcon(Icons.Outlined.Dns) },
        actions = {
            StepBusyButton(
                text = stringResource(Res.string.move_choose_continue),
                onClick = onCheckServer,
                busy = state.isChecking,
                enabled = state.address.isNotBlank(),
            )
            if (canUseCloud) {
                OutlinedButton(
                    onClick = {
                        onAddressChange(DefaultLogDateConfigRepository.DEFAULT_BACKEND_URL)
                        onCheckServer()
                    },
                    enabled = !state.isChecking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.move_choose_logdate_cloud)) }
            }
        },
    ) {
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            label = { Text(stringResource(Res.string.move_choose_address_label)) },
            placeholder = { Text(stringResource(Res.string.move_choose_address_placeholder)) },
            singleLine = true,
            isError = state.problem != null,
            supportingText = state.problem?.let { { Text(chooseProblemMessage(it)) } },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
            keyboardActions = KeyboardActions(onGo = { onCheckServer() }),
            enabled = !state.isChecking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReviewStep(
    state: MoveServerUiState.Review,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val destination = state.destination.name()
    StepScaffold(
        title = stringResource(Res.string.move_review_title, destination),
        onBack = onBack.takeUnless { state.isWorking },
        hero = { StepHeroIcon(Icons.Outlined.CloudSync) },
        actions = {
            StepBusyButton(text = stringResource(Res.string.move_review_continue), onClick = onContinue, busy = state.isWorking)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            state.problem?.let {
                Text(
                    text = stringResource(Res.string.move_problem_switch_failed, destination, state.source.name),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SettingsSection(stringResource(Res.string.move_review_what_moves)) {
                Fact(
                    stringResource(
                        Res.string.sign_in_methods_detail_pair,
                        pluralStringResource(Res.plurals.move_review_entries, state.survey.entries, state.survey.entries),
                        pluralStringResource(Res.plurals.move_review_journals, state.survey.journals, state.survey.journals),
                    ),
                )
                if (state.survey.media > 0) {
                    Fact(pluralStringResource(Res.plurals.move_review_media, state.survey.media, state.survey.media))
                }
                if (state.survey.drafts > 0) {
                    Fact(pluralStringResource(Res.plurals.move_review_drafts, state.survey.drafts, state.survey.drafts))
                }
            }
            SettingsSection(stringResource(Res.string.move_review_what_changes)) {
                Fact(stringResource(Res.string.move_review_passkey, destination))
                Fact(stringResource(Res.string.move_review_recovery_phrase))
                Fact(stringResource(Res.string.move_review_source_copy, state.source.name))
            }
            if (state.survey.remoteOnlyMedia > 0) {
                Text(
                    text =
                        pluralStringResource(
                            Res.plurals.move_review_remote_only,
                            state.survey.remoteOnlyMedia,
                            state.survey.remoteOnlyMedia,
                            state.source.name,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CreateAccountStep(
    state: MoveServerUiState.CreateAccount,
    onBack: () -> Unit,
    onCreate: (String) -> Unit,
    onSignInInstead: () -> Unit,
) {
    val destination = state.destination.name()
    var username by remember(state.destination.origin) { mutableStateOf(state.username) }
    StepScaffold(
        title = stringResource(Res.string.move_account_title, destination),
        onBack = onBack.takeUnless { state.isWorking },
        supportingText = stringResource(Res.string.move_account_body, destination),
        hero = { StepHeroIcon(Icons.Outlined.PersonAdd) },
        actions = {
            StepBusyButton(
                text = stringResource(Res.string.move_account_create),
                onClick = { onCreate(username) },
                busy = state.isWorking,
                enabled = username.isNotBlank(),
            )
            TextButton(onClick = onSignInInstead, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.move_account_sign_in))
            }
        },
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(Res.string.move_account_username)) },
            singleLine = true,
            isError = state.problem != null,
            supportingText = state.problem?.let { { Text(accountProblemMessage(it, destination)) } },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { if (username.isNotBlank()) onCreate(username) }),
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UploadingStep(
    state: MoveServerUiState.Uploading,
    onClose: () -> Unit,
) {
    val progress = state.progress
    val total = state.record.uploadTotal
    val done = if (progress == null) 0 else (total - progress.remaining).coerceIn(0, total)
    StepScaffold(
        title = stringResource(Res.string.move_uploading_title, state.record.to.name),
        onBack = null,
        supportingText = stringResource(Res.string.move_uploading_body),
        hero = { StepHeroIcon(Icons.Outlined.CloudSync) },
        actions = {
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.move_uploading_close))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (progress == null || total == 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(Res.string.move_uploading_starting), style = MaterialTheme.typography.bodyMedium)
            } else {
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(Res.string.move_uploading_progress, done, total), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FinishedStep(
    state: MoveServerUiState.Finished,
    onDelete: () -> Unit,
    onSignInAndDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    val source = state.record.from.name
    val destination = state.record.to.name
    val cleanup = state.cleanup
    var confirming by remember { mutableStateOf<(() -> Unit)?>(null) }
    confirming?.let { delete ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(Res.string.move_done_confirm_title, source)) },
            text = { Text(stringResource(Res.string.move_done_confirm_message, source, destination)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        delete()
                    },
                ) { Text(stringResource(Res.string.move_done_delete, source), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(UiRes.string.common_cancel)) } },
        )
    }
    StepScaffold(
        title = stringResource(Res.string.move_done_title, destination),
        onBack = null,
        supportingText = stringResource(Res.string.move_done_body, destination),
        hero = { StepHeroIcon(Icons.Outlined.CloudDone) },
        actions = {
            when (cleanup) {
                is Cleanup.Blocked -> {
                    StepBusyButton(stringResource(Res.string.move_done_close), onClick = onKeep, busy = false)
                }

                Cleanup.NeedsSignIn -> {
                    StepBusyButton(
                        stringResource(Res.string.move_done_sign_in_and_delete),
                        onClick = { confirming = onSignInAndDelete },
                        busy = false,
                    )
                    TextButton(onClick = onKeep, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.move_done_keep)) }
                }

                else -> {
                    StepBusyButton(
                        text = stringResource(Res.string.move_done_delete, source),
                        onClick = { confirming = onDelete },
                        busy = cleanup == Cleanup.Deleting,
                    )
                    TextButton(onClick = onKeep, enabled = cleanup != Cleanup.Deleting, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.move_done_keep))
                    }
                }
            }
        },
    ) {
        val (message, isError) =
            when (cleanup) {
                Cleanup.Offered, Cleanup.Deleting -> {
                    stringResource(Res.string.move_done_delete_body, destination, source) to false
                }

                Cleanup.NeedsSignIn -> {
                    stringResource(Res.string.move_done_needs_sign_in, source) to false
                }

                is Cleanup.Failed -> {
                    stringResource(if (cleanup.offline) Res.string.move_done_failed_offline else Res.string.move_done_failed, source) to
                        true
                }

                is Cleanup.Blocked -> {
                    when (cleanup.reason) {
                        CleanupBlock.MEDIA_ONLY_ON_OLD_SERVER -> {
                            pluralStringResource(
                                Res.plurals.move_done_blocked_media,
                                state.record.remoteOnlyMedia,
                                state.record.remoteOnlyMedia,
                                source,
                            )
                        }

                        CleanupBlock.UPLOADS_FAILED -> {
                            stringResource(Res.string.move_done_blocked_uploads, destination, source)
                        }
                    } to false
                }
            }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Fact(text: String) {
    SettingsRow(headlineContent = { Text(text) })
}

@Composable
private fun chooseProblemMessage(problem: ChooseProblem): String =
    stringResource(
        when (problem) {
            ChooseProblem.SameAsCurrent -> {
                Res.string.move_problem_same_server
            }

            is ChooseProblem.Server -> {
                when (problem.problem) {
                    ServerProblem.INVALID_ADDRESS -> Res.string.move_problem_invalid_address
                    ServerProblem.UNREACHABLE -> Res.string.move_problem_unreachable
                    ServerProblem.NOT_LOGDATE -> Res.string.move_problem_not_logdate
                    ServerProblem.OUT_OF_DATE -> Res.string.move_problem_out_of_date
                    ServerProblem.PASSKEYS_UNAVAILABLE_HERE -> Res.string.move_problem_passkeys_unavailable
                }
            }
        },
    )

@Composable
private fun accountProblemMessage(
    problem: AccountProblem,
    destination: String,
): String =
    when (problem) {
        AccountProblem.USERNAME_TAKEN -> stringResource(Res.string.move_problem_username_taken, destination)
        AccountProblem.INVALID_USERNAME -> stringResource(Res.string.move_problem_invalid_username)
        AccountProblem.OFFLINE -> stringResource(Res.string.move_problem_offline, destination)
        AccountProblem.PASSKEY_FAILED -> stringResource(Res.string.move_problem_passkey)
        AccountProblem.SERVER -> stringResource(Res.string.move_problem_account_server, destination)
    }

private fun CheckedServer.name(): String = MoveEndpoint(origin, descriptor).name
