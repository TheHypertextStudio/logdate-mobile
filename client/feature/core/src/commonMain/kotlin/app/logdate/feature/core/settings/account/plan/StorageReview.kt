package app.logdate.feature.core.settings.account.plan

import androidx.compose.runtime.Composable
import app.logdate.client.domain.storage.StoredKind
import app.logdate.feature.core.settings.ui.StorageCategory
import app.logdate.feature.core.settings.ui.StorageKindAction
import app.logdate.shared.model.CloudObjectType
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.storage_cleanup_open_entries
import logdate.client.feature.core.generated.resources.storage_cleanup_open_photos
import logdate.client.feature.core.generated.resources.storage_cleanup_open_recordings
import logdate.client.feature.core.generated.resources.storage_cleanup_open_videos
import logdate.client.feature.core.generated.resources.storage_review_entries
import logdate.client.feature.core.generated.resources.storage_review_library
import logdate.client.feature.core.generated.resources.storage_review_recordings
import org.jetbrains.compose.resources.stringResource

/** A kind of stored thing the app has somewhere to look through. */
enum class StorageReview {
    Photos,
    Videos,
    Recordings,
    WrittenEntries,
    ;

    /** The same kind, as the clean-up page knows it. */
    val storedKind: StoredKind
        get() =
            when (this) {
                Photos -> StoredKind.Photos
                Videos -> StoredKind.Videos
                Recordings -> StoredKind.Recordings
                WrittenEntries -> StoredKind.WrittenEntries
            }
}

/**
 * What can be done about a kind of stored thing, if anything. With [onCleanUp] the button opens the
 * page that deletes that kind; without it, the button opens where the app shows that kind.
 */
@Composable
internal fun reviewAction(
    category: StorageCategory,
    onReview: ((StorageReview) -> Unit)?,
    onCleanUp: ((StoredKind) -> Unit)?,
): StorageKindAction? {
    val review =
        when (category.type) {
            CloudObjectType.IMAGE_NOTES -> StorageReview.Photos
            CloudObjectType.VIDEO_NOTES -> StorageReview.Videos
            CloudObjectType.VOICE_NOTES -> StorageReview.Recordings
            CloudObjectType.TEXT_NOTES -> StorageReview.WrittenEntries
            else -> return null
        }
    if (onCleanUp != null) {
        val label =
            when (review) {
                StorageReview.Photos -> Res.string.storage_cleanup_open_photos
                StorageReview.Videos -> Res.string.storage_cleanup_open_videos
                StorageReview.Recordings -> Res.string.storage_cleanup_open_recordings
                StorageReview.WrittenEntries -> Res.string.storage_cleanup_open_entries
            }
        return StorageKindAction(label = stringResource(label), onClick = { onCleanUp(review.storedKind) })
    }
    if (onReview == null) return null
    val label =
        when (review) {
            StorageReview.Photos, StorageReview.Videos -> Res.string.storage_review_library
            StorageReview.Recordings -> Res.string.storage_review_recordings
            StorageReview.WrittenEntries -> Res.string.storage_review_entries
        }
    return StorageKindAction(label = stringResource(label), onClick = { onReview(review) })
}
