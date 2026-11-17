@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.account.plan.cleanup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import app.logdate.client.domain.storage.StoredKind
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.storage_cleanup_confirm_entries
import logdate.client.feature.core.generated.resources.storage_cleanup_confirm_photos
import logdate.client.feature.core.generated.resources.storage_cleanup_confirm_recordings
import logdate.client.feature.core.generated.resources.storage_cleanup_confirm_videos
import logdate.client.feature.core.generated.resources.storage_cleanup_count_entries
import logdate.client.feature.core.generated.resources.storage_cleanup_count_photos
import logdate.client.feature.core.generated.resources.storage_cleanup_count_recordings
import logdate.client.feature.core.generated.resources.storage_cleanup_count_videos
import logdate.client.feature.core.generated.resources.storage_cleanup_empty_entries
import logdate.client.feature.core.generated.resources.storage_cleanup_empty_photos
import logdate.client.feature.core.generated.resources.storage_cleanup_empty_recordings
import logdate.client.feature.core.generated.resources.storage_cleanup_empty_videos
import logdate.client.feature.core.generated.resources.storage_cleanup_title_entries
import logdate.client.feature.core.generated.resources.storage_cleanup_title_photos
import logdate.client.feature.core.generated.resources.storage_cleanup_title_recordings
import logdate.client.feature.core.generated.resources.storage_cleanup_title_videos

/** The page title for a kind of stored thing. */
internal fun StoredKind.title() =
    when (this) {
        StoredKind.Photos -> Res.string.storage_cleanup_title_photos
        StoredKind.Videos -> Res.string.storage_cleanup_title_videos
        StoredKind.Recordings -> Res.string.storage_cleanup_title_recordings
        StoredKind.WrittenEntries -> Res.string.storage_cleanup_title_entries
    }

/** How many there are, said for this kind. */
internal fun StoredKind.countLabel() =
    when (this) {
        StoredKind.Photos -> Res.plurals.storage_cleanup_count_photos
        StoredKind.Videos -> Res.plurals.storage_cleanup_count_videos
        StoredKind.Recordings -> Res.plurals.storage_cleanup_count_recordings
        StoredKind.WrittenEntries -> Res.plurals.storage_cleanup_count_entries
    }

/** What the page says when nothing of this kind is stored. */
internal fun StoredKind.emptyLabel() =
    when (this) {
        StoredKind.Photos -> Res.string.storage_cleanup_empty_photos
        StoredKind.Videos -> Res.string.storage_cleanup_empty_videos
        StoredKind.Recordings -> Res.string.storage_cleanup_empty_recordings
        StoredKind.WrittenEntries -> Res.string.storage_cleanup_empty_entries
    }

/** The question asked before deleting some of this kind. */
internal fun StoredKind.confirmTitle() =
    when (this) {
        StoredKind.Photos -> Res.plurals.storage_cleanup_confirm_photos
        StoredKind.Videos -> Res.plurals.storage_cleanup_confirm_videos
        StoredKind.Recordings -> Res.plurals.storage_cleanup_confirm_recordings
        StoredKind.WrittenEntries -> Res.plurals.storage_cleanup_confirm_entries
    }

internal fun StoredKind.icon(): ImageVector =
    when (this) {
        StoredKind.Photos -> Icons.Rounded.Image
        StoredKind.Videos -> Icons.Rounded.Videocam
        StoredKind.Recordings -> Icons.Rounded.Mic
        StoredKind.WrittenEntries -> Icons.Rounded.Notes
    }

/** The same shape each kind has on the Storage screen. */
@Composable
internal fun StoredKind.shape(): Shape =
    when (this) {
        StoredKind.Photos -> MaterialShapes.Cookie9Sided.toShape()
        StoredKind.Videos -> MaterialShapes.Sunny.toShape()
        StoredKind.Recordings -> MaterialShapes.Clover4Leaf.toShape()
        StoredKind.WrittenEntries -> MaterialShapes.Cookie4Sided.toShape()
    }
