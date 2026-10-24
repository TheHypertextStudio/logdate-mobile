@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.export

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.logdate.client.domain.export.ExportCounts
import app.logdate.client.text.asString
import app.logdate.feature.core.common.DataTransferIntroduction
import app.logdate.feature.core.common.DataTransferModal
import app.logdate.feature.core.common.DataTransferSheetScrollContent
import app.logdate.ui.common.SettingsChoiceItem
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.ToggleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.export
import logdate.client.feature.core.generated.resources.export_categories_label
import logdate.client.feature.core.generated.resources.export_category_drafts
import logdate.client.feature.core.generated.resources.export_category_item_count
import logdate.client.feature.core.generated.resources.export_category_journals
import logdate.client.feature.core.generated.resources.export_category_media
import logdate.client.feature.core.generated.resources.export_category_notes
import logdate.client.feature.core.generated.resources.export_date_range_all_time
import logdate.client.feature.core.generated.resources.export_date_range_all_time_hint
import logdate.client.feature.core.generated.resources.export_date_range_filtered_hint
import logdate.client.feature.core.generated.resources.export_date_range_label
import logdate.client.feature.core.generated.resources.export_date_range_last_30_days
import logdate.client.feature.core.generated.resources.export_date_range_last_90_days
import logdate.client.feature.core.generated.resources.export_date_range_last_year
import logdate.client.feature.core.generated.resources.export_options_description
import logdate.client.feature.core.generated.resources.export_options_title
import logdate.client.ui.generated.resources.common_cancel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Modal that contains the entire export session lifecycle:
 * configuration, progress, success, and failure states.
 *
 * The modal stays open from the moment the user taps Export until they
 * dismiss the result or cancel the operation.
 */
@Composable
internal fun ExportBottomSheet(
    exportState: ExportState,
    onOptionsChanged: (ExportOptions) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    DataTransferModal(onDismiss = onDismiss) {
        DataTransferSheetScrollContent(
            footer =
                when (exportState) {
                    is ExportState.Configuring -> {
                        {
                            ExportConfigActions(
                                options = exportState.options,
                                onConfirm = onConfirm,
                                onDismiss = onDismiss,
                            )
                        }
                    }

                    is ExportState.Selecting -> {
                        {
                            ExportConfigActions(
                                options = ExportOptions(),
                                onConfirm = {},
                                onDismiss = onDismiss,
                                buttonsEnabled = false,
                            )
                        }
                    }

                    else -> {
                        null
                    }
                },
        ) {
            AnimatedContent(
                targetState = exportState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                contentKey = { it::class },
                label = "export-sheet-state",
            ) { state ->
                when (state) {
                    is ExportState.Configuring -> {
                        ExportConfigContent(
                            options = state.options,
                            counts = state.counts,
                            onOptionsChanged = onOptionsChanged,
                            onConfirm = onConfirm,
                            onDismiss = onDismiss,
                            showActions = false,
                        )
                    }

                    is ExportState.Selecting -> {
                        // SAF picker is open on top — show config with disabled button
                        ExportConfigContent(
                            options = ExportOptions(),
                            counts = null,
                            onOptionsChanged = {},
                            onConfirm = {},
                            onDismiss = onDismiss,
                            buttonsEnabled = false,
                            showActions = false,
                        )
                    }

                    is ExportState.Exporting -> {
                        ExportProgressCard(
                            progressPercent = state.progressPercent,
                            message = state.message?.asString().orEmpty(),
                            onCancel = onCancel,
                        )
                    }

                    is ExportState.Completed -> {
                        ExportSuccessCard(
                            fileName = state.fileName,
                            stats = state.stats,
                            onDismiss = onDismiss,
                        )
                    }

                    is ExportState.Failed -> {
                        ExportFailureCard(
                            reason = state.reason.asString(),
                            onRetry = onRetry,
                            onDismiss = onDismiss,
                        )
                    }

                    is ExportState.Idle -> {
                        // Sheet is being dismissed, show nothing
                        Spacer(modifier = Modifier.height(Spacing.lg))
                    }
                }
            }

            if (exportState !is ExportState.Configuring && exportState !is ExportState.Selecting) {
                Spacer(modifier = Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
internal fun ExportConfigContent(
    options: ExportOptions,
    counts: ExportCounts?,
    onOptionsChanged: (ExportOptions) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    buttonsEnabled: Boolean = true,
    showActions: Boolean = true,
) {
    Column {
        DataTransferIntroduction(
            title = stringResource(Res.string.export_options_title),
            description = stringResource(Res.string.export_options_description),
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        // Date range selector
        DateRangeSelector(
            dateRange = options.dateRange,
            onDateRangeChanged = { onOptionsChanged(options.copy(dateRange = it)) },
            enabled = buttonsEnabled,
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        SettingsSection(title = stringResource(Res.string.export_categories_label)) {
            ToggleSettingsItem(
                title = stringResource(Res.string.export_category_journals),
                description = counts?.journalCount?.let { pluralStringResource(Res.plurals.export_category_item_count, it, it) },
                checked = options.includeJournals,
                onCheckedChange = { onOptionsChanged(options.copy(includeJournals = it)) },
                enabled = buttonsEnabled,
            )
            ToggleSettingsItem(
                title = stringResource(Res.string.export_category_notes),
                description = counts?.noteCount?.let { pluralStringResource(Res.plurals.export_category_item_count, it, it) },
                checked = options.includeNotes,
                onCheckedChange = { onOptionsChanged(options.copy(includeNotes = it)) },
                enabled = buttonsEnabled,
            )
            ToggleSettingsItem(
                title = stringResource(Res.string.export_category_drafts),
                description = counts?.draftCount?.let { pluralStringResource(Res.plurals.export_category_item_count, it, it) },
                checked = options.includeDrafts,
                onCheckedChange = { onOptionsChanged(options.copy(includeDrafts = it)) },
                enabled = buttonsEnabled,
            )
            ToggleSettingsItem(
                title = stringResource(Res.string.export_category_media),
                description = counts?.mediaCount?.let { pluralStringResource(Res.plurals.export_category_item_count, it, it) },
                checked = options.includeMedia,
                onCheckedChange = { onOptionsChanged(options.copy(includeMedia = it)) },
                enabled = buttonsEnabled,
            )
        }

        if (showActions) {
            Spacer(modifier = Modifier.height(Spacing.xl))
            ExportConfigActions(options, onConfirm, onDismiss, buttonsEnabled)
        }
    }
}

@Composable
private fun ExportConfigActions(
    options: ExportOptions,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    buttonsEnabled: Boolean = true,
) {
    val hasAnySelected =
        options.includeJournals ||
            options.includeNotes ||
            options.includeDrafts ||
            options.includeMedia
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
            onClick = onConfirm,
            enabled = buttonsEnabled && hasAnySelected,
            modifier = Modifier.padding(start = Spacing.sm),
        ) {
            Text(stringResource(Res.string.export))
        }
    }
}

@Composable
private fun DateRangeSelector(
    dateRange: ExportDateRange,
    onDateRangeChanged: (ExportDateRange) -> Unit,
    enabled: Boolean = true,
) {
    val options =
        listOf(
            stringResource(Res.string.export_date_range_all_time) to ExportDateRange.AllTime,
            stringResource(Res.string.export_date_range_last_30_days) to ExportDateRange.Last30Days,
            stringResource(Res.string.export_date_range_last_90_days) to ExportDateRange.Last90Days,
            stringResource(Res.string.export_date_range_last_year) to ExportDateRange.LastYear,
        )

    BoxWithConstraints {
        val expandedChoices = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.2f
        val compactColumns = if (maxWidth < 420.dp) 2 else 4
        Column(modifier = Modifier.animateContentSize()) {
            if (expandedChoices) {
                SettingsSection(title = stringResource(Res.string.export_date_range_label)) {
                    options.forEach { (label, range) ->
                        SettingsChoiceItem(
                            title = label,
                            selected = dateRange == range,
                            onClick = { onDateRangeChanged(range) },
                            enabled = enabled,
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(Res.string.export_date_range_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                DateRangeButtonGroup(
                    options = options,
                    selectedRange = dateRange,
                    onDateRangeChanged = onDateRangeChanged,
                    columns = compactColumns,
                    enabled = enabled,
                )
            }

            val hintText =
                when (dateRange) {
                    is ExportDateRange.AllTime -> stringResource(Res.string.export_date_range_all_time_hint)
                    is ExportDateRange.Custom -> null
                    else -> stringResource(Res.string.export_date_range_filtered_hint)
                }
            if (hintText != null) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A connected button group for single-choice selection, using the
 * Material 3 Expressive connected button group pattern with [ToggleButton].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DateRangeButtonGroup(
    options: List<Pair<String, ExportDateRange>>,
    selectedRange: ExportDateRange,
    onDateRangeChanged: (ExportDateRange) -> Unit,
    columns: Int,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        options.chunked(columns).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                rowOptions.forEachIndexed { index, (label, range) ->
                    ToggleButton(
                        checked = selectedRange == range,
                        onCheckedChange = { onDateRangeChanged(range) },
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        shapes =
                            when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                rowOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}
