@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import app.logdate.ui.common.Dialog
import app.logdate.ui.common.MaterialCard
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.PlatformSheet
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.data_transfer_counts_summary
import logdate.client.feature.core.generated.resources.export_category_drafts
import logdate.client.feature.core.generated.resources.export_category_journals
import logdate.client.feature.core.generated.resources.export_category_media
import logdate.client.feature.core.generated.resources.export_category_notes
import logdate.client.feature.core.generated.resources.restore_archive_exported_date
import logdate.client.feature.core.generated.resources.restore_archive_metadata_pair
import logdate.client.feature.core.generated.resources.restore_archive_version
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_dismiss
import logdate.client.ui.generated.resources.common_retry
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataTransferModal(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // The preview stand-in for the dialog is ordinary content, so it must draw above the page
    // that composes after it; the real dialog and sheet live in their own windows.
    val previewOverlay = if (LocalInspectionMode.current) Modifier.zIndex(1f) else Modifier
    BoxWithConstraints(Modifier.fillMaxSize().then(previewOverlay)) {
        if (maxWidth >= 840.dp && maxHeight >= 600.dp) {
            if (LocalInspectionMode.current) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                    contentAlignment = Alignment.Center,
                ) {
                    DataTransferDialogSurface(content)
                }
            } else {
                Dialog(
                    onDismissRequest = onDismiss,
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    DataTransferDialogSurface(content)
                }
            }
        } else {
            PlatformSheet(
                onDismissRequest = onDismiss,
                modifier = Modifier.widthIn(max = BottomSheetDefaults.SheetMaxWidth),
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun DataTransferDialogSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column {
            Spacer(Modifier.height(Spacing.lg))
            content()
        }
    }
}

@Composable
internal fun DataTransferSheetScrollContent(
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxContentHeight = if (maxWidth >= 600.dp) 600.dp else 720.dp
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg)
                        .then(if (footer == null) Modifier.navigationBarsPadding() else Modifier),
                content = content,
            )
            if (footer != null) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .padding(top = Spacing.md, bottom = Spacing.md)
                            .navigationBarsPadding(),
                ) {
                    footer()
                }
            }
        }
    }
}

@Composable
internal fun DataTransferIntroduction(
    title: String,
    description: String,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DataTransferProgressCard(
    title: String,
    progressPercent: Int,
    message: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "$progressPercent%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            LinearProgressIndicator(
                progress = { progressPercent / 100f },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
            )

            AnimatedVisibility(
                visible = message.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(UiRes.string.common_cancel))
                }
            }
        }
    }
}

@Composable
internal fun DataTransferResultHeader(
    title: String,
    description: String,
    icon: Painter,
    iconTint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = iconTint)
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DataTransferArchiveSummary(
    fileName: String,
    fileDetails: String?,
    countsSummary: String?,
    modifier: Modifier = Modifier,
    contained: Boolean = false,
) {
    if (contained) {
        MaterialCard(
            modifier = modifier,
            contentPadding = Spacing.md,
        ) {
            ArchiveSummaryText(fileName, fileDetails, countsSummary)
        }
    } else {
        ArchiveSummaryText(fileName, fileDetails, countsSummary, modifier)
    }
}

@Composable
private fun ArchiveSummaryText(
    fileName: String,
    fileDetails: String?,
    countsSummary: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.titleSmall,
        )
        if (fileDetails != null) {
            Text(
                text = fileDetails,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (countsSummary != null) {
            Text(
                text = countsSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun archiveDetails(
    exportDate: String?,
    appVersion: String?,
): String? {
    val localizedDate =
        exportDate?.takeIf(String::isNotBlank)?.let {
            stringResource(Res.string.restore_archive_exported_date, it)
        }
    val localizedVersion =
        appVersion?.takeIf(String::isNotBlank)?.let {
            stringResource(Res.string.restore_archive_version, it)
        }
    return when {
        localizedDate != null && localizedVersion != null -> {
            stringResource(Res.string.restore_archive_metadata_pair, localizedDate, localizedVersion)
        }

        else -> {
            localizedDate ?: localizedVersion
        }
    }
}

@Composable
internal fun dataTransferCountsSummary(
    journalCount: Int,
    noteCount: Int,
    draftCount: Int,
    mediaCount: Int,
): String =
    stringResource(
        Res.string.data_transfer_counts_summary,
        stringResource(Res.string.export_category_journals),
        journalCount,
        stringResource(Res.string.export_category_notes),
        noteCount,
        stringResource(Res.string.export_category_drafts),
        draftCount,
        stringResource(Res.string.export_category_media),
        mediaCount,
    )

@Composable
internal fun OperationFailureCard(
    title: String,
    reason: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String = stringResource(UiRes.string.common_retry),
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    painter = PlatformIcons.error(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(UiRes.string.common_dismiss))
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier.padding(start = Spacing.sm),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
