package app.logdate.client.sync.space.migration

import app.logdate.client.media.MediaFileSource
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.JOURNAL_COVER_KIND
import app.logdate.client.sync.MissingMediaException
import app.logdate.client.sync.SyncMediaTransfer
import app.logdate.client.sync.SyncTokenRefresher
import app.logdate.client.sync.cloud.CloudMediaDataSource
import app.logdate.client.sync.cloud.FileMediaDataSource
import app.logdate.client.sync.metadata.MediaSyncRef
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.space.SpaceMediaReference
import app.logdate.client.sync.space.mappers.SpaceDraft
import app.logdate.client.sync.space.mappers.SpaceJournal
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableVideoBlock
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A media file the move needs is neither on this device nor readable on the server. */
internal class MigrationMediaUnavailableException(
    reference: String,
    cause: Throwable? = null,
    val count: Int = 1,
) : Exception("The media file $reference isn't on this device or the server", cause)

/**
 * Gives a moved item's media files personal-space references, through the sync engine's own media
 * transfer so every kind of reference is read the way sync reads it: a note's file, a draft block's
 * files and a journal's cover. Nothing changes on the device; the new references are kept by
 * [MigrationMediaRefs] until the switch.
 *
 * A file that isn't in this device's media store, because it was never downloaded or lives in the
 * photo library, is moved from its legacy copy: copied by the server when an app encrypted it,
 * otherwise downloaded, encrypted here and uploaded.
 */
internal class MigrationMediaPreparer(
    private val tokens: SyncTokenRefresher,
    private val scope: UploadScope,
    private val refs: MigrationMediaRefs,
    private val transfer: SyncMediaTransfer,
    private val source: MigrationMediaSource,
    private val legacyMedia: CloudMediaDataSource,
    private val legacyRefs: MediaSyncRefStore,
) {
    suspend fun note(note: JournalNote): JournalNote {
        val reference = note.mediaRefOrNull() ?: return note
        if (SpaceMediaReference.parse(reference) != null) return note
        val file = MovedFile(note.uid, { refs.get(note.uid) }, { legacyRefs.get(note.uid) }) { refs.upsert(it) }
        if (isOffDevice(reference)) return note.withMedia(fromLegacyCopy(file, reference))
        val prepared =
            withToken(
                "moveNoteMedia(${note.uid})",
            ) { token -> transfer.uploadIfNeeded(token, note) { SpaceMediaReference.parse(it) != null } }
        return prepared.getOrElse { error ->
            if (error !is MissingMediaException) throw error
            note.withMedia(fromLegacyCopy(file, reference, error))
        }
    }

    suspend fun draft(item: SpaceDraft): SpaceDraft {
        val prepared =
            withToken("moveDraftMedia(${item.draft.id})") { token ->
                transfer.uploadDraftMediaIfNeeded(token, item.draft) { SpaceMediaReference.parse(it) != null }
            }.getOrElse { throw unavailable(it, item.draft.id.toString()) }
        return item.copy(draft = withOffDeviceAssets(prepared))
    }

    suspend fun journal(item: SpaceJournal): SpaceJournal {
        if (item.mergedInto != null || item.journal.coverImageUri == null) return item
        val prepared =
            withToken("moveJournalCover(${item.journal.id})") { token ->
                transfer.uploadJournalCoverIfNeeded(token, item.journal) { SpaceMediaReference.parse(it) != null }
            }.getOrElse { throw unavailable(it, item.journal.id.toString()) }
        val cover = prepared.coverImageUri
        if (cover == null || SpaceMediaReference.parse(cover) != null || !isOffDevice(cover)) return item.copy(journal = prepared)
        return item.copy(
            journal = prepared.copy(coverImageUri = fromLegacyCopy(asset(prepared.id, prepared.id, JOURNAL_COVER_KIND), cover)),
        )
    }

    /** [draft] with every block file that isn't on this device moved from its legacy copy. */
    private suspend fun withOffDeviceAssets(draft: EditorDraft): EditorDraft =
        draft.copy(
            blocks =
                draft.blocks.map { block ->
                    when (block) {
                        is SerializableImageBlock -> {
                            block.copy(uri = moveIfOffDevice(draft.id, block.id, "image", block.uri))
                        }

                        is SerializableVideoBlock -> {
                            block.copy(
                                uri = moveIfOffDevice(draft.id, block.id, "video", block.uri),
                                thumbnailUri = moveIfOffDevice(draft.id, block.id, "thumbnail", block.thumbnailUri),
                            )
                        }

                        is SerializableAudioBlock -> {
                            block.copy(uri = moveIfOffDevice(draft.id, block.id, "audio", block.uri))
                        }

                        is SerializableCameraBlock -> {
                            block.copy(uri = moveIfOffDevice(draft.id, block.id, "camera", block.uri))
                        }

                        else -> {
                            block
                        }
                    }
                },
        )

    private suspend fun moveIfOffDevice(
        draftId: Uuid,
        blockId: Uuid,
        kind: String,
        uri: String?,
    ): String? {
        if (uri == null || SpaceMediaReference.parse(uri) != null || !isOffDevice(uri)) return uri
        return fromLegacyCopy(asset(draftId, blockId, kind), uri)
    }

    private fun asset(
        ownerId: Uuid,
        blockId: Uuid,
        kind: String,
    ) = MovedFile(
        blockId,
        { refs.getDraftAsset(ownerId, blockId, kind) },
        { legacyRefs.getDraftAsset(ownerId, blockId, kind) },
    ) { refs.upsertDraftAsset(ownerId, blockId, kind, it) }

    /** A file this device doesn't have, and how to read and save its references. */
    private class MovedFile(
        val contentId: Uuid,
        val moved: suspend () -> MediaSyncRef?,
        val legacy: suspend () -> MediaSyncRef?,
        val save: suspend (MediaSyncRef) -> Unit,
    )

    /** Moves [file] from its legacy copy, which its legacy reference or [reference] itself names, and returns its space reference. */
    private suspend fun fromLegacyCopy(
        file: MovedFile,
        reference: String,
        cause: Throwable? = null,
    ): String {
        file.moved()?.let { return it.remoteUrl }
        val legacy =
            file.legacy()
                ?: legacyMediaIdOfUrl(reference)?.let { MediaSyncRef(file.contentId.toString(), reference, reference, it, 0) }
                ?: throw MigrationMediaUnavailableException(reference, cause)
        val mediaId = legacyMediaIdOf(legacy) ?: throw MigrationMediaUnavailableException(reference, cause)
        val downloaded = downloadLegacy(file.contentId, legacy.remoteUrl, mediaId, reference)
        try {
            refs.rememberLegacy(file.contentId, legacy)
            val uploaded =
                withToken(
                    "moveMedia(${file.contentId})",
                ) { token -> source.uploadMedia(token, file.contentId, downloaded.source) }.getOrThrow()
            file.save(
                MediaSyncRef(
                    file.contentId.toString(),
                    reference,
                    uploaded.downloadUrl,
                    uploaded.mediaId,
                    Clock.System.now().toEpochMilliseconds(),
                ),
            )
            return uploaded.downloadUrl
        } finally {
            downloaded.path?.let { SystemFileSystem.delete(it, mustExist = false) }
        }
    }

    /** A legacy file downloaded for the move, and the temporary file it was written to, when there is one. */
    private class LegacyFile(
        val source: MediaFileSource,
        val path: Path?,
    )

    /** Downloads the legacy file [mediaId] decrypted, streamed to a file when the legacy source can, or whole otherwise. */
    private suspend fun downloadLegacy(
        contentId: Uuid,
        remoteUrl: String,
        mediaId: String,
        reference: String,
    ): LegacyFile {
        val files = legacyMedia as? FileMediaDataSource
        val streamed =
            files?.let { source ->
                withToken("downloadLegacyMedia($contentId)") { token ->
                    source.downloadMediaToFile(token, remoteUrl, mediaId) ?: Result.failure(UnsupportedOperationException())
                }.getOrNull()
            }
        if (streamed != null) {
            val media =
                MediaFileSource(
                    streamed.fileName,
                    streamed.mimeType,
                    streamed.sizeBytes,
                ) { SystemFileSystem.source(streamed.path).buffered() }
            return LegacyFile(media, streamed.path)
        }
        val whole =
            withToken("downloadLegacyMedia($contentId)") { token -> legacyMedia.downloadMedia(token, mediaId) }
                .getOrElse { throw MigrationMediaUnavailableException(reference, it) }
        val media = MediaFileSource(whole.fileName, whole.mimeType, whole.data.size.toLong()) { Buffer().apply { write(whole.data) } }
        return LegacyFile(media, null)
    }

    private suspend fun <T> withToken(
        operation: String,
        block: suspend (String) -> Result<T>,
    ): Result<T> = tokens.withFreshToken(block, operation, expectedScope = scope)

    private fun unavailable(
        error: Throwable,
        owner: String,
    ): Throwable = if (error is MissingMediaException) MigrationMediaUnavailableException(owner, error) else error
}

/** Whether [reference] names a file on a server or in the photo library rather than in this device's media store. */
internal fun isOffDevice(reference: String): Boolean =
    reference.isBlank() ||
        reference.startsWith("http://") ||
        reference.startsWith("https://") ||
        UnavailableMediaReferences.nativeSource(reference) != null

private fun JournalNote.withMedia(mediaRef: String): JournalNote =
    when (this) {
        is JournalNote.Image -> copy(mediaRef = mediaRef)
        is JournalNote.Video -> copy(mediaRef = mediaRef)
        is JournalNote.Audio -> copy(mediaRef = mediaRef)
        else -> this
    }

/** Builds a [MediaManager]-backed transfer that writes the move's references and media, never the sync engine's. */
internal fun migrationTransfer(
    mediaManager: MediaManager,
    refs: MigrationMediaRefs,
    source: MigrationMediaSource,
    mediaReferences: StoredMediaReferences,
): SyncMediaTransfer =
    SyncMediaTransfer(
        mediaManager = mediaManager,
        mediaSyncRefStore = refs,
        cloudMediaDataSource = source,
        mediaReferences = mediaReferences,
    )
