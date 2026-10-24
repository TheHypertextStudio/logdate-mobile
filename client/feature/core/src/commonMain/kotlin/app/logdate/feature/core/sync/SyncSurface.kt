@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.sync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.client.sync.BackupRequestState
import app.logdate.ui.common.BannerContent
import app.logdate.ui.common.MessageBanner
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.last_sync_failed
import logdate.client.feature.core.generated.resources.sync_account_media_too_large
import logdate.client.feature.core.generated.resources.sync_account_storage_unsupported
import logdate.client.feature.core.generated.resources.sync_banner_conflicts
import logdate.client.feature.core.generated.resources.sync_banner_enter_recovery_phrase
import logdate.client.feature.core.generated.resources.sync_banner_manage
import logdate.client.feature.core.generated.resources.sync_banner_needs_recovery
import logdate.client.feature.core.generated.resources.sync_banner_review
import logdate.client.feature.core.generated.resources.sync_banner_session_expired
import logdate.client.feature.core.generated.resources.sync_banner_storage_full
import logdate.client.feature.core.generated.resources.sync_banner_storage_full_items
import logdate.client.feature.core.generated.resources.sync_feedback_sign_in_action
import logdate.client.feature.core.generated.resources.sync_status_not_backed_up
import logdate.client.feature.core.generated.resources.sync_status_open
import logdate.client.feature.core.generated.resources.sync_status_progress_percent
import logdate.client.feature.core.generated.resources.sync_status_queued
import logdate.client.feature.core.generated.resources.sync_status_recovering
import logdate.client.feature.core.generated.resources.sync_status_retry_scheduled
import logdate.client.feature.core.generated.resources.sync_status_unavailable
import logdate.client.feature.core.generated.resources.sync_status_waiting
import logdate.client.feature.core.generated.resources.syncing
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val SYNC_STATUS_BUTTON_TAG = "logdate_home_sync_status"
private const val MAX_BADGE_COUNT = 99

/**
 * Backup status in the trailing slot of the timeline's top app bar. Quiet by default: composes
 * nothing for [SyncPresentation.Hidden].
 *
 * It is a fixed-size icon button rather than a text pill so it can never push the title or the
 * other actions off a phone-width bar. The state lives in the glyph:
 *
 * - `Syncing` → sync glyph, ringed with the run's progress once its size is known, spinning
 *   until then
 * - `Pending` → cloud glyph with a count badge
 * - `NetworkError` → "sync problem" glyph with a count badge. Covers real connectivity loss, a
 *   server error, and any unclassified exception alike, so it stays connectivity-neutral.
 *
 * Tapping it opens backup status, which says what is waiting and why. The button never speaks
 * for `AuthError` / `StorageError` / `ConflictError` — those promote to a banner instead.
 */
@Composable
fun SyncStatusButton(
    presentation: SyncPresentation,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val description: String
    val glyph: @Composable () -> Unit
    var badgeCount = 0
    var badgeColor = scheme.secondary
    when (presentation) {
        SyncPresentation.Hidden,
        SyncPresentation.AuthError,
        SyncPresentation.NeedsRecovery,
        is SyncPresentation.StorageError,
        SyncPresentation.MediaTooLarge,
        SyncPresentation.AccountStorageUnsupported,
        is SyncPresentation.ConflictError,
        -> {
            return
        }

        is SyncPresentation.Syncing -> {
            val percent = presentation.progressPercent
            description =
                if (percent != null) {
                    stringResource(Res.string.sync_status_progress_percent, percent)
                } else {
                    stringResource(Res.string.syncing)
                }
            glyph = { SyncingGlyph(percent) }
        }

        is SyncPresentation.Pending -> {
            description =
                when (presentation.requestState) {
                    BackupRequestState.QUEUED -> stringResource(Res.string.sync_status_queued)
                    BackupRequestState.RETRYING -> stringResource(Res.string.sync_status_retry_scheduled)
                    else -> pluralStringResource(Res.plurals.sync_status_waiting, presentation.pendingCount, presentation.pendingCount)
                }
            glyph = { Icon(Icons.Filled.CloudUpload, contentDescription = null, tint = scheme.onSurfaceVariant) }
            badgeCount = presentation.pendingCount
        }

        is SyncPresentation.Recovering -> {
            description = pluralStringResource(Res.plurals.sync_status_recovering, presentation.pendingCount, presentation.pendingCount)
            glyph = { Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = scheme.primary) }
            badgeCount = presentation.pendingCount
        }

        SyncPresentation.StatusUnavailable -> {
            description = stringResource(Res.string.sync_status_unavailable)
            glyph = { Icon(Icons.Filled.SyncProblem, contentDescription = null, tint = scheme.tertiary) }
        }

        is SyncPresentation.NetworkError -> {
            val count = presentation.pendingCount
            description =
                if (count > 0) {
                    pluralStringResource(Res.plurals.sync_status_not_backed_up, count, count)
                } else {
                    stringResource(Res.string.last_sync_failed)
                }
            glyph = { Icon(Icons.Filled.SyncProblem, contentDescription = null, tint = scheme.tertiary) }
            badgeCount = count
            badgeColor = scheme.tertiary
        }
    }
    val openLabel = stringResource(Res.string.sync_status_open)
    // The badge sits beside the button rather than inside it: IconButton clips its content to a
    // circle, which cut a "99+" badge in half. It hangs past the button's edge, so it brings its
    // own room with it.
    Box(modifier = modifier.padding(end = if (badgeCount > 0) 8.dp else 0.dp)) {
        IconButton(
            onClick = onClick,
            modifier =
                Modifier
                    .testTag(SYNC_STATUS_BUTTON_TAG)
                    .semantics {
                        contentDescription = description
                        onClick(label = openLabel) {
                            onClick()
                            true
                        }
                    },
            content = glyph,
        )
        if (badgeCount > 0) {
            Badge(
                containerColor = badgeColor,
                contentColor = contentColorFor(badgeColor),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = 6.dp)
                        .clearAndSetSemantics {},
            ) {
                Text(if (badgeCount > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else "$badgeCount")
            }
        }
    }
}

@Composable
private fun SyncingGlyph(progressPercent: Int?) {
    val tint = MaterialTheme.colorScheme.primary
    if (progressPercent == null) {
        RotatingSyncIcon(tint = tint)
        return
    }
    // The ring moving is the activity signal here, so the glyph inside it holds still.
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
        CircularProgressIndicator(
            progress = { progressPercent / 100f },
            modifier = Modifier.matchParentSize(),
            strokeWidth = 2.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Icon(Icons.Filled.Sync, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/**
 * Banner for severities that require a user choice — auth lapsed, quota hit, conflicts to
 * resolve. Sits *under* the TopAppBar inside the Scaffold body, so it inherits content insets
 * and never collides with the system status bar (the original bug).
 *
 * On medium/expanded windows, callers should constrain width to ~560dp and center; on
 * compact, full content width is correct. Both modes are honored via the parent's [modifier].
 */
@Composable
fun SyncErrorBanner(
    presentation: SyncPresentation,
    modifier: Modifier = Modifier,
    onAction: (SyncAction) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val visual = presentation.toBannerVisual()
    MessageBanner(
        content =
            visual?.let {
                BannerContent(
                    message = it.message,
                    icon = it.icon,
                    containerColor = it.containerColor,
                    contentColor = it.contentColor,
                    action = it.action?.let { (label, syncAction) -> label to { onAction(syncAction) } },
                    dismissible = it.dismissible,
                )
            },
        modifier = modifier,
        onDismiss = onDismiss,
    )
}

private data class BannerVisual(
    val message: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val action: Pair<String, SyncAction>?,
    val dismissible: Boolean,
)

@Composable
private fun SyncPresentation.toBannerVisual(): BannerVisual? {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        SyncPresentation.Hidden,
        SyncPresentation.StatusUnavailable,
        is SyncPresentation.Syncing,
        is SyncPresentation.Pending,
        is SyncPresentation.Recovering,
        -> {
            null
        }

        // Entries that cannot sync used to render a chip and nothing else, and the only route to
        // the screen listing them hung off the conflict banner -- so the one state a user needs to
        // act on was the one with no way to act. Once anything is actually stuck, offer the door.
        is SyncPresentation.NetworkError -> {
            if (pendingCount > 0) {
                BannerVisual(
                    message = pluralStringResource(Res.plurals.sync_status_not_backed_up, pendingCount, pendingCount),
                    icon = Icons.Filled.SyncProblem,
                    containerColor = scheme.tertiaryContainer,
                    contentColor = scheme.onTertiaryContainer,
                    action = stringResource(Res.string.sync_banner_review) to SyncAction.ReviewIssues,
                    dismissible = true,
                )
            } else {
                null
            }
        }

        SyncPresentation.NeedsRecovery -> {
            BannerVisual(
                message = stringResource(Res.string.sync_banner_needs_recovery),
                icon = Icons.Filled.Lock,
                containerColor = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
                action = stringResource(Res.string.sync_banner_enter_recovery_phrase) to SyncAction.EnterRecoveryPhrase,
                dismissible = false,
            )
        }

        SyncPresentation.AccountStorageUnsupported -> {
            BannerVisual(
                message = stringResource(Res.string.sync_account_storage_unsupported),
                icon = Icons.Filled.CloudOff,
                containerColor = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
                action = null,
                dismissible = false,
            )
        }

        SyncPresentation.MediaTooLarge -> {
            BannerVisual(
                message = stringResource(Res.string.sync_account_media_too_large),
                icon = Icons.Filled.CloudUpload,
                containerColor = scheme.tertiaryContainer,
                contentColor = scheme.onTertiaryContainer,
                action = stringResource(Res.string.sync_banner_review) to SyncAction.ReviewIssues,
                dismissible = false,
            )
        }

        SyncPresentation.AuthError -> {
            BannerVisual(
                message = stringResource(Res.string.sync_banner_session_expired),
                icon = Icons.Filled.Lock,
                // Use errorContainer *tone* but not the harshest; balanced for a calm prompt.
                containerColor = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
                action = stringResource(Res.string.sync_feedback_sign_in_action) to SyncAction.SignIn,
                dismissible = false,
            )
        }

        is SyncPresentation.StorageError -> {
            BannerVisual(
                message =
                    if (pendingCount > 0) {
                        pluralStringResource(Res.plurals.sync_banner_storage_full_items, pendingCount, pendingCount)
                    } else {
                        stringResource(Res.string.sync_banner_storage_full)
                    },
                icon = Icons.Filled.Storage,
                containerColor = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
                action = stringResource(Res.string.sync_banner_manage) to SyncAction.ManageStorage,
                dismissible = true,
            )
        }

        is SyncPresentation.ConflictError -> {
            BannerVisual(
                message = pluralStringResource(Res.plurals.sync_banner_conflicts, conflictCount, conflictCount),
                icon = Icons.AutoMirrored.Filled.MergeType,
                containerColor = scheme.tertiaryContainer,
                contentColor = scheme.onTertiaryContainer,
                action = stringResource(Res.string.sync_banner_review) to SyncAction.ReviewConflicts,
                dismissible = false,
            )
        }
    }
}

/**
 * Continuously-rotating sync glyph used in [SyncStatusButton] while a run's size is unknown.
 * Replaces an indeterminate `CircularProgressIndicator` because that renders as a tiny arc
 * fragment in static screenshot snapshots — this icon snapshots cleanly at the initial frame
 * (rotation = 0) and animates the same way on device.
 */
@Composable
private fun RotatingSyncIcon(tint: Color) {
    val transition = rememberInfiniteTransition(label = "sync-icon")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1_500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "sync-icon-rotation",
    )
    Icon(
        imageVector = Icons.Filled.Sync,
        contentDescription = null,
        tint = tint,
        modifier =
            Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = rotation },
    )
}
