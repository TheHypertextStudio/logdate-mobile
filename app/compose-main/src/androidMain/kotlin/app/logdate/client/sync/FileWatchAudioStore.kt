package app.logdate.client.sync

import android.content.Context
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Stores watch audio in the phone's private audio directory, next to phone recordings, and keeps
 * metadata that arrived ahead of its audio in a separate directory the audio scan never sees.
 *
 * The audio file appears at [audioPath] only when complete: bytes land in a staging file that is
 * synced to disk and renamed into place.
 *
 * Files left by a transfer that never finished are cleared as new work arrives: a staging file the
 * process died in the middle of, and metadata whose audio never came. Deletion markers are not
 * cleared, since a marker is empty and the only thing that stops a late delivery from coming back.
 */
class FileWatchAudioStore(
    private val audioDirectory: File,
    private val incomingDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WatchAudioStore {
    constructor(context: Context) : this(
        audioDirectory = File(context.filesDir, "audio_notes"),
        incomingDirectory = File(context.filesDir, "watch_incoming"),
    )

    private val metadataSerializer = MapSerializer(String.serializer(), String.serializer())

    override fun audioPath(noteId: Uuid): String = audioFile(noteId).absolutePath

    override fun hasAudio(noteId: Uuid): Boolean = audioFile(noteId).isFile

    override suspend fun writeAudio(
        noteId: Uuid,
        source: InputStream,
    ): Boolean =
        withContext(ioDispatcher) {
            val staging = File(audioDirectory, "wear_$noteId.m4a.part")
            try {
                audioDirectory.mkdirs()
                clearOlderThan(audioDirectory, STAGING_MAX_AGE_MS) { it.name.endsWith(STAGING_SUFFIX) }
                FileOutputStream(staging).use { output ->
                    source.copyTo(output)
                    output.fd.sync()
                }
                if (staging.length() == 0L) {
                    staging.delete()
                    return@withContext false
                }
                Files.move(staging.toPath(), audioFile(noteId).toPath(), StandardCopyOption.ATOMIC_MOVE)
                true
            } catch (e: Exception) {
                Napier.w("Failed to store watch audio for note $noteId", e)
                staging.delete()
                false
            }
        }

    override suspend fun stashMetadata(
        noteId: Uuid,
        data: Map<String, String>,
    ) {
        withContext(ioDispatcher) {
            incomingDirectory.mkdirs()
            clearOlderThan(incomingDirectory, STASHED_METADATA_MAX_AGE_MS) { it.name.endsWith(METADATA_SUFFIX) }
            metadataFile(noteId).writeText(Json.encodeToString(metadataSerializer, data))
        }
    }

    override suspend fun stashedMetadata(noteId: Uuid): Map<String, String>? =
        withContext(ioDispatcher) {
            val file = metadataFile(noteId)
            if (!file.isFile) return@withContext null
            try {
                Json.decodeFromString(metadataSerializer, file.readText())
            } catch (e: Exception) {
                Napier.w("Discarding unreadable watch metadata for note $noteId", e)
                null
            }
        }

    override suspend fun clearMetadata(noteId: Uuid) {
        withContext(ioDispatcher) { metadataFile(noteId).delete() }
    }

    override suspend fun discard(noteId: Uuid) {
        withContext(ioDispatcher) {
            audioFile(noteId).delete()
            metadataFile(noteId).delete()
        }
    }

    override suspend fun markDeleted(noteId: Uuid) {
        withContext(ioDispatcher) {
            incomingDirectory.mkdirs()
            deletionMarker(noteId).writeText("")
        }
    }

    override suspend fun isDeleted(noteId: Uuid): Boolean = withContext(ioDispatcher) { deletionMarker(noteId).isFile }

    override suspend fun matchesAudioDigest(
        noteId: Uuid,
        expectedDigest: String,
    ): Boolean =
        withContext(ioDispatcher) {
            val file = audioFile(noteId)
            if (!file.isFile) return@withContext false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == expectedDigest
        }

    private fun clearOlderThan(
        directory: File,
        maxAgeMs: Long,
        matches: (File) -> Boolean,
    ) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        directory
            .listFiles()
            .orEmpty()
            .filter { matches(it) && it.isFile && it.lastModified() < cutoff }
            .forEach { stale ->
                if (stale.delete()) Napier.d("Cleared abandoned watch file ${stale.name}")
            }
    }

    private fun audioFile(noteId: Uuid): File = File(audioDirectory, "wear_$noteId.m4a")

    private fun metadataFile(noteId: Uuid): File = File(incomingDirectory, "$noteId.json")

    private fun deletionMarker(noteId: Uuid): File = File(incomingDirectory, "$noteId.deleted")

    private companion object {
        const val STAGING_SUFFIX = ".m4a.part"
        const val METADATA_SUFFIX = ".json"

        /** A transfer writes continuously, so a staging file untouched for an hour was abandoned. */
        const val STAGING_MAX_AGE_MS = 60L * 60 * 1000

        /** The watch gives up resending a note after minutes, so metadata a week old will never get its audio. */
        const val STASHED_METADATA_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
