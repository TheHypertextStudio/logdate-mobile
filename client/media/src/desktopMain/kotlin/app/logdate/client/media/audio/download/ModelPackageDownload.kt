package app.logdate.client.media.audio.download

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class ModelPackageInstallation(
    val destination: File,
    val requiredNames: List<String>,
    val isReady: () -> Boolean,
    val unpackedBytes: (File) -> Long,
    val extract: (File, File) -> Unit,
)

/** Keeps a complete transfer while waiting for room to unpack it. */
fun downloadModelPackage(
    url: String,
    installation: ModelPackageInstallation,
    availableBytes: (File) -> Long = { it.usableSpace },
): Flow<ModelDownloadStatus> =
    flow {
        val destination = installation.destination

        if (installation.isReady()) {
            reconcileCompletedModelPackage(destination)
            emit(ModelDownloadStatus.Completed)
            return@flow
        }
        val staging = File(destination.parentFile, "${destination.name}.installing")
        val partial = File(destination.parentFile, "${destination.name}.tar.bz2.tmp")
        val archive = File(destination.parentFile, "${destination.name}.downloaded")
        var retainArchive = false
        var transferState: ModelPackageTransferState? = null
        try {
            val state = ModelPackageTransferState(destination)
            transferState = state
            state.useSource(url)
            if (!archive.isFile) fetchModelPackage(url, partial, archive, state, availableBytes) { emit(it) }
            installModelPackage(archive, staging, installation, availableBytes) { emit(ModelDownloadStatus.Extracting) }
            reconcileCompletedModelPackage(destination)
            emit(ModelDownloadStatus.Completed)
        } catch (e: CancellationException) {
            retainArchive = archive.isFile
            throw e
        } catch (e: Exception) {
            retainArchive = reportPackageFailure(e, archive, partial, transferState)
        } finally {
            try {
                deleteModelDirectory(staging.path)
            } finally {
                Files.deleteIfExists(partial.toPath())
                if (!retainArchive) {
                    retainedPackageSizes.remove(archive.path)
                    archive.delete()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

private suspend fun FlowCollector<ModelDownloadStatus>.reportPackageFailure(
    error: Exception,
    archive: File,
    partial: File,
    state: ModelPackageTransferState?,
): Boolean {
    Napier.e("Could not prepare model package", error)
    val archiveAvailable = archive.isFile
    val status =
        when (error) {
            is ModelTransferInterruptedException -> ModelDownloadStatus.NoNetwork
            is ModelServerUnavailableException -> ModelDownloadStatus.ServerUnavailable
            is ModelPackageUnchangedException -> ModelDownloadStatus.AwaitingUpdate
            is InvalidModelArchiveException -> {
                archive.delete()
                val failures = state?.markInvalid() ?: 1
                if (failures >= 2) ModelDownloadStatus.AwaitingUpdate else ModelDownloadStatus.ArchiveCorrupt
            }
            is IOException -> {
                val storageFull = error is ModelStorageFullException || error.message?.contains("space", ignoreCase = true) == true
                if (storageFull && !archiveAvailable) recordRequiredSpace(error, partial, state)
                if (storageFull) ModelDownloadStatus.OutOfStorage else ModelDownloadStatus.UnknownError
            }
            else -> ModelDownloadStatus.UnknownError
        }
    emit(status)
    return archiveAvailable && error !is InvalidModelArchiveException
}

private fun recordRequiredSpace(
    error: IOException,
    partial: File,
    state: ModelPackageTransferState?,
) {
    val needed =
        (error as? ModelStorageFullException)?.requiredFreeBytes
            ?: (partial.length() + MINIMUM_SPACE_INCREMENT)
                .coerceAtMost(partial.parentFile.totalSpace.coerceAtLeast(partial.length() + 1))
    partial.delete()
    try {
        state?.requireSpace(needed)
    } catch (metadataError: IOException) {
        Napier.e("Could not record required download space", metadataError)
    }
}

private suspend fun installModelPackage(
    archive: File,
    staging: File,
    installation: ModelPackageInstallation,
    availableBytes: (File) -> Long,
    installing: suspend () -> Unit,
) {
    val required = retainedPackageSizes.computeIfAbsent(archive.path) { installation.unpackedBytes(archive) }
    if (required < 0) throw InvalidModelArchiveException("Invalid unpacked model size")
    if (availableBytes(installation.destination.parentFile) < required) throw ModelStorageFullException()
    installing()
    installation.extract(archive, staging)
    publishModelFiles(staging.path, installation.destination.path, installation.requiredNames)
    if (!installation.isReady()) throw InvalidModelArchiveException("Installed model files are incomplete")
}

private suspend fun fetchModelPackage(
    url: String,
    partial: File,
    archive: File,
    state: ModelPackageTransferState,
    availableBytes: (File) -> Long,
    progress: suspend (ModelDownloadStatus) -> Unit,
) {
    partial.parentFile.mkdirs()
    if (availableBytes(partial.parentFile) < state.minimumFreeBytes) throw ModelStorageFullException(state.minimumFreeBytes)
    val checkingRevision = state.awaitsRevision
    val connection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = if (checkingRevision) "HEAD" else "GET"
        }
    try {
        val response = transport { connection.responseCode }
        if (checkingRevision && response !in 200..299) {
            if (!state.allowUnvalidatedProbe()) throw ModelPackageUnchangedException()
            connection.disconnect()
            fetchModelPackage(url, partial, archive, state, availableBytes, progress)
            return
        }
        if (response !in 200..299) throw ModelServerUnavailableException(response)
        val total = connection.contentLengthLong.takeIf { it > 0 }
        val etag = connection.getHeaderField("ETag")
        val modified = connection.lastModified
        if (!state.observe(etag, modified, total, checkingRevision)) throw ModelPackageUnchangedException()
        if (checkingRevision) {
            connection.disconnect()
            fetchModelPackage(url, partial, archive, state, availableBytes, progress)
            return
        }
        partial.parentFile.mkdirs()
        Files.deleteIfExists(partial.toPath())
        if (total != null && availableBytes(partial.parentFile) < total) throw ModelStorageFullException(total)
        copyModelBody(connection, partial, total, availableBytes, progress)
        try {
            Files.move(partial.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        connection.disconnect()
    }
}

private suspend fun copyModelBody(
    connection: HttpURLConnection,
    partial: File,
    total: Long?,
    availableBytes: (File) -> Long,
    progress: suspend (ModelDownloadStatus) -> Unit,
) {
    progress(ModelDownloadStatus.Downloading(0, total))
    transport { connection.inputStream }.use { input ->
        FileOutputStream(partial).use { output ->
            val buffer = ByteArray(8192)
            var transferred = 0L
            var reported = 0L
            while (true) {
                val count = transport { input.read(buffer) }
                if (count < 0) break
                if (availableBytes(partial.parentFile) < count) {
                    val needed =
                        (transferred + MINIMUM_SPACE_INCREMENT)
                            .coerceAtMost(partial.parentFile.totalSpace.coerceAtLeast(transferred + 1))
                    throw ModelStorageFullException(needed)
                }
                output.write(buffer, 0, count)
                transferred += count
                if (transferred - reported >= 64 * 1024) {
                    progress(ModelDownloadStatus.Downloading(transferred, total))
                    reported = transferred
                }
            }
            if (total != null && transferred != total) throw ModelTransferInterruptedException(IOException("Incomplete HTTP body"))
            progress(ModelDownloadStatus.Downloading(transferred, total))
        }
    }
}

private inline fun <T> transport(block: () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        throw ModelTransferInterruptedException(e)
    }

private class ModelTransferInterruptedException(
    cause: IOException,
) : IOException(cause)

private class ModelServerUnavailableException(
    status: Int,
) : IOException("HTTP $status")

private class ModelStorageFullException(
    val requiredFreeBytes: Long = 0,
) : IOException("Insufficient space to prepare model")

private class ModelPackageUnchangedException : IOException("Model package revision has not changed")

private const val MINIMUM_SPACE_INCREMENT = 8L * 1024 * 1024

private val retainedPackageSizes = ConcurrentHashMap<String, Long>()

fun reconcileCompletedModelPackage(destination: File) {
    for (suffix in listOf(".installing", ".tar.bz2.tmp", ".downloaded", ".transfer-state", ".transfer-state.new")) {
        try {
            deleteModelDirectory(File(destination.parentFile, "${destination.name}$suffix").path)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Napier.e("Could not remove completed transfer artifact", e)
        }
    }
    retainedPackageSizes.remove(File(destination.parentFile, "${destination.name}.downloaded").path)
}

fun checkedModelPackageReady(
    directoryPath: String,
    requiredNames: List<String>,
): Boolean {
    val ready = checkedModelFilesReady(directoryPath, requiredNames)
    if (ready) reconcileCompletedModelPackage(File(directoryPath))
    return ready
}
