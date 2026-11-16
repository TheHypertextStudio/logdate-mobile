package app.logdate.client.media.audio.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** Checks presence without disguising access or inspection failures as absent files. */
fun checkedModelFilesReady(
    directoryPath: String,
    requiredNames: List<String>,
): Boolean {
    val directory = File(directoryPath).toPath()
    try {
        if (!Files.readAttributes(directory, BasicFileAttributes::class.java).isDirectory) return false
        for (name in requiredNames) {
            val path = directory.resolve(name)
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile || attributes.size() <= 0) return false
            Files.newInputStream(path).use { if (it.read() < 0) return false }
        }
        return true
    } catch (_: NoSuchFileException) {
        return false
    }
}

/** Only a validated staging directory becomes visible as the installed model. */
fun publishModelFiles(
    stagingDirectory: String,
    installedDirectory: String,
    requiredNames: List<String>,
) {
    if (!checkedModelFilesReady(stagingDirectory, requiredNames)) throw InvalidModelArchiveException("Incomplete model files")
    if (Files.isSymbolicLink(File(stagingDirectory).toPath())) throw InvalidModelArchiveException("Linked model staging directory")
    val target = File(installedDirectory)
    deleteModelDirectory(target.path)
    Files.move(File(stagingDirectory).toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
}

/** Deletes an owned model directory or link without visiting linked directories. */
fun deleteModelDirectory(directoryPath: String) {
    val path = File(directoryPath).toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths ->
        paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.delete(it) }
    }
}

class InvalidModelArchiveException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
