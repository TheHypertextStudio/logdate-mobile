package app.logdate.client.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import app.logdate.client.media.storage.AndroidCanonicalMediaStore
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.androidMediaFileResolver
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.asSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLConnection
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AndroidMediaManager(
    private val contentResolver: ContentResolver,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val canonicalMediaStore: AndroidCanonicalMediaStore = AndroidCanonicalMediaStore(context.filesDir),
    private val mediaFiles: MediaFileResolver = androidMediaFileResolver(context),
) : MediaManager,
    ConditionalMediaSaver {
    private val filesDir = context.filesDir
    private val legacyBackfillMutex = Mutex()
    private val recoveryGateway: MediaRecoveryGateway =
        ContentResolverMediaRecoveryGateway(contentResolver, context)

    private enum class MediaKind {
        IMAGE,
        VIDEO,
    }

    override suspend fun getMedia(uri: String): MediaObject =
        withContext(ioDispatcher) {
            // Every helper below either queries ContentResolver or reads metadata
            // through MediaMetadataRetriever for file:// URIs, so the entire
            // body is IO. Keep it dispatched off the caller's thread so this
            // can never freeze a Compose render scope.
            val parsedUri = localUri(uri)
            val fileName = resolveFileName(parsedUri)

            when (resolveMediaKind(parsedUri, fileName)) {
                MediaKind.IMAGE -> {
                    if (parsedUri.isFileBacked()) {
                        getImageMediaFromFileUri(parsedUri)
                    } else {
                        getImageMedia(parsedUri)
                    }
                }

                MediaKind.VIDEO -> {
                    if (parsedUri.isFileBacked()) {
                        getVideoMediaFromFileUri(parsedUri)
                    } else {
                        getVideoMedia(parsedUri)
                    }
                }
            }
        }

    /**
     * Retrieves image media information from the content provider.
     */
    private fun getImageMedia(uri: Uri): MediaObject.Image {
        val cursor =
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED,
                ),
                null,
                null,
                null,
            )

        return cursor?.use {
            if (!it.moveToFirst()) {
                throw IllegalStateException("Unable to query image metadata for URI: $uri")
            }

            val nameIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateTakenIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val dateIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            it
                .toMediaCursorRow(
                    uri = uri.toString(),
                    nameColumn = nameIndex,
                    sizeColumn = sizeIndex,
                    durationColumn = -1,
                    dateTakenColumn = dateTakenIndex,
                    dateAddedColumn = dateIndex,
                ).toImage(recoveryGateway)
        } ?: throw IllegalStateException("Unable to query image metadata for URI: $uri")
    }

    /**
     * Retrieves video media information from the content provider.
     */
    private fun getVideoMedia(uri: Uri): MediaObject.Video {
        val cursor =
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_TAKEN,
                    MediaStore.Video.Media.DATE_ADDED,
                ),
                null,
                null,
                null,
            )

        return cursor?.use {
            if (!it.moveToFirst()) {
                throw IllegalStateException("Unable to query video metadata for URI: $uri")
            }

            val nameIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateTakenIndex = it.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
            val dateIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            it
                .toMediaCursorRow(
                    uri = uri.toString(),
                    nameColumn = nameIndex,
                    sizeColumn = sizeIndex,
                    durationColumn = durationIndex,
                    dateTakenColumn = dateTakenIndex,
                    dateAddedColumn = dateIndex,
                ).toVideo(recoveryGateway)
        } ?: throw IllegalStateException("Unable to query video metadata for URI: $uri")
    }

    override suspend fun queryMediaByDate(
        start: Instant,
        end: Instant,
    ): Flow<List<MediaObject>> =
        flow {
            ensureLegacyManagedMediaBackfilled()
            try {
                emit(queryMediaByDateInternal(start, end))
            } catch (error: Exception) {
                Napier.e("Failed to query Android media by date", error)
                throw error
            }
        }

    override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> =
        flow {
            ensureLegacyManagedMediaBackfilled()
            // Initial snapshot.
            try {
                emit(getRecentMediaInternal(limit))
            } catch (error: Exception) {
                Napier.e("Failed to query recent Android media", error)
                throw error
            }
            // Re-emit a fresh snapshot every time MediaStore reports a change
            // (new capture, deletion, edit). Lets the in-app picker surface a
            // photo taken in the system camera without any manual refresh.
            mediaStoreInvalidations().collect {
                try {
                    emit(getRecentMediaInternal(limit))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Napier.e("Failed to refresh recent Android media after MediaStore change", error)
                }
            }
        }

    override suspend fun getRecentImages(limit: Int): Flow<List<MediaObject.Image>> =
        flow {
            emit(getRecentMediaInternal(limit, includeVideos = false).filterIsInstance<MediaObject.Image>())
            mediaStoreInvalidations().collect {
                try {
                    emit(getRecentMediaInternal(limit, includeVideos = false).filterIsInstance<MediaObject.Image>())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Napier.e("Failed to refresh recent Android images", error)
                }
            }
        }

    /**
     * Emits Unit each time MediaStore's image or video collection changes.
     * The flow registers a [ContentObserver] for the lifetime of each
     * collection and tears it down when the collector cancels.
     */
    private fun mediaStoreInvalidations(): Flow<Unit> =
        callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val observer =
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            val uris =
                listOf(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                )
            uris.forEach { uri ->
                contentResolver.registerContentObserver(uri, true, observer)
            }
            awaitClose { contentResolver.unregisterContentObserver(observer) }
        }.conflate()

    // A note can claim the same content-addressed object after a reference check and before
    // deletion. Until all writers share a durable claim lifecycle, no caller can unlink it safely.
    @Suppress("UNUSED_PARAMETER")
    override suspend fun deleteOwnedMedia(uri: String): Boolean = false

    override suspend fun exists(mediaId: String): Boolean {
        val parsedUri = localUri(mediaId)

        return withContext(ioDispatcher) {
            when (parsedUri.scheme) {
                ContentResolver.SCHEME_CONTENT -> {
                    queryUriExists(parsedUri)
                }

                ContentResolver.SCHEME_FILE -> {
                    requireFileFromUri(parsedUri).exists()
                }

                null, "" -> {
                    queryLegacyMediaStoreIdExists(mediaId) ||
                        File(mediaId).exists() ||
                        File(filesDir, "media/$mediaId").exists() ||
                        File(filesDir, "user_media/$mediaId").exists()
                }

                else -> {
                    queryUriExists(parsedUri)
                }
            }
        }
    }

    override suspend fun addToDefaultCollection(uri: String) {
        val parsedUri = localUri(uri)
        val fileName = resolveFileName(parsedUri)
        val mimeType =
            requirePublishableMimeType(
                resolveSupportedMimeType(parsedUri, fileName),
                uri,
            )

        if (parsedUri.authority == MediaStore.AUTHORITY) {
            return
        }

        if (parsedUri.isFileBacked()) {
            val sourceFile = requireFileFromUri(parsedUri)
            if (sourceFile.exists() && legacyMediaAlreadyPublished(sourceFile, mimeType)) {
                Napier.d("Media already exists in MediaStore: $uri")
                return
            }
        }

        val publishedUri =
            try {
                publishMediaToStore(
                    sourceUri = parsedUri,
                    fileName = fileName,
                    mimeType = mimeType,
                    timestamp = resolveSourceTimestamp(parsedUri),
                )
            } catch (error: Exception) {
                Napier.e("Failed to publish media to MediaStore", error)
                throw error
            }
        Napier.d("Published media to MediaStore: $publishedUri")
    }

    private suspend fun queryMediaByDateInternal(
        start: Instant,
        end: Instant,
    ): List<MediaObject> =
        withContext(ioDispatcher) {
            val startMillis = start.toEpochMilliseconds()
            val endMillis = end.toEpochMilliseconds()
            val mediaItems = mutableListOf<MediaObject>()

            val imageSelection =
                "(${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?) " +
                    "OR (${MediaStore.Images.Media.DATE_TAKEN} IS NULL AND ${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?)"
            val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val imageProjection =
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED,
                )
            val imageSortOrder = "${MediaStore.Images.Media.DATE_TAKEN} ASC, ${MediaStore.Images.Media.DATE_ADDED} ASC"

            requireQueryCursor(
                collectionUri = imageCollection,
                projection = imageProjection,
                selection = imageSelection,
                selectionArgs =
                    arrayOf(
                        startMillis.toString(),
                        endMillis.toString(),
                        (startMillis / 1000).toString(),
                        (endMillis / 1000).toString(),
                    ),
                sortOrder = imageSortOrder,
                failureMessage = "Unable to query Android images for onboarding import",
            ).use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateTakenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    try {
                        mediaItems.add(
                            imageFromCursor(
                                collectionUri = imageCollection,
                                cursor = cursor,
                                idColumn = idColumn,
                                nameColumn = nameColumn,
                                sizeColumn = sizeColumn,
                                dateTakenColumn = dateTakenColumn,
                                dateAddedColumn = dateColumn,
                            ),
                        )
                    } catch (error: Exception) {
                        Napier.e("Unable to materialize Android image row even with fallbacks", error)
                    }
                }
            }

            val videoSelection =
                "(${MediaStore.Video.Media.DATE_TAKEN} >= ? AND ${MediaStore.Video.Media.DATE_TAKEN} < ?) " +
                    "OR (${MediaStore.Video.Media.DATE_TAKEN} IS NULL AND ${MediaStore.Video.Media.DATE_ADDED} >= ? AND ${MediaStore.Video.Media.DATE_ADDED} < ?)"
            val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val videoProjection =
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_TAKEN,
                    MediaStore.Video.Media.DATE_ADDED,
                )
            val videoSortOrder = "${MediaStore.Video.Media.DATE_TAKEN} ASC, ${MediaStore.Video.Media.DATE_ADDED} ASC"

            requireQueryCursor(
                collectionUri = videoCollection,
                projection = videoProjection,
                selection = videoSelection,
                selectionArgs =
                    arrayOf(
                        startMillis.toString(),
                        endMillis.toString(),
                        (startMillis / 1000).toString(),
                        (endMillis / 1000).toString(),
                    ),
                sortOrder = videoSortOrder,
                failureMessage = "Unable to query Android videos for onboarding import",
            ).use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateTakenColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    try {
                        mediaItems.add(
                            videoFromCursor(
                                collectionUri = videoCollection,
                                cursor = cursor,
                                idColumn = idColumn,
                                nameColumn = nameColumn,
                                sizeColumn = sizeColumn,
                                durationColumn = durationColumn,
                                dateTakenColumn = dateTakenColumn,
                                dateAddedColumn = dateColumn,
                            ),
                        )
                    } catch (error: Exception) {
                        Napier.e("Unable to materialize Android video row even with fallbacks", error)
                    }
                }
            }

            mediaItems.sortBy { it.timestamp }
            mediaItems
        }

    private suspend fun getRecentMediaInternal(
        limit: Int,
        includeVideos: Boolean = true,
    ): List<MediaObject> =
        withContext(ioDispatcher) {
            val mediaItems = mutableListOf<MediaObject>()

            val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val imageProjection =
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED,
                )
            val imageSortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

            requireQueryCursor(
                collectionUri = imageCollection,
                projection = imageProjection,
                sortOrder = imageSortOrder,
                limit = limit,
                failureMessage = "Unable to query recent Android images",
            ).use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateTakenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    try {
                        mediaItems.add(
                            imageFromCursor(
                                collectionUri = imageCollection,
                                cursor = cursor,
                                idColumn = idColumn,
                                nameColumn = nameColumn,
                                sizeColumn = sizeColumn,
                                dateTakenColumn = dateTakenColumn,
                                dateAddedColumn = dateColumn,
                            ),
                        )
                    } catch (error: Exception) {
                        Napier.e("Unable to materialize Android image row even with fallbacks", error)
                    }
                }
            }

            val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val videoProjection =
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_TAKEN,
                    MediaStore.Video.Media.DATE_ADDED,
                )
            val videoSortOrder = "${MediaStore.Video.Media.DATE_TAKEN} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC"

            if (includeVideos) {
                requireQueryCursor(
                    collectionUri = videoCollection,
                    projection = videoProjection,
                    sortOrder = videoSortOrder,
                    limit = limit,
                    failureMessage = "Unable to query recent Android videos",
                ).use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val dateTakenColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                    while (cursor.moveToNext()) {
                        try {
                            mediaItems.add(
                                videoFromCursor(
                                    collectionUri = videoCollection,
                                    cursor = cursor,
                                    idColumn = idColumn,
                                    nameColumn = nameColumn,
                                    sizeColumn = sizeColumn,
                                    durationColumn = durationColumn,
                                    dateTakenColumn = dateTakenColumn,
                                    dateAddedColumn = dateColumn,
                                ),
                            )
                        } catch (error: Exception) {
                            Napier.e("Unable to materialize Android video row even with fallbacks", error)
                        }
                    }
                }
            }

            // Cursor already returned at most `limit` rows of each type pre-sorted by
            // recency. Final sort merges images and videos.
            mediaItems.sortedByDescending { it.timestamp }
        }

    override suspend fun readMedia(uri: String): MediaPayload =
        withContext(ioDispatcher) {
            val parsedUri = localUri(uri)
            val fileName = resolveFileName(parsedUri)
            val mimeType = resolveSupportedMimeType(parsedUri, fileName)
            val data = readBytes(parsedUri)
            MediaPayload(
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = data.size.toLong(),
                data = data,
            )
        }

    override suspend fun openMedia(uri: String): MediaFileSource =
        withContext(ioDispatcher) {
            val parsedUri = localUri(uri)
            val sizeBytes = resolveSizeBytes(parsedUri) ?: return@withContext super.openMedia(uri)
            val fileName = resolveFileName(parsedUri)
            MediaFileSource(
                fileName = fileName,
                mimeType = resolveSupportedMimeType(parsedUri, fileName),
                sizeBytes = sizeBytes,
            ) { openSourceInputStream(parsedUri).asSource() }
        }

    /**
     * The byte length of a file-backed [uri], or null for a content URI. A provider's reported
     * length can disagree with what it streams, so content URIs are read whole instead.
     */
    private fun resolveSizeBytes(uri: Uri): Long? {
        if (!uri.isFileBacked()) return null
        val file = requireFileFromUri(uri)
        check(file.isFile) { "Media file does not exist: ${file.absolutePath}" }
        return file.length()
    }

    override suspend fun saveMedia(payload: MediaPayload): String = saveMedia(payload) { true }

    override suspend fun saveMedia(
        payload: MediaPayload,
        canCommit: () -> Boolean,
    ): String {
        val mimeType =
            requireSupportedMediaMimeType(
                payload.mimeType.ifBlank {
                    resolveMimeTypeFromFileName(payload.fileName)
                },
                payload.fileName,
            )
        return try {
            require(payload.sizeBytes == payload.data.size.toLong()) {
                "Expected ${payload.sizeBytes} media bytes but received ${payload.data.size}"
            }
            withContext(ioDispatcher) {
                canonicalMediaStore.store(
                    input = payload.data.inputStream(),
                    mimeType = mimeType,
                    expectedSizeBytes = payload.sizeBytes,
                    canCommit = canCommit,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Napier.e("Failed to persist media payload", error)
            throw error
        }
    }

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
    ): String = saveMediaFromFile(sourceFilePath, fileName, mimeType) { true }

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
        canCommit: () -> Boolean,
    ): String {
        val sourceFile = File(sourceFilePath)
        val supportedMimeType = requireSupportedMediaMimeType(mimeType, fileName)
        return try {
            withContext(ioDispatcher) {
                check(sourceFile.isFile) { "Media file does not exist: ${sourceFile.absolutePath}" }
                canonicalMediaStore.store(
                    input = FileInputStream(sourceFile),
                    mimeType = supportedMimeType,
                    expectedSizeBytes = sourceFile.length(),
                    canCommit = canCommit,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Napier.e("Failed to persist media file", error)
            throw error
        }
    }

    private suspend fun ensureLegacyManagedMediaBackfilled() =
        legacyBackfillMutex.withLock {
            val directory = legacyMediaDirectory()
            if (!directory.exists()) {
                return@withLock
            }

            directory
                .listFiles()
                ?.asSequence()
                ?.filter { it.isFile }
                ?.forEach { file ->
                    val inferredMimeType = resolveMimeTypeFromFileName(file.name) ?: return@forEach
                    if (!isPublishableMimeType(inferredMimeType)) {
                        return@forEach
                    }

                    if (
                        legacyMediaAlreadyPublished(
                            file = file,
                            mimeType = inferredMimeType,
                        )
                    ) {
                        Napier.d("Legacy media file already published in MediaStore: ${file.absolutePath}")
                        return@forEach
                    }

                    runCatching {
                        publishFileToMediaStore(
                            sourceFile = file,
                            fileName = file.name,
                            mimeType = inferredMimeType,
                            timestamp = fileTimestamp(file),
                        )
                    }.onSuccess { publishedUri ->
                        Napier.d("Backfilled legacy media file into MediaStore: $publishedUri")
                    }.onFailure { error ->
                        Napier.e("Failed to backfill legacy media file: ${file.absolutePath}", error)
                    }
                }
        }

    private fun legacyMediaDirectory(): File = File(filesDir, "user_media")

    private fun isPublishableMimeType(mimeType: String): Boolean = mimeType.startsWith("image/") || mimeType.startsWith("video/")

    private fun isSupportedMediaMimeType(mimeType: String): Boolean =
        mimeType in
            setOf(
                "image/jpeg",
                "image/heic",
                "image/heic-sequence",
                "image/heif",
                "image/heif-sequence",
                "image/avif",
                "image/bmp",
                "image/png",
                "image/webp",
                "image/gif",
                "video/mp4",
                "video/mpeg",
                "video/ogg",
                "video/webm",
                "video/quicktime",
                "video/3gpp",
                "video/3gpp2",
                "video/x-matroska",
                "video/x-msvideo",
                "video/x-ms-wmv",
                "audio/mp4",
                "audio/mpeg",
                "audio/ogg",
                "audio/aac",
                "audio/amr",
                "audio/flac",
                "audio/midi",
                "audio/opus",
                "audio/3gpp",
                "audio/3gpp2",
                "audio/webm",
                "audio/wav",
            )

    private fun resolveMimeTypeFromFileName(fileName: String): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName).lowercase()
        if (extension == "m4a") {
            return "audio/mp4"
        }
        val guessedFromExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return guessedFromExtension ?: URLConnection.guessContentTypeFromName(fileName)
    }

    private fun fileTimestamp(file: File): Instant {
        check(file.exists()) { "Media file does not exist: ${file.absolutePath}" }
        val lastModified = file.lastModified()
        check(lastModified > 0L) { "Unable to resolve lastModified for media file: ${file.absolutePath}" }
        return Instant.fromEpochMilliseconds(lastModified)
    }

    private suspend fun publishMediaToStore(
        sourceUri: Uri,
        fileName: String,
        mimeType: String,
        timestamp: Instant,
    ): String =
        publishStreamToMediaStore(
            openInputStream = { openSourceInputStream(sourceUri) },
            fileName = fileName,
            mimeType = mimeType,
            timestamp = timestamp,
        )

    private suspend fun publishBytesToMediaStore(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        timestamp: Instant,
    ): String =
        publishStreamToMediaStore(
            openInputStream = { java.io.ByteArrayInputStream(bytes) },
            fileName = fileName,
            mimeType = mimeType,
            timestamp = timestamp,
        )

    private suspend fun publishFileToMediaStore(
        sourceFile: File,
        fileName: String,
        mimeType: String,
        timestamp: Instant,
    ): String =
        publishStreamToMediaStore(
            openInputStream = { FileInputStream(sourceFile) },
            fileName = fileName,
            mimeType = mimeType,
            timestamp = timestamp,
        )

    private suspend fun publishStreamToMediaStore(
        openInputStream: () -> InputStream,
        fileName: String,
        mimeType: String,
        timestamp: Instant,
    ): String {
        var insertedUri: Uri? = null
        var ownershipHandedOff = false
        var primaryFailure: Throwable? = null

        try {
            val publishedUri =
                withContext(ioDispatcher) {
                    val target =
                        mediaStoreTargetForMimeType(mimeType)
                            ?: throw IllegalArgumentException("Unsupported media type: $mimeType")
                    val sanitizedName = sanitizeFileName(fileName)
                    val values =
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
                            put(MediaStore.MediaColumns.DATE_TAKEN, timestamp.toEpochMilliseconds())
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    insertedUri =
                        contentResolver.insert(target.collectionUri, values)
                            ?: throw IllegalStateException("Failed to create MediaStore row for $fileName")
                    val destinationUri = checkNotNull(insertedUri)

                    openInputStream().use { input ->
                        contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                            copyToMediaStoreCancellable(input, output)
                        } ?: throw IllegalStateException("Failed to open output stream for $destinationUri")
                    }
                    currentCoroutineContext().ensureActive()

                    val cleared =
                        contentResolver.update(
                            destinationUri,
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                            },
                            null,
                            null,
                        )
                    check(cleared > 0) { "Failed to finalize MediaStore row for $destinationUri" }
                    currentCoroutineContext().ensureActive()

                    destinationUri.toString()
                }

            ownershipHandedOff = true
            return publishedUri
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            if (!ownershipHandedOff) {
                insertedUri?.let { orphanedUri ->
                    try {
                        withContext(NonCancellable) {
                            withContext(ioDispatcher) {
                                check(contentResolver.delete(orphanedUri, null, null) > 0) {
                                    "Failed to delete abandoned MediaStore row for $orphanedUri"
                                }
                            }
                        }
                    } catch (cleanupFailure: Throwable) {
                        primaryFailure?.addSuppressed(cleanupFailure)
                        if (primaryFailure == null) throw cleanupFailure
                        Napier.e("Failed to delete abandoned MediaStore row", cleanupFailure)
                    }
                }
            }
        }
    }

    private suspend fun copyToMediaStoreCancellable(
        input: InputStream,
        output: java.io.OutputStream,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val byteCount = input.read(buffer)
            if (byteCount < 0) break
            output.write(buffer, 0, byteCount)
        }
        output.flush()
        currentCoroutineContext().ensureActive()
    }

    private fun openSourceInputStream(uri: Uri): InputStream =
        if (uri.isFileBacked()) {
            FileInputStream(requireFileFromUri(uri))
        } else {
            contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Invalid URI: $uri")
        }

    private fun persistAudioStream(
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
    ): String =
        inputStream.use { input ->
            val directory =
                File(filesDir, "audio_notes").apply {
                    check(isDirectory || mkdirs()) { "Unable to create private audio directory: $absolutePath" }
                }
            removeStaleAudioTemporaryFiles(directory)

            val destinationPrefix = "${Uuid.random()}-"
            val destinationName =
                destinationPrefix +
                    normalizeAudioFileName(
                        fileName = fileName,
                        mimeType = mimeType,
                        maxBytes = MAX_AUDIO_DESTINATION_NAME_BYTES - destinationPrefix.toByteArray(Charsets.UTF_8).size,
                    )
            val destination = File(directory, destinationName)
            val temporary = File(directory, ".${destination.name}.tmp")

            try {
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
                check(temporary.renameTo(destination)) {
                    "Unable to finalize private audio file: ${destination.absolutePath}"
                }
                Uri.fromFile(destination).toString()
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
        }

    private fun normalizeAudioFileName(
        fileName: String,
        mimeType: String,
        maxBytes: Int,
    ): String {
        val sanitizedName = sanitizeFileName(fileName).ifBlank { "audio" }
        val stem =
            sanitizedName
                .substringBeforeLast('.', missingDelimiterValue = sanitizedName)
                .ifBlank { "audio" }
        val suffix = ".${requireAudioFileExtension(mimeType)}"
        val stemByteBudget = maxBytes - suffix.toByteArray(Charsets.UTF_8).size
        check(stemByteBudget > 0) { "Audio file extension exceeds the private storage filename budget" }
        return stem.truncateUtf8(stemByteBudget).ifBlank { "audio" } + suffix
    }

    private fun requireAudioFileExtension(mimeType: String): String =
        when (mimeType) {
            "audio/aac" -> "aac"
            "audio/amr" -> "amr"
            "audio/flac" -> "flac"
            "audio/midi" -> "mid"
            "audio/mp4" -> "m4a"
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav" -> "wav"
            "audio/webm" -> "webm"
            "audio/3gpp" -> "3gp"
            "audio/3gpp2" -> "3g2"
            else -> throw IllegalArgumentException("Unsupported audio type for private storage: $mimeType")
        }

    private fun String.truncateUtf8(maxBytes: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return this
        }

        val result = StringBuilder()
        var index = 0
        var byteCount = 0
        while (index < length) {
            val codePoint = Character.codePointAt(this, index)
            val codePointText = String(Character.toChars(codePoint))
            val codePointBytes = codePointText.toByteArray(Charsets.UTF_8).size
            if (byteCount + codePointBytes > maxBytes) {
                break
            }
            result.append(codePointText)
            byteCount += codePointBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun removeStaleAudioTemporaryFiles(directory: File) {
        val staleBefore = Clock.System.now().toEpochMilliseconds() - AUDIO_TEMP_MAX_AGE_MILLIS
        directory
            .listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && file.lastModified() in 1..staleBefore && file.isManagedAudioTemporaryFile() }
            ?.forEach { staleFile ->
                runCatching { staleFile.delete() }
                    .onSuccess { deleted ->
                        if (!deleted) {
                            Napier.w("Unable to remove stale private audio temporary file: ${staleFile.absolutePath}")
                        }
                    }.onFailure { error ->
                        Napier.w("Unable to remove stale private audio temporary file: ${staleFile.absolutePath}", error)
                    }
            }
    }

    private fun File.isManagedAudioTemporaryFile(): Boolean {
        if (!name.startsWith('.') || !name.endsWith(".tmp") || name.length <= AUDIO_TEMP_UUID_END_INDEX) {
            return false
        }
        if (name.getOrNull(AUDIO_TEMP_UUID_END_INDEX) != '-') {
            return false
        }
        return runCatching {
            Uuid.parse(name.substring(AUDIO_TEMP_UUID_START_INDEX, AUDIO_TEMP_UUID_END_INDEX))
        }.isSuccess
    }

    private fun mediaStoreTargetForMimeType(mimeType: String): MediaStoreTarget? =
        when {
            mimeType.startsWith("image/") -> MediaStoreTarget(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/LogDate")
            mimeType.startsWith("video/") -> MediaStoreTarget(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/LogDate")
            else -> null
        }

    private data class MediaStoreTarget(
        val collectionUri: Uri,
        val relativePath: String,
    )

    private fun sanitizeFileName(fileName: String): String =
        fileName
            .replace("..", "_")
            .replace("/", "_")
            .replace("\\", "_")
            .replace('\u0000', '_')

    /**
     * [reference] as a URI the code below can open: a `file:` URI for LogDate media references and
     * local files (including paths from another Android user), the parsed [reference] otherwise.
     */
    private fun localUri(reference: String): Uri = mediaFiles.filePath(reference)?.let { Uri.fromFile(File(it)) } ?: Uri.parse(reference)

    private fun requireFileFromUri(uri: Uri): File {
        val path =
            if (uri.scheme.isNullOrBlank()) {
                uri.toString().takeIf { it.startsWith('/') }
            } else {
                uri.path
            } ?: throw IllegalArgumentException("File URI is missing a path: $uri")
        return File(path)
    }

    private fun Uri.isFileBacked(): Boolean =
        scheme == ContentResolver.SCHEME_FILE ||
            (scheme.isNullOrBlank() && toString().startsWith('/'))

    private fun getImageMediaFromFileUri(uri: Uri): MediaObject.Image {
        val file = requireFileFromUri(uri)
        return MediaObject.Image(
            uri = uri.toString(),
            name = file.name,
            size = file.length().toInt(),
            timestamp = fileTimestamp(file),
        )
    }

    private fun getVideoMediaFromFileUri(uri: Uri): MediaObject.Video {
        val file = requireFileFromUri(uri)
        return MediaObject.Video(
            uri = uri.toString(),
            name = file.name,
            size = file.length().toInt(),
            duration = resolveFileVideoDuration(uri),
            timestamp = fileTimestamp(file),
        )
    }

    private fun resolveSourceTimestamp(uri: Uri): Instant =
        if (uri.isFileBacked()) {
            fileTimestamp(requireFileFromUri(uri))
        } else {
            querySourceTimestamp(uri)
                ?: throw IllegalStateException("Unable to resolve timestamp for media URI: $uri")
        }

    private fun querySourceTimestamp(uri: Uri): Instant? {
        val cursor =
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_ADDED,
                ),
                null,
                null,
                null,
            ) ?: return null

        return cursor.use {
            if (!it.moveToFirst()) {
                null
            } else {
                val dateTakenIndex = it.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val dateAddedIndex = it.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                resolveTimestampOrNull(dateTakenIndex, dateAddedIndex, it)
            }
        }
    }

    /**
     * Answers with false rather than propagating a provider failure. Callers are asking whether
     * something is there, and a provider that refuses the question has not established that it is.
     */
    private fun queryUriExists(uri: Uri): Boolean =
        runCatching {
            contentResolver
                .query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    cursor.moveToFirst()
                } == true
        }.getOrElse { error ->
            Napier.d("Could not check whether $uri exists: ${error.message}")
            false
        }

    private fun queryLegacyMediaStoreIdExists(mediaId: String): Boolean {
        if (!looksLikeMediaStoreId(mediaId)) {
            return false
        }
        val imageUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        val videoUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
        return queryUriExists(imageUri) || queryUriExists(videoUri)
    }

    private fun legacyMediaAlreadyPublished(
        file: File,
        mimeType: String,
    ): Boolean {
        val target = mediaStoreTargetForMimeType(mimeType) ?: return false
        val sanitizedName = sanitizeFileName(file.name)
        val timestamp = fileTimestamp(file).toEpochMilliseconds()
        return contentResolver
            .query(
                target.collectionUri,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ? AND ${MediaStore.MediaColumns.DATE_TAKEN} = ?",
                arrayOf(
                    sanitizedName,
                    file.length().toString(),
                    timestamp.toString(),
                ),
                null,
            )?.use { cursor ->
                cursor.moveToFirst()
            } == true
    }

    private fun resolveTimestampOrNull(
        dateTakenColumn: Int,
        dateAddedColumn: Int,
        cursor: Cursor,
    ): Instant? {
        if (dateTakenColumn >= 0) {
            val taken = cursor.getLong(dateTakenColumn)
            if (taken > 0L) {
                return Instant.fromEpochMilliseconds(taken)
            }
        }

        if (dateAddedColumn >= 0) {
            val added = cursor.getLong(dateAddedColumn)
            if (added > 0L) {
                return Instant.fromEpochMilliseconds(added * 1000)
            }
        }

        return null
    }

    private fun resolveFileName(uri: Uri): String {
        if (uri.isFileBacked()) {
            return requireFileFromUri(uri).name
        }

        val contentName =
            contentResolver
                .query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                }
        return contentName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Unable to resolve media file name for URI: $uri")
    }

    private fun resolveSupportedMimeType(
        uri: Uri,
        fileName: String,
    ): String {
        if (uri.isPrivateAudioFile()) {
            resolvePrivateAudioMimeType(fileName)?.let { return it }
        }

        val contentType = if (uri.isFileBacked()) null else contentResolver.getType(uri)
        if (!contentType.isNullOrBlank()) {
            return requireSupportedMediaMimeType(contentType, uri.toString())
        }

        return requireSupportedMediaMimeType(resolveMimeTypeFromFileName(fileName), uri.toString())
    }

    private fun Uri.isPrivateAudioFile(): Boolean =
        isFileBacked() &&
            runCatching {
                requireFileFromUri(this).parentFile?.canonicalFile == File(filesDir, "audio_notes").canonicalFile
            }.getOrDefault(false)

    private fun resolvePrivateAudioMimeType(fileName: String): String? =
        when (fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "aac" -> "audio/aac"
            "amr" -> "audio/amr"
            "flac" -> "audio/flac"
            "mid" -> "audio/midi"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            "3gp" -> "audio/3gpp"
            "3g2" -> "audio/3gpp2"
            else -> null
        }

    private fun readBytes(uri: Uri): ByteArray =
        openSourceInputStream(uri).use { stream ->
            stream.readBytes()
        }

    private fun resolveMediaKind(
        uri: Uri,
        fileName: String,
    ): MediaKind {
        val mimeType = resolveSupportedMimeType(uri, fileName)
        return when {
            mimeType.startsWith("image/") -> MediaKind.IMAGE
            mimeType.startsWith("video/") -> MediaKind.VIDEO
            else -> throw IllegalArgumentException("Media metadata is unavailable for audio type: $mimeType")
        }
    }

    private fun requirePublishableMimeType(
        mimeType: String?,
        source: String,
    ): String {
        val resolvedMimeType =
            normalizeMimeType(mimeType)
                ?: throw IllegalArgumentException("Unable to resolve media type for $source")
        if (!isPublishableMimeType(resolvedMimeType)) {
            throw IllegalArgumentException("Unsupported media type for $source: $resolvedMimeType")
        }
        return resolvedMimeType
    }

    private fun requireSupportedMediaMimeType(
        mimeType: String?,
        source: String,
    ): String {
        val resolvedMimeType =
            normalizeMimeType(mimeType)
                ?: throw IllegalArgumentException("Unable to resolve media type for $source")
        if (!isSupportedMediaMimeType(resolvedMimeType)) {
            throw IllegalArgumentException("Unsupported media type for $source: $resolvedMimeType")
        }
        return resolvedMimeType
    }

    private fun normalizeMimeType(mimeType: String?): String? =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?.let { normalized ->
                when (normalized) {
                    "audio/mp3" -> "audio/mpeg"
                    "audio/wave", "audio/x-wav", "audio/vnd.wave" -> "audio/wav"
                    "audio/x-flac" -> "audio/flac"
                    "audio/x-m4a" -> "audio/mp4"
                    "audio/x-midi" -> "audio/midi"
                    else -> normalized
                }
            }

    private companion object {
        const val MAX_AUDIO_DESTINATION_NAME_BYTES = 250
        const val AUDIO_TEMP_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        const val AUDIO_TEMP_UUID_START_INDEX = 1
        const val AUDIO_TEMP_UUID_END_INDEX = 37
    }

    /**
     * Resolves a video's duration when the source is a file:// URI. Falls back
     * to [Duration.ZERO] on any failure so the user never loses access to the
     * video just because we can't read its duration metadata.
     */
    private fun resolveFileVideoDuration(uri: Uri): Duration {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(requireFileFromUri(uri).absolutePath)
            val parsed =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.milliseconds
            if (parsed != null) return parsed

            Napier.w("Missing video duration metadata for file URI: $uri — defaulting to zero so the video stays visible")
            return Duration.ZERO
        } catch (error: RuntimeException) {
            Napier.w("Unable to resolve video duration for file URI: $uri — defaulting to zero so the video stays visible", error)
            return Duration.ZERO
        } finally {
            try {
                retriever.release()
            } catch (error: RuntimeException) {
                Napier.e("Failed to release MediaMetadataRetriever", error)
            }
        }
    }

    private fun requireQueryCursor(
        collectionUri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null,
        limit: Int? = null,
        failureMessage: String,
    ): Cursor {
        // Push LIMIT into the cursor query when we know an explicit cap; on API 30+
        // MediaStore honors QUERY_ARG_LIMIT, which keeps the cursor from materializing
        // the entire library before the caller slices it.
        val cursor =
            if (limit != null) {
                val queryArgs =
                    Bundle().apply {
                        if (selection != null) {
                            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        }
                        if (selectionArgs != null) {
                            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                        }
                        if (sortOrder != null) {
                            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                        }
                        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    }
                contentResolver.query(collectionUri, projection, queryArgs, null)
            } else {
                contentResolver.query(
                    collectionUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder,
                )
            }
        return cursor ?: throw IllegalStateException(failureMessage)
    }

    private fun imageFromCursor(
        collectionUri: Uri,
        cursor: Cursor,
        idColumn: Int,
        nameColumn: Int,
        sizeColumn: Int,
        dateTakenColumn: Int,
        dateAddedColumn: Int,
    ): MediaObject.Image {
        val uri = Uri.withAppendedPath(collectionUri, cursor.getLong(idColumn).toString())
        val row =
            cursor.toMediaCursorRow(
                uri = uri.toString(),
                nameColumn = nameColumn,
                sizeColumn = sizeColumn,
                durationColumn = -1,
                dateTakenColumn = dateTakenColumn,
                dateAddedColumn = dateAddedColumn,
            )
        return row.toImage(recoveryGateway)
    }

    private fun videoFromCursor(
        collectionUri: Uri,
        cursor: Cursor,
        idColumn: Int,
        nameColumn: Int,
        sizeColumn: Int,
        durationColumn: Int,
        dateTakenColumn: Int,
        dateAddedColumn: Int,
    ): MediaObject.Video {
        val uri = Uri.withAppendedPath(collectionUri, cursor.getLong(idColumn).toString())
        val row =
            cursor.toMediaCursorRow(
                uri = uri.toString(),
                nameColumn = nameColumn,
                sizeColumn = sizeColumn,
                durationColumn = durationColumn,
                dateTakenColumn = dateTakenColumn,
                dateAddedColumn = dateAddedColumn,
            )
        return row.toVideo(recoveryGateway)
    }

    /**
     * Extracts a [MediaCursorRow] snapshot from the cursor's current row,
     * keeping every metadata field nullable so the fallback logic in
     * [MediaCursorRow.toImage]/[MediaCursorRow.toVideo] can recover gracefully
     * from missing values. Pass [durationColumn] as -1 for image rows.
     */
    private fun Cursor.toMediaCursorRow(
        uri: String,
        nameColumn: Int,
        sizeColumn: Int,
        durationColumn: Int,
        dateTakenColumn: Int,
        dateAddedColumn: Int,
    ): MediaCursorRow {
        val displayName =
            if (nameColumn >= 0 && !isNull(nameColumn)) getString(nameColumn) else null
        val sizeBytes =
            if (sizeColumn >= 0 && !isNull(sizeColumn)) getInt(sizeColumn) else null
        val durationMillis =
            if (durationColumn >= 0 && !isNull(durationColumn)) getLong(durationColumn) else null
        val dateTakenMillis =
            if (dateTakenColumn >= 0 && !isNull(dateTakenColumn)) getLong(dateTakenColumn) else null
        val dateAddedSeconds =
            if (dateAddedColumn >= 0 && !isNull(dateAddedColumn)) getLong(dateAddedColumn) else null
        return MediaCursorRow(
            uri = uri,
            displayName = displayName,
            sizeBytes = sizeBytes,
            durationMillis = durationMillis,
            dateTakenMillis = dateTakenMillis,
            dateAddedSeconds = dateAddedSeconds,
        )
    }
}

/**
 * MediaStore ids are row numbers. Appending anything else to a collection URI builds a path
 * MediaStore rejects outright, which turned an existence check on an ordinary file path into a
 * thrown UnsupportedOperationException.
 */
internal fun looksLikeMediaStoreId(mediaId: String): Boolean = mediaId.isNotEmpty() && mediaId.all { it.isDigit() }
