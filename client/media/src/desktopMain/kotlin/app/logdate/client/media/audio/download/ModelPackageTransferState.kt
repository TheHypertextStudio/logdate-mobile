package app.logdate.client.media.audio.download

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.Properties

internal class ModelPackageTransferState(
    destination: File,
) {
    private val file = File(destination.parentFile, "${destination.name}.transfer-state")
    private val values =
        Properties().apply {
            try {
                Files.newInputStream(file.toPath()).use { load(it) }
            } catch (_: NoSuchFileException) {
                // No transfer has been requested yet.
            } catch (_: IllegalArgumentException) {
                clear()
            }
        }
    val awaitsRevision: Boolean get() = invalidCount >= 2
    val minimumFreeBytes: Long get() = values.getProperty("minimumFreeBytes")?.toLongOrNull() ?: 0
    private val invalidCount: Int get() = values.getProperty("invalidCount")?.toIntOrNull() ?: 0

    fun useSource(url: String) {
        if (values.getProperty("url") != url) {
            values.clear()
            values.setProperty("url", url)
            save()
        }
    }

    fun requireSpace(bytes: Long) {
        values.setProperty("minimumFreeBytes", maxOf(minimumFreeBytes, bytes).toString())
        save()
    }

    fun observe(
        etag: String?,
        modified: Long,
        length: Long?,
        headersOnly: Boolean,
    ): Boolean {
        val changed = revisionChanged(etag, modified, length)
        if (headersOnly) {
            if (changed) {
                resetInvalidCount()
                return true
            }
            return if (matchesValidator(etag, modified)) false else allowUnvalidatedProbe()
        }
        if (changed) values.setProperty("invalidCount", "0")
        values.setProperty("etag", etag.orEmpty())
        values.setProperty("modified", modified.toString())
        values.setProperty("length", length?.toString().orEmpty())
        save()
        return true
    }

    fun allowUnvalidatedProbe(): Boolean {
        val lastFailure = values.getProperty("lastInvalidAt")?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - lastFailure < UNKNOWN_REVISION_RECHECK_MS) return false
        resetInvalidCount()
        return true
    }

    fun markInvalid(): Int {
        val count = invalidCount + 1
        values.setProperty("invalidCount", count.toString())
        values.setProperty("lastInvalidAt", System.currentTimeMillis().toString())
        save()
        return count
    }

    private fun revisionChanged(
        etag: String?,
        modified: Long,
        length: Long?,
    ): Boolean {
        val oldTag = values.getProperty("etag")?.takeIf { it.isNotEmpty() }
        val oldModified = values.getProperty("modified")?.toLongOrNull()?.takeIf { it > 0 }
        val oldLength = values.getProperty("length")?.toLongOrNull()?.takeIf { it > 0 }
        return when {
            etag != null && oldTag != null -> etag != oldTag
            modified > 0 && oldModified != null -> modified != oldModified
            length != null && oldLength != null -> length != oldLength
            else -> false
        }
    }

    private fun matchesValidator(
        etag: String?,
        modified: Long,
    ): Boolean {
        val oldTag = values.getProperty("etag")?.takeIf { it.isNotEmpty() }
        if (etag != null && oldTag != null) return oldTag == etag
        return modified > 0 && values.getProperty("modified")?.toLongOrNull() == modified
    }

    private fun resetInvalidCount() {
        values.setProperty("invalidCount", "0")
        save()
    }

    private fun save() {
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.new")
        Files.deleteIfExists(temporary.toPath())
        FileOutputStream(temporary).use { output ->
            values.store(output, null)
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val UNKNOWN_REVISION_RECHECK_MS = 24L * 60 * 60 * 1_000
    }
}
