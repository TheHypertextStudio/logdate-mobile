package app.logdate

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fetches one [SpeechModel] archive, checks it against the model's SHA-256, and writes the asset the
 * app bundles.
 *
 * The archive is downloaded into [workDirectory] and the asset is written beside its destination
 * under a temporary name, then moved into place in one step. An interrupted or corrupt download
 * therefore never leaves a file at the asset's path that a later build would bundle.
 */
internal class SpeechModelDownloader(
    private val workDirectory: File,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
) {
    fun fetch(
        model: SpeechModel,
        destination: File,
    ) {
        workDirectory.mkdirs()
        val archive = File(workDirectory, "${model.assetName}.download")
        val partial = File(destination.parentFile, "${destination.name}.part")
        try {
            val actual = download(model.url, archive)
            if (!actual.equals(model.sha256, ignoreCase = true)) {
                throw IOException(
                    "${model.url} has SHA-256 $actual, but the build expects ${model.sha256}. " +
                        "The file may be truncated or replaced; the model was not bundled.",
                )
            }
            when {
                model.url.endsWith(".tar.bz2") -> repackageTarBz2AsZip(archive, partial, model.excludePatterns)
                model.url.endsWith(".onnx") && destination.name.endsWith(".zip") ->
                    wrapInZip(archive, model.url.substringAfterLast('/'), partial)
                else -> archive.copyTo(partial, overwrite = true)
            }
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            archive.delete()
            partial.delete()
        }
    }

    /** Copies [url] to [target] and returns the SHA-256 of the bytes received, in lowercase hex. */
    private fun download(
        url: String,
        target: File,
    ): String {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        if (connection is HttpURLConnection) {
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("Downloading $url failed with HTTP $status")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(connection.getInputStream(), digest).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun wrapInZip(
        source: File,
        entryName: String,
        zipFile: File,
    ) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry(entryName))
            source.inputStream().use { it.copyTo(zipOut) }
            zipOut.closeEntry()
        }
    }

    private fun repackageTarBz2AsZip(
        tarBz2File: File,
        zipFile: File,
        excludePatterns: List<String>,
    ) {
        var topLevelPrefix: String? = null
        ZipOutputStream(zipFile.outputStream().buffered()).use { zipOut ->
            TarArchiveInputStream(BZip2CompressorInputStream(tarBz2File.inputStream().buffered())).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (topLevelPrefix == null) {
                        val slashIndex = entryName.indexOf('/')
                        if (slashIndex > 0) topLevelPrefix = entryName.substring(0, slashIndex + 1)
                    }
                    val prefix = topLevelPrefix
                    val relativePath = if (prefix != null && entryName.startsWith(prefix)) entryName.removePrefix(prefix) else entryName
                    val skipped = relativePath.isEmpty() || excludePatterns.any { relativePath.endsWith(it) }
                    if (!skipped && entry.isDirectory) {
                        zipOut.putNextEntry(ZipEntry("$relativePath/"))
                        zipOut.closeEntry()
                    } else if (!skipped) {
                        zipOut.putNextEntry(ZipEntry(relativePath))
                        tarIn.copyTo(zipOut)
                        zipOut.closeEntry()
                    }
                    entry = tarIn.nextEntry
                }
            }
        }
    }
}
