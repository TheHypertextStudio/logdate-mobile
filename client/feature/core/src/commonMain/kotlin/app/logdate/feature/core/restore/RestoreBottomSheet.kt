@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.restore

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.domain.restore.ArchivePreview
import app.logdate.feature.core.common.DataTransferArchiveSummary
import app.logdate.feature.core.common.DataTransferIntroduction
import app.logdate.feature.core.common.DataTransferModal
import app.logdate.feature.core.common.DataTransferSheetScrollContent
import app.logdate.feature.core.common.archiveDetails
import app.logdate.feature.core.common.dataTransferCountsSummary
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.ToggleSettingsItem
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateShort
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.export_category_drafts
import logdate.client.feature.core.generated.resources.export_category_item_count
import logdate.client.feature.core.generated.resources.export_category_media
import logdate.client.feature.core.generated.resources.import_backup
import logdate.client.feature.core.generated.resources.restore_choose_file
import logdate.client.feature.core.generated.resources.restore_confirm_description
import logdate.client.feature.core.generated.resources.restore_confirm_import
import logdate.client.feature.core.generated.resources.restore_error_file_not_accessible
import logdate.client.feature.core.generated.resources.restore_error_file_picker_failed
import logdate.client.feature.core.generated.resources.restore_error_file_picker_unavailable
import logdate.client.feature.core.generated.resources.restore_error_generic
import logdate.client.feature.core.generated.resources.restore_error_invalid_archive
import logdate.client.feature.core.generated.resources.restore_error_invalid_summary
import logdate.client.feature.core.generated.resources.restore_error_ios_requires_folder
import logdate.client.feature.core.generated.resources.restore_error_missing_source
import logdate.client.feature.core.generated.resources.restore_error_no_summary
import logdate.client.feature.core.generated.resources.restore_error_unsupported_version
import logdate.client.feature.core.generated.resources.restore_import_options_label
import logdate.client.feature.core.generated.resources.restore_merge_info
import logdate.client.feature.core.generated.resources.restore_preview_description
import logdate.client.ui.generated.resources.common_cancel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
internal fun RestoreBottomSheet(
    restoreState: RestoreState,
    onSelectFile: () -> Unit,
    onUpdateOptions: (ImportOptions) -> Unit,
    onConfirmImport: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    DataTransferModal(onDismiss = onDismiss) {
        DataTransferSheetScrollContent(
            footer =
                if (restoreState is RestoreState.Previewing) {
                    { RestorePreviewActions(onConfirmImport, onDismiss) }
                } else {
                    null
                },
        ) {
            AnimatedContent(
                targetState = restoreState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                contentKey = { it::class },
                label = "restore-sheet-state",
            ) { state ->
                when (state) {
                    is RestoreState.Confirming -> {
                        RestoreConfirmContent(
                            onSelectFile = onSelectFile,
                            onDismiss = onDismiss,
                        )
                    }

                    is RestoreState.Selecting -> {
                        RestoreConfirmContent(
                            onSelectFile = onSelectFile,
                            onDismiss = onDismiss,
                            buttonsEnabled = false,
                        )
                    }

                    is RestoreState.Previewing -> {
                        RestorePreviewContent(
                            preview = state.preview,
                            fileName = state.fileName,
                            options = state.options,
                            onOptionsChanged = onUpdateOptions,
                            onConfirmImport = onConfirmImport,
                            onDismiss = onDismiss,
                            showActions = false,
                        )
                    }

                    is RestoreState.Restoring -> {
                        RestoreProgressCard(
                            progressPercent = state.progressPercent,
                            message = resolveStageText(state.stage),
                            onCancel = onCancel,
                        )
                    }

                    is RestoreState.Completed -> {
                        RestoreSuccessCard(
                            summary = state.summary,
                            onDismiss = onDismiss,
                            onRecoverMedia = onRetry,
                        )
                    }

                    is RestoreState.Failed -> {
                        RestoreFailureCard(
                            reason = resolveErrorText(state.error),
                            onRetry = onRetry,
                            onDismiss = onDismiss,
                            actionLabel =
                                if (state.error == RestoreError.FILE_NOT_ACCESSIBLE ||
                                    state.error == RestoreError.INVALID_ARCHIVE ||
                                    state.error == RestoreError.MISSING_SOURCE ||
                                    state.error == RestoreError.UNSUPPORTED_VERSION
                                ) {
                                    stringResource(Res.string.restore_choose_file)
                                } else {
                                    null
                                },
                        )
                    }

                    is RestoreState.Idle -> {
                        Spacer(modifier = Modifier.height(Spacing.lg))
                    }
                }
            }

            if (restoreState !is RestoreState.Previewing) {
                Spacer(modifier = Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
private fun RestoreConfirmContent(
    onSelectFile: () -> Unit,
    onDismiss: () -> Unit,
    buttonsEnabled: Boolean = true,
) {
    Column {
        DataTransferIntroduction(
            title = stringResource(Res.string.import_backup),
            description = stringResource(Res.string.restore_confirm_description),
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = PlatformIcons.info(),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.restore_merge_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDismiss,
                enabled = buttonsEnabled,
            ) {
                Text(stringResource(UiRes.string.common_cancel))
            }
            Button(
                onClick = onSelectFile,
                enabled = buttonsEnabled,
                modifier = Modifier.padding(start = Spacing.sm),
            ) {
                Text(stringResource(Res.string.restore_choose_file))
            }
        }
    }
}

@Composable
internal fun RestorePreviewContent(
    preview: ArchivePreview,
    fileName: String,
    options: ImportOptions,
    onOptionsChanged: (ImportOptions) -> Unit,
    onConfirmImport: () -> Unit,
    onDismiss: () -> Unit,
    showActions: Boolean = true,
) {
    Column {
        DataTransferIntroduction(
            title = stringResource(Res.string.import_backup),
            description = stringResource(Res.string.restore_preview_description),
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        DataTransferArchiveSummary(
            fileName = fileName,
            fileDetails = archiveDetails(preview.exportDate.toReadableDateShort(), preview.appVersion),
            countsSummary =
                dataTransferCountsSummary(
                    preview.stats.journalCount,
                    preview.stats.noteCount,
                    preview.stats.draftCount,
                    preview.stats.mediaCount,
                ),
            contained = true,
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        if (preview.hasDrafts || preview.hasMedia) {
            SettingsSection(title = stringResource(Res.string.restore_import_options_label)) {
                if (preview.hasDrafts) {
                    ToggleSettingsItem(
                        title = stringResource(Res.string.export_category_drafts),
                        description =
                            pluralStringResource(
                                Res.plurals.export_category_item_count,
                                preview.stats.draftCount,
                                preview.stats.draftCount,
                            ),
                        checked = options.includeDrafts,
                        onCheckedChange = { onOptionsChanged(options.copy(includeDrafts = it)) },
                    )
                }
                if (preview.hasMedia) {
                    ToggleSettingsItem(
                        title = stringResource(Res.string.export_category_media),
                        description =
                            pluralStringResource(
                                Res.plurals.export_category_item_count,
                                preview.stats.mediaCount,
                                preview.stats.mediaCount,
                            ),
                        checked = options.includeMedia,
                        onCheckedChange = { onOptionsChanged(options.copy(includeMedia = it)) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = PlatformIcons.info(),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.restore_merge_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showActions) {
            Spacer(modifier = Modifier.height(Spacing.xl))
            RestorePreviewActions(onConfirmImport, onDismiss)
        }
    }
}

@Composable
private fun RestorePreviewActions(
    onConfirmImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss) {
            Text(stringResource(UiRes.string.common_cancel))
        }
        Button(
            onClick = onConfirmImport,
            modifier = Modifier.padding(start = Spacing.sm),
        ) {
            Text(stringResource(Res.string.restore_confirm_import))
        }
    }
}

@Composable
private fun resolveStageText(stage: RestoreStage): String = stage.labelResource?.let { stringResource(it) }.orEmpty()

@Composable
private fun resolveErrorText(error: RestoreError): String =
    when (error) {
        RestoreError.INVALID_ARCHIVE -> stringResource(Res.string.restore_error_invalid_archive)
        RestoreError.MISSING_SOURCE -> stringResource(Res.string.restore_error_missing_source)
        RestoreError.FILE_NOT_ACCESSIBLE -> stringResource(Res.string.restore_error_file_not_accessible)
        RestoreError.FILE_PICKER_UNAVAILABLE -> stringResource(Res.string.restore_error_file_picker_unavailable)
        RestoreError.FILE_PICKER_FAILED -> stringResource(Res.string.restore_error_file_picker_failed)
        RestoreError.INVALID_SUMMARY -> stringResource(Res.string.restore_error_invalid_summary)
        RestoreError.NO_SUMMARY_RETURNED -> stringResource(Res.string.restore_error_no_summary)
        RestoreError.IOS_REQUIRES_FOLDER -> stringResource(Res.string.restore_error_ios_requires_folder)
        RestoreError.UNSUPPORTED_VERSION -> stringResource(Res.string.restore_error_unsupported_version)
        RestoreError.RESTORE_FAILED -> stringResource(Res.string.restore_error_generic)
    }
