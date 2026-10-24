package app.logdate.client.sync

import app.logdate.client.device.crypto.IdentityKeyNotFoundException
import app.logdate.client.media.ConditionalMediaSaver
import app.logdate.client.media.ManagedMediaPublicationCustody
import app.logdate.client.media.ManagedMediaPublicationCustody.None
import app.logdate.client.media.MediaFileSource
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaPayload
import app.logdate.client.media.StagedMediaFile
import app.logdate.client.media.storage.MediaReference
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.media.storage.isRecoveredOriginal
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.CloudMediaDataSource
import app.logdate.client.sync.cloud.DownloadedMediaFile
import app.logdate.client.sync.cloud.FileMediaDataSource
import app.logdate.client.sync.cloud.MediaReadException
import app.logdate.client.sync.metadata.MediaSyncRef
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableVideoBlock
import app.logdate.shared.model.diagnostics.DiagnosticReason
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Uploads and downloads a note's media file alongside its metadata, tracking the local/remote URI pair in [mediaSyncRefStore]. */
internal class SyncMediaTransfer(
    private val mediaManager: MediaManager,
    private val mediaSyncRefStore: MediaSyncRefStore,
    private val cloudMediaDataSource: CloudMediaDataSource,
    private val mediaReferences: StoredMediaReferences = StoredMediaReferences.Unchanged,
    private val mayDownload: suspend () -> Boolean = { true },
    private val publicationCustody: ManagedMediaPublicationCustody = None,
) {
    /** Whether two stored references name the same local file, however each is spelled. */
    fun sameLocalFile(
        first: String,
        second: String,
    ): Boolean {
        val a = UnavailableMediaReferences.nativeSource(first) ?: first
        val b = UnavailableMediaReferences.nativeSource(second) ?: second
        return a == b || mediaReferences.storedReference(a) == mediaReferences.storedReference(b)
    }

    fun canReuseOriginal(
        reference: String,
        source: String,
    ): Boolean =
        !UnavailableMediaReferences.isUnavailable(reference) &&
            !isRemoteRef(reference) &&
            !UnavailableMediaReferences.requiresNativeQuarantine(mediaReferences.storedReference(reference)) &&
            (
                UnavailableMediaReferences.nativeSource(source) == null ||
                    MediaReference.parse(mediaReferences.storedReference(reference)) is MediaReference.Owned
            )

    suspend fun originalAvailable(
        reference: String,
        source: String = reference,
    ): Boolean {
        if (!canReuseOriginal(reference, source)) return false
        return try {
            if (MediaReference.parse(mediaReferences.storedReference(reference)) is MediaReference.Owned) {
                mediaManager.isRecoveredOriginal(reference, mediaReferences)
            } else {
                mediaManager.exists(reference)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            false
        }
    }

    fun isRemoteRef(mediaRef: String): Boolean = mediaRef.startsWith("http://") || mediaRef.startsWith("https://")

    fun requiresUpload(reference: String): Boolean = !isRemoteRef(reference) && !UnavailableMediaReferences.isUnavailable(reference)

    /** Resolve every local attachment before the draft record can reference it remotely. */
    suspend fun uploadDraftMediaIfNeeded(
        accessToken: String,
        draft: EditorDraft,
        reusable: (String) -> Boolean = { true },
    ): Result<EditorDraft> =
        runCatchingUnlessCancelled {
            draft.copy(
                blocks =
                    draft.blocks.map { block ->
                        when (block) {
                            is SerializableImageBlock -> {
                                block.copy(
                                    uri = uploadDraftAsset(accessToken, draft.id, block.id, "image", block.uri, reusable),
                                )
                            }

                            is SerializableVideoBlock -> {
                                block.copy(
                                    uri = uploadDraftAsset(accessToken, draft.id, block.id, "video", block.uri, reusable),
                                    thumbnailUri =
                                        uploadDraftAsset(
                                            accessToken,
                                            draft.id,
                                            block.id,
                                            "thumbnail",
                                            block.thumbnailUri,
                                            reusable,
                                        ),
                                )
                            }

                            is SerializableAudioBlock -> {
                                block.copy(
                                    uri = uploadDraftAsset(accessToken, draft.id, block.id, "audio", block.uri, reusable),
                                )
                            }

                            is SerializableCameraBlock -> {
                                block.copy(
                                    uri = uploadDraftAsset(accessToken, draft.id, block.id, "camera", block.uri, reusable),
                                )
                            }

                            else -> {
                                block
                            }
                        }
                    },
            )
        }

    /**
     * Uploads a journal's cover if it isn't uploaded yet and returns the journal with the uploaded
     * file as its cover. A cover keeps its upload under the journal's id, as a draft block's file does.
     */
    suspend fun uploadJournalCoverIfNeeded(
        accessToken: String,
        journal: Journal,
        reusable: (String) -> Boolean = { true },
    ): Result<Journal> =
        runCatchingUnlessCancelled {
            journal.copy(
                coverImageUri = uploadDraftAsset(accessToken, journal.id, journal.id, JOURNAL_COVER_KIND, journal.coverImageUri, reusable),
            )
        }

    private suspend fun uploadDraftAsset(
        accessToken: String,
        draftId: Uuid,
        blockId: Uuid,
        kind: String,
        uri: String?,
        reusable: (String) -> Boolean,
    ): String? {
        if (uri == null) return null
        if (UnavailableMediaReferences.nativeSource(uri) != null) {
            return mediaSyncRefStore
                .getDraftAsset(draftId, blockId, kind)
                ?.takeIf { matchesLocalFile(it, uri) && isRemoteRef(it.remoteUrl) }
                ?.remoteUrl
                ?: throw MediaRecoveryException(DiagnosticReason.MISSING_MEDIA)
        }
        if (!requiresUpload(uri)) return uri
        val cached = mediaSyncRefStore.getDraftAsset(draftId, blockId, kind)
        if (cached != null &&
            matchesLocalFile(cached, uri) &&
            cached.remoteUrl.isNotBlank() &&
            reusable(cached.remoteUrl)
        ) {
            return cached.remoteUrl
        }
        val media = mediaManager.openMedia(uri)
        val named = MediaFileSource("draft-$kind-${media.fileName}", media.mimeType, media.sizeBytes) { media.open() }
        val uploaded = cloudMediaDataSource.uploadMedia(accessToken, blockId, named).getOrThrow()
        mediaSyncRefStore.upsertDraftAsset(
            draftId,
            blockId,
            kind,
            MediaSyncRef(
                noteId = blockId.toString(),
                localUri = uri,
                remoteUrl = uploaded.downloadUrl,
                mediaId = uploaded.mediaId,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        return uploaded.downloadUrl
    }

    /** Media is repaired after the draft text has been saved; failures leave the retry row intact. */
    suspend fun hydrateDraftMedia(
        accessToken: String,
        draft: EditorDraft,
        isStillCurrent: (() -> Boolean)? = null,
    ): DraftMediaHydrationResult {
        val mappings = mutableListOf<DraftAssetMapping>()
        val blocks = draft.blocks.toMutableList()
        val failures = mutableListOf<DiagnosticReason>()

        suspend fun recoverAsset(
            blockId: Uuid,
            kind: String,
            reference: String?,
        ): String? =
            try {
                hydrateDraftAsset(accessToken, draft.id, blockId, kind, reference, mappings, isStillCurrent)
            } catch (error: DraftMediaFailure) {
                failures += error.reason
                reference
            }
        draft.blocks.forEachIndexed { index, block ->
            when (block) {
                is SerializableImageBlock -> {
                    blocks[index] =
                        block.copy(uri = recoverAsset(block.id, "image", block.uri))
                }

                is SerializableVideoBlock -> {
                    val video = block.copy(uri = recoverAsset(block.id, "video", block.uri))
                    blocks[index] = video
                    blocks[index] =
                        video.copy(
                            thumbnailUri =
                                recoverAsset(block.id, "thumbnail", block.thumbnailUri),
                        )
                }

                is SerializableAudioBlock -> {
                    blocks[index] =
                        block.copy(uri = recoverAsset(block.id, "audio", block.uri))
                }

                is SerializableCameraBlock -> {
                    blocks[index] =
                        block.copy(uri = recoverAsset(block.id, "camera", block.uri))
                }

                else -> {
                    Unit
                }
            }
        }
        val updated = draft.copy(blocks = blocks)
        val reason =
            failures.firstOrNull {
                it == DiagnosticReason.KEY_RECOVERY_REQUIRED || it == DiagnosticReason.SIGN_IN_REQUIRED
            } ?: failures.firstOrNull()
        return if (reason == null) {
            DraftMediaHydrationResult.Available(updated, mappings)
        } else {
            DraftMediaHydrationResult.Pending(reason, updated, mappings)
        }
    }

    internal suspend fun hydrateDraftAsset(
        accessToken: String,
        draftId: Uuid,
        blockId: Uuid,
        kind: String,
        uri: String?,
        mappings: MutableList<DraftAssetMapping>,
        isStillCurrent: (() -> Boolean)? = null,
    ): String? {
        if (uri == null) return null
        if (isStillCurrent != null && !isStillCurrent()) throw CancellationException("Media hydration scope changed")
        if (originalAvailable(uri)) return uri
        val cached = mediaSyncRefStore.getDraftAsset(draftId, blockId, kind)
        val remote = if (isRemoteRef(uri)) uri else cached?.takeIf { matchesLocalFile(it, uri) }?.remoteUrl
        if (remote == null || !isRemoteRef(remote)) throw DraftMediaFailure(DiagnosticReason.MISSING_MEDIA)
        if (cached?.remoteUrl == remote && originalAvailable(cached.localUri, uri)) {
            return cached.localUri
        }
        if (!mayDownload()) throw DraftMediaFailure(DiagnosticReason.NETWORK_POLICY)
        val mediaId = extractMediaId(remote) ?: throw DraftMediaFailure(DiagnosticReason.MISSING_MEDIA)
        val downloaded =
            downloadMedia(accessToken, remote, mediaId).getOrElse { error ->
                if (error is CancellationException) throw error
                throw DraftMediaFailure(mediaFailureReason(error))
            }
        if (downloaded.contentId != blockId) throw DraftMediaFailure(DiagnosticReason.CORRUPT_PAYLOAD)
        if (isStillCurrent != null && !isStillCurrent()) throw CancellationException("Media hydration scope changed")
        val localUri =
            try {
                val payload = MediaPayload(downloaded.fileName, downloaded.mimeType, downloaded.data.size.toLong(), downloaded.data)
                if (isStillCurrent == null) {
                    mediaManager.saveMedia(payload)
                } else {
                    val conditionalSaver = mediaManager as? ConditionalMediaSaver ?: error("Conditional media saver unavailable")
                    conditionalSaver.saveMedia(payload) { isStillCurrent() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throwIfDeviceBlocked(error)
                throw DraftMediaFailure(DiagnosticReason.LOCAL_STORAGE)
            }
        if (!originalAvailable(localUri)) throw DraftMediaFailure(DiagnosticReason.LOCAL_STORAGE)
        mappings +=
            DraftAssetMapping(
                draftId,
                blockId,
                kind,
                MediaSyncRef(
                    noteId = blockId.toString(),
                    localUri = localUri,
                    remoteUrl = remote,
                    mediaId = mediaId,
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                    // Keep a retry connected to its old path if a concurrent editor save rejects the draft patch.
                    localAliases = hydrationAliases(cached, remote, uri),
                ),
            )
        return localUri
    }

    private fun hydrationAliases(
        cached: MediaSyncRef?,
        remote: String,
        uri: String,
    ): List<String> =
        if (cached?.remoteUrl == remote) {
            (cached.localAliases + cached.localUri + listOfNotNull(uri.takeUnless(::isRemoteRef)))
                .asReversed()
                .distinct()
                .take(8)
                .asReversed()
        } else {
            emptyList()
        }

    suspend fun resolveNativeNote(note: JournalNote): JournalNote? {
        val reference = note.mediaRefOrNull() ?: return note
        if (UnavailableMediaReferences.nativeSource(reference) == null) return note
        val mapping = mediaSyncRefStore.get(note.uid)?.takeIf { matchesLocalFile(it, reference) && isRemoteRef(it.remoteUrl) }
        return mapping?.let { note.withMediaRef(it.remoteUrl) }
    }

    /**
     * @param reusable whether an earlier upload, named by its remote reference, can stand in for
     *   uploading again; one made to the per-kind endpoint can't for a note bound for the personal space.
     */
    suspend fun uploadIfNeeded(
        accessToken: String,
        note: JournalNote,
        reusable: (String) -> Boolean = { true },
    ): Result<JournalNote> {
        if (note.mediaRefOrNull()?.let(UnavailableMediaReferences::nativeSource) != null) {
            return resolveNativeNote(note)?.let { Result.success(it) }
                ?: Result.failure(MediaRecoveryException(DiagnosticReason.MISSING_MEDIA))
        }
        val mediaRef = note.mediaRefOrNull() ?: return Result.success(note)
        if (!requiresUpload(mediaRef)) {
            return Result.success(note)
        }
        val cached = mediaSyncRefStore.get(note.uid)
        if (cached != null && matchesLocalFile(cached, mediaRef) && cached.remoteUrl.isNotBlank() && reusable(cached.remoteUrl)) {
            return Result.success(note.withMediaRef(cached.remoteUrl))
        }

        val media =
            runCatchingUnlessCancelled { mediaManager.openMedia(mediaRef) }
                .getOrElse { error -> return unreadableMedia(mediaRef, error) }

        return runCatchingUnlessCancelled {
            val upload = cloudMediaDataSource.uploadMedia(accessToken, note.uid, media)
            upload.exceptionOrNull()?.let { error ->
                // The file is read while the request is sent, so it can vanish after it was opened.
                if (error is MediaReadException) return unreadableMedia(mediaRef, error)
            }
            upload.getOrThrow()
        }.map { upload ->
            val remoteUrl = upload.downloadUrl
            mediaSyncRefStore.upsert(
                MediaSyncRef(
                    noteId = note.uid.toString(),
                    localUri = mediaRef,
                    remoteUrl = remoteUrl,
                    mediaId = upload.mediaId,
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                ),
            )
            note.withMediaRef(remoteUrl)
        }
    }

    /**
     * Distinguishes "the bytes are gone" from "the read happened to fail". Only the former is
     * hopeless; an upload that fails for any other reason is still worth retrying. If the check
     * itself fails we cannot show the file is still there, and the read has already failed -- treat
     * it as gone rather than blocking the queue.
     */
    private suspend fun unreadableMedia(
        mediaRef: String,
        error: Throwable,
    ): Result<JournalNote> {
        throwIfDeviceBlocked(error)
        val stillOnDisk =
            runCatchingUnlessCancelled { mediaManager.exists(mediaRef) }.getOrElse {
                throwIfDeviceBlocked(it)
                false
            }
        return if (!stillOnDisk) {
            Result.failure(MissingMediaException(mediaRef, error))
        } else {
            Result.failure(error)
        }
    }

    suspend fun downloadIfNeeded(
        accessToken: String,
        note: JournalNote,
    ): JournalNote =
        when (val result = hydrate(accessToken, note)) {
            is MediaHydrationResult.Available -> {
                result.mapping?.let { mediaSyncRefStore.upsert(it) }
                result.note
            }

            is MediaHydrationResult.Pending -> {
                throw MediaRecoveryException(result.reason)
            }
        }

    suspend fun hydrate(
        accessToken: String,
        note: JournalNote,
        isStillCurrent: (() -> Boolean)? = null,
    ): MediaHydrationResult {
        val mediaRef = note.mediaRefOrNull() ?: return MediaHydrationResult.Available(note)
        if (originalAvailable(mediaRef)) return MediaHydrationResult.Available(note)
        val cached = mediaSyncRefStore.get(note.uid)
        cachedOriginal(cached, mediaRef)?.let { return MediaHydrationResult.Available(note.withMediaRef(it), cached) }
        val remote =
            if (isRemoteRef(mediaRef)) {
                mediaRef
            } else {
                cached?.takeIf { matchesLocalFile(it, mediaRef) }?.remoteUrl
            }
        if (remote == null || !isRemoteRef(remote)) return MediaHydrationResult.Pending(DiagnosticReason.MISSING_MEDIA)
        if (!mayDownload()) return MediaHydrationResult.Pending(DiagnosticReason.NETWORK_POLICY)
        val mediaId = extractMediaId(remote) ?: return MediaHydrationResult.Pending(DiagnosticReason.MISSING_MEDIA)
        (cloudMediaDataSource as? FileMediaDataSource)?.downloadMediaToFile(accessToken, remote, mediaId)?.let { file ->
            return hydrateFromFile(note, remote, mediaId, cached, mediaRef, file, isStillCurrent)
        }
        val downloaded =
            downloadMedia(accessToken, remote, mediaId).getOrElse { error ->
                if (error is CancellationException) throw error
                return MediaHydrationResult.Pending(mediaFailureReason(error))
            }
        if (downloaded.contentId != note.uid) return MediaHydrationResult.Pending(DiagnosticReason.CORRUPT_PAYLOAD)
        if (isStillCurrent != null && !isStillCurrent()) throw CancellationException("Media hydration scope changed")
        return try {
            val payload =
                MediaPayload(
                    fileName = downloaded.fileName,
                    mimeType = downloaded.mimeType,
                    sizeBytes = downloaded.data.size.toLong(),
                    data = downloaded.data,
                )
            if (isStillCurrent != null && !isStillCurrent()) throw CancellationException("Media hydration scope changed")
            val localUri =
                if (isStillCurrent == null) {
                    mediaManager.saveMedia(payload)
                } else {
                    val conditionalSaver = mediaManager as? ConditionalMediaSaver ?: error("Conditional media saver unavailable")
                    conditionalSaver.saveMedia(payload) { isStillCurrent() }
                }
            check(originalAvailable(localUri))
            MediaHydrationResult.Available(
                note.withMediaRef(localUri),
                MediaSyncRef(
                    noteId = note.uid.toString(),
                    localUri = localUri,
                    remoteUrl = remote,
                    mediaId = mediaId,
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                    localAliases = hydrationAliases(cached, remote, mediaRef),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            MediaHydrationResult.Pending(DiagnosticReason.LOCAL_STORAGE)
        }
    }

    /**
     * Moves a file downloaded straight to disk into the media store, so the file is never held whole
     * in memory, and deletes the download either way.
     */
    private suspend fun hydrateFromFile(
        note: JournalNote,
        remote: String,
        mediaId: String,
        cached: MediaSyncRef?,
        mediaRef: String,
        download: Result<DownloadedMediaFile>,
        isStillCurrent: (() -> Boolean)?,
    ): MediaHydrationResult {
        val file =
            download.getOrElse { error ->
                if (error is CancellationException) throw error
                return MediaHydrationResult.Pending(mediaFailureReason(error))
            }
        try {
            if (file.contentId != note.uid) return MediaHydrationResult.Pending(DiagnosticReason.CORRUPT_PAYLOAD)
            if (isStillCurrent != null && !isStillCurrent()) throw CancellationException("Media hydration scope changed")
            val ticket =
                publicationCustody.reserve(
                    "sync-media:${note.uid}:$mediaId",
                    StagedMediaFile(file.path.toString(), file.fileName, file.mimeType),
                )
            val localUri =
                try {
                    if (isStillCurrent == null) {
                        mediaManager.saveMediaFromFile(file.path.toString(), file.fileName, file.mimeType)
                    } else {
                        val saver = mediaManager as? ConditionalMediaSaver ?: error("Conditional media saver unavailable")
                        saver.saveMediaFromFile(file.path.toString(), file.fileName, file.mimeType, isStillCurrent)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throwIfDeviceBlocked(error)
                    return MediaHydrationResult.Pending(DiagnosticReason.LOCAL_STORAGE)
                }
            publicationCustody.publish(ticket, localUri)
            if (isStillCurrent != null && !isStillCurrent()) {
                throw CancellationException("Media hydration scope changed")
            }
            if (!originalAvailable(localUri)) return MediaHydrationResult.Pending(DiagnosticReason.LOCAL_STORAGE)
            return MediaHydrationResult.Available(
                note.withMediaRef(localUri),
                MediaSyncRef(
                    noteId = note.uid.toString(),
                    localUri = localUri,
                    remoteUrl = remote,
                    mediaId = mediaId,
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                    localAliases = hydrationAliases(cached, remote, mediaRef),
                ),
            )
        } finally {
            SystemFileSystem.delete(file.path, mustExist = false)
        }
    }

    private suspend fun cachedOriginal(
        mapping: MediaSyncRef?,
        reference: String,
    ): String? =
        mapping
            ?.takeIf { it.remoteUrl == reference || matchesLocalFile(it, reference) }
            ?.localUri
            ?.takeIf { originalAvailable(it, reference) }

    private suspend fun downloadMedia(
        accessToken: String,
        reference: String,
        mediaId: String,
    ) = (cloudMediaDataSource as? app.logdate.client.sync.cloud.ReferencedMediaDataSource)
        ?.downloadReferencedMedia(accessToken, reference, mediaId)
        ?: cloudMediaDataSource.downloadMedia(accessToken, mediaId)

    private fun mediaFailureReason(error: Throwable): DiagnosticReason {
        throwIfDeviceBlocked(error)
        return when {
            error is IdentityKeyNotFoundException -> DiagnosticReason.KEY_RECOVERY_REQUIRED
            error is CloudApiException && error.statusCode == 404 -> DiagnosticReason.MISSING_MEDIA
            error is CloudApiException && error.statusCode == 401 -> DiagnosticReason.SIGN_IN_REQUIRED
            error is app.logdate.client.sync.cloud.MediaDecryptionException -> DiagnosticReason.CORRUPT_PAYLOAD
            error is app.logdate.client.sync.cloud.MediaIntegrityException -> DiagnosticReason.CORRUPT_PAYLOAD
            else -> DiagnosticReason.UNKNOWN
        }
    }

    private fun matchesLocalFile(
        mapping: MediaSyncRef,
        reference: String,
    ): Boolean = sameLocalFile(mapping.localUri, reference) || mapping.localAliases.any { sameLocalFile(it, reference) }

    private fun extractMediaId(mediaRef: String): String? =
        runCatching {
            val normalized =
                mediaRef
                    .substringBefore('#')
                    .substringBefore('?')
                    .trim()
            val segments = normalized.split('/')
            val mediaIndex = segments.indexOfLast { it == "media" }
            if (mediaIndex >= 0) {
                segments.getOrNull(mediaIndex + 1)?.takeIf { it.isNotBlank() }
            } else {
                segments.lastOrNull()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()

    private fun JournalNote.withMediaRef(mediaRef: String): JournalNote =
        when (this) {
            is JournalNote.Image -> copy(mediaRef = mediaRef)
            is JournalNote.Video -> copy(mediaRef = mediaRef)
            is JournalNote.Audio -> copy(mediaRef = mediaRef)
            else -> this
        }
}

internal sealed interface MediaHydrationResult {
    data class Available(
        val note: JournalNote,
        val mapping: MediaSyncRef? = null,
    ) : MediaHydrationResult

    data class Pending(
        val reason: DiagnosticReason,
    ) : MediaHydrationResult
}

internal data class DraftAssetMapping(
    val draftId: Uuid,
    val blockId: Uuid,
    val kind: String,
    val ref: MediaSyncRef,
)

internal sealed interface DraftMediaHydrationResult {
    data class Available(
        val draft: EditorDraft,
        val mappings: List<DraftAssetMapping>,
    ) : DraftMediaHydrationResult

    data class Pending(
        val reason: DiagnosticReason,
        val draft: EditorDraft,
        val mappings: List<DraftAssetMapping>,
    ) : DraftMediaHydrationResult
}

/** The kind a journal's cover is stored under in the draft asset references. */
internal const val JOURNAL_COVER_KIND = "journal-cover"

internal class DraftMediaFailure(
    val reason: DiagnosticReason,
) : Exception()

internal class MediaRecoveryException(
    val reason: DiagnosticReason,
) : Exception(reason.name)
