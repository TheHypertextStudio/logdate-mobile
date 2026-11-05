package app.logdate.client.media.audio.sherpa

import app.logdate.client.media.audio.download.InvalidModelArchiveException
import app.logdate.client.media.audio.download.deleteModelDirectory
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal fun extractModelArchive(
    archive: File,
    stagingDirectory: File,
) {
    deleteModelDirectory(stagingDirectory.path)
    if (!stagingDirectory.mkdirs() && !stagingDirectory.isDirectory) throw IOException("Cannot create model staging directory")
    val input = BufferedInputStream(archive.inputStream())
    val compressed =
        try {
            BZip2CompressorInputStream(input)
        } catch (e: IOException) {
            input.close()
            throw InvalidModelArchiveException("Invalid compressed model archive", e)
        }
    var topLevelPrefix: String? = null
    TarArchiveInputStream(compressed).use { tar ->
        while (true) {
            val entry = nextModelEntry(tar) ?: break
            val name = entry.name
            if (topLevelPrefix == null) {
                val slash = name.indexOf('/')
                if (slash > 0) topLevelPrefix = name.substring(0, slash + 1)
            }
            val relative = topLevelPrefix?.let { if (name.startsWith(it)) name.removePrefix(it) else name } ?: name
            if (relative.isEmpty()) continue
            val destination = File(stagingDirectory, relative)
            if (!destination.canonicalFile.toPath().startsWith(stagingDirectory.canonicalFile.toPath())) {
                throw InvalidModelArchiveException("Model archive entry escaped staging directory")
            }
            when {
                entry.isDirectory ->
                    if (!destination.mkdirs() &&
                        !destination.isDirectory
                    ) {
                        throw IOException("Cannot create model directory")
                    }
                entry.isFile -> copyModelEntry(tar, entry, destination)
                else -> throw InvalidModelArchiveException("Unsupported model archive entry")
            }
        }
    }
}

internal fun modelArchiveUnpackedBytes(archive: File): Long {
    val input = BufferedInputStream(archive.inputStream())
    val compressed =
        try {
            BZip2CompressorInputStream(input)
        } catch (e: IOException) {
            input.close()
            throw InvalidModelArchiveException("Invalid compressed model archive", e)
        }
    return TarArchiveInputStream(compressed).use { tar ->
        var total = 0L
        while (true) {
            val entry = nextModelEntry(tar) ?: break
            if (entry.isFile) {
                if (entry.size < 0 || Long.MAX_VALUE - total < entry.size) throw InvalidModelArchiveException("Invalid model entry size")
                total += entry.size
            }
        }
        total
    }
}

private fun nextModelEntry(tar: TarArchiveInputStream): TarArchiveEntry? =
    try {
        tar.nextEntry
    } catch (e: IOException) {
        throw InvalidModelArchiveException("Invalid model archive entry", e)
    }

private fun copyModelEntry(
    tar: TarArchiveInputStream,
    entry: TarArchiveEntry,
    destination: File,
) {
    val parent = destination.parentFile
    if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create model directory")
    FileOutputStream(destination).use { output ->
        val buffer = ByteArray(8192)
        var written = 0L
        while (true) {
            val count =
                try {
                    tar.read(buffer)
                } catch (e: IOException) {
                    throw InvalidModelArchiveException("Incomplete model archive entry", e)
                }
            if (count < 0) break
            output.write(buffer, 0, count)
            written += count
        }
        if (written != entry.size) throw InvalidModelArchiveException("Incomplete model archive entry")
    }
}
