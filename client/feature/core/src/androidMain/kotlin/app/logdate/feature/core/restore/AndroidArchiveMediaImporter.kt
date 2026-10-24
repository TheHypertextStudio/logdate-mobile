package app.logdate.feature.core.restore

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import app.logdate.client.domain.restore.ArchiveMediaIntegrity
import app.logdate.client.domain.restore.MediaImporter
import app.logdate.client.domain.restore.copyArchiveMedia
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.AndroidMediaPublicationTickets
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.isRecoveredOriginal
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import okio.sink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

internal class AndroidArchiveMediaImporter(
    private val context: Context,
    private val mediaManager: MediaManager,
    private val zipFile: ZipFile,
    private val root: String,
    private val references: StoredMediaReferences,
    private val integrity: Map<String, ArchiveMediaIntegrity>?,
    private val publicationTickets: AndroidMediaPublicationTickets,
) : MediaImporter {
    override suspend fun isAvailable(reference: String): Boolean = mediaManager.isRecoveredOriginal(reference, references)

    override fun containsMedia(exportPath: String): Boolean = zipFile.getEntry(root + exportPath.trimStart('/'))?.isDirectory == false

    override suspend fun importMedia(exportPath: String): String? {
        val normalizedPath = exportPath.trimStart('/')
        val entry = zipFile.getEntry(root + normalizedPath)
        if (entry == null) {
            Napier.w("Restore attachment missing")
            return null
        }
        if (entry.isDirectory) {
            Napier.w("Restore attachment is a directory")
            return null
        }
        val fileName = normalizedPath.substringAfterLast('/')
        val tempFile = File.createTempFile("logdate_media_", "_$fileName", context.cacheDir)
        try {
            zipFile.getInputStream(entry).use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyArchiveMedia(input.source(), output.sink(), normalizedPath, integrity)
                }
            }
            val fromExtension = resolveMimeType(fileName)
            // Always read magic bytes once and reuse — avoids double file I/O when both
            // the extension path and the verification path would otherwise call detectMimeTypeFromBytes.
            val fromBytes = detectMimeTypeFromBytes(tempFile)
            val mimeType =
                when {
                    fromExtension == "application/octet-stream" -> {
                        if (fromBytes != "application/octet-stream") {
                            Napier.d("Restore attachment type detected")
                        }
                        fromBytes
                    }

                    fromBytes != "application/octet-stream" && fromBytes != fromExtension -> {
                        // Extension may be inferred (e.g. .jpg for a HEIC file from a bare
                        // MediaStore content URI). Magic bytes win when they disagree.
                        Napier.w("Restore attachment type mismatch")
                        fromBytes
                    }

                    else -> {
                        fromExtension
                    }
                }
            val ticket = publicationTickets.reserve("archive-restore:$normalizedPath", tempFile.absolutePath)
            val savedPath =
                if (mimeType.startsWith("audio/")) {
                    saveAudioToInternalStorage(tempFile, fileName)
                } else {
                    mediaManager.saveMediaFromFile(
                        sourceFilePath = tempFile.absolutePath,
                        fileName = fileName,
                        mimeType = mimeType,
                    )
                }
            publicationTickets.publish(ticket.id, savedPath)
            Napier.d("Restore attachment imported")
            return savedPath
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Napier.e("Restore attachment import failed")
            return null
        } finally {
            tempFile.delete()
        }
    }

    private fun resolveMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mimeType =
            if (extension.isNotBlank()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            } else {
                null
            }
        return mimeType ?: "application/octet-stream"
    }

    private fun saveAudioToInternalStorage(
        sourceFile: File,
        fileName: String,
    ): String =
        Uri
            .fromFile(
                ArchiveAudioFileStore(File(context.filesDir, "audio_notes"), context.cacheDir).import(sourceFile, fileName),
            ).toString()

    private fun detectMimeTypeFromBytes(file: File): String =
        try {
            val header = ByteArray(16)
            file.inputStream().use { it.read(header) }
            when {
                header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> {
                    "image/jpeg"
                }

                header[0] == 0x89.toByte() &&
                    header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() &&
                    header[3] == 0x47.toByte() -> {
                    "image/png"
                }

                header[0] == 0x52.toByte() &&
                    header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() &&
                    header[3] == 0x46.toByte() &&
                    header[8] == 0x57.toByte() &&
                    header[9] == 0x45.toByte() &&
                    header[10] == 0x42.toByte() &&
                    header[11] == 0x50.toByte() -> {
                    "image/webp"
                }

                header[0] == 0x47.toByte() &&
                    header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() &&
                    header[3] == 0x38.toByte() -> {
                    "image/gif"
                }

                header[4] == 0x66.toByte() &&
                    header[5] == 0x74.toByte() &&
                    header[6] == 0x79.toByte() &&
                    header[7] == 0x70.toByte() -> {
                    val brand = String(header, 8, 4, Charsets.US_ASCII)
                    when {
                        brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("avi") -> "image/heic"
                        brand.startsWith("M4A") || brand.startsWith("m4a") -> "audio/mp4"
                        else -> "video/mp4"
                    }
                }

                else -> {
                    "application/octet-stream"
                }
            }
        } catch (e: Exception) {
            Napier.w("Restore attachment type detection failed")
            "application/octet-stream"
        }
}
