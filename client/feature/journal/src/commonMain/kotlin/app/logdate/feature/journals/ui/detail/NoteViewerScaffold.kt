@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.feature.journals.ui.deriveCoverColor
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.WorkspacePanel
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.add_to_journal
import logdate.client.feature.journal.generated.resources.cd_edit_entry
import logdate.client.feature.journal.generated.resources.cd_next_entry
import logdate.client.feature.journal.generated.resources.cd_previous_entry
import logdate.client.feature.journal.generated.resources.location
import logdate.client.feature.journal.generated.resources.open_in_locations
import logdate.client.feature.journal.generated.resources.pinned_location
import logdate.client.feature.journal.generated.resources.share
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue
import kotlin.math.roundToLong
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Shared immersive layout for non-audio note presentations.
 * Provides the toolbar with journal context and routes foldable postures through hinge-safe panes.
 */
@Composable
fun NoteViewerScaffoldContent(
    shared: NoteViewerShared,
    onGoBack: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onOpenLocationTimeline: () -> Unit = {},
    onNavigateToNote: (Uuid) -> Unit = {},
    onShowAddToJournal: () -> Unit = {},
    onShare: () -> Unit = {},
    modifier: Modifier = Modifier,
    noteContent: @Composable () -> Unit,
) {
    val journalContext = shared.journalContext
    val accentColor =
        journalContext?.let {
            remember(it.journalId) { deriveCoverColor(it.journalId) }
        }

    WorkspacePanel(modifier) {
        Column(Modifier.fillMaxSize()) {
            NoteViewerToolbar(onGoBack, journalContext, accentColor, onNavigateToNote, onShowAddToJournal, onShare, onEdit)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NoteViewerContent(shared, onOpenLocationTimeline, noteContent = noteContent)
            }
        }
    }
}

/**
 * Navigation toolbar with journal context and prev/next navigation.
 */
@Composable
private fun NoteViewerToolbar(
    onGoBack: () -> Unit,
    journalContext: JournalContext? = null,
    accentColor: Color? = null,
    onNavigateToNote: (Uuid) -> Unit = {},
    onShowAddToJournal: () -> Unit = {},
    onShare: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onGoBack,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(UiRes.string.common_back),
            )
        }

        if (journalContext != null) {
            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = journalContext.journalTitle,
                style = MaterialTheme.typography.labelMedium,
                color = accentColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(
                    painter = PlatformIcons.edit(),
                    contentDescription = stringResource(Res.string.cd_edit_entry),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        IconButton(onClick = onShowAddToJournal) {
            Icon(
                Icons.Default.LibraryAdd,
                contentDescription = stringResource(Res.string.add_to_journal),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        IconButton(onClick = onShare) {
            Icon(
                Icons.Default.Share,
                contentDescription = stringResource(Res.string.share),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (journalContext != null) {
            IconButton(
                onClick = { journalContext.previousNoteId?.let(onNavigateToNote) },
                enabled = journalContext.hasPrevious,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(Res.string.cd_previous_entry),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { journalContext.nextNoteId?.let(onNavigateToNote) },
                enabled = journalContext.hasNext,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(Res.string.cd_next_entry),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Note body wrapper — handles location card and content placement.
 * No Card/Surface wrapper; each type-specific viewer handles its own surface treatment.
 */
@Composable
fun NoteViewerContent(
    shared: NoteViewerShared,
    onOpenLocationTimeline: () -> Unit = {},
    noteContent: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        shared.location?.let { location ->
            NoteLocationCard(
                location = location,
                onOpenLocationTimeline = onOpenLocationTimeline,
            )
        }

        noteContent()
    }
}

@Composable
private fun NoteLocationCard(
    location: NoteLocation,
    onOpenLocationTimeline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordinates = location.coordinates
    val placeCenter = location.place?.let { place -> place.latitude?.let { latitude -> place.longitude?.let { latitude to it } } }
    val title = location.displayName ?: stringResource(Res.string.pinned_location)
    val subtitle =
        when {
            coordinates != null -> {
                "${coordinates.latitude.formatCoordinate()}, ${coordinates.longitude.formatCoordinate()}"
            }

            placeCenter != null -> {
                "${placeCenter.first.formatCoordinate()}, ${placeCenter.second.formatCoordinate()}"
            }

            else -> {
                ""
            }
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onOpenLocationTimeline,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = stringResource(Res.string.location),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FilledTonalButton(onClick = onOpenLocationTimeline) {
                Text(text = stringResource(Res.string.open_in_locations))
            }
        }
    }
}

private fun Double.formatCoordinate(): String {
    val roundedScaled = (this * 10_000).roundToLong()
    val absoluteScaled = roundedScaled.absoluteValue
    val wholePart = absoluteScaled / 10_000
    val fractionPart = (absoluteScaled % 10_000).toString().padStart(4, '0')
    val sign = if (roundedScaled < 0) "-" else ""

    return "$sign$wholePart.$fractionPart"
}
