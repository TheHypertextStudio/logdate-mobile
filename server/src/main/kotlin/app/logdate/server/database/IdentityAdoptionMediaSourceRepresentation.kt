package app.logdate.server.database

import app.logdate.server.crypto.EncryptionService
import app.logdate.server.identity.adoption.IDENTITY_ADOPTION_MAX_BLOB_BYTES
import app.logdate.server.identity.adoption.IdentityAdoptionMediaSourceStream
import app.logdate.server.logdate.LogDateBlobStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

/** Existing server ciphertext is authenticated before any plaintext is offered to a response. */
internal suspend fun mediaSourceRepresentation(
    input: InputStream,
    rawSize: Long,
    rawHash: String?,
    rowDigest: String,
    encryption: EncryptionService?,
    decryptServerMedia: Boolean,
    reopen: suspend (String) -> InputStream?,
): IdentityAdoptionMediaSourceStream? =
    withContext(Dispatchers.IO) {
        val prefixStream = PushbackInputStream(input, 5)
        try {
            val prefix = prefixStream.readNBytes(5)
            prefixStream.unread(prefix)
            if (decryptServerMedia && prefix.contentEquals("LDSM1".toByteArray(Charsets.US_ASCII))) {
                return@withContext decryptedMediaSource(
                    prefixStream,
                    rowDigest,
                    requireNotNull(encryption) { "Legacy decryption is unavailable" },
                )
            }
            if (rawHash != null) {
                val stream = LogDateBlobStream(rawSize, prefixStream)
                return@withContext IdentityAdoptionMediaSourceStream(stream, "stored", rawHash, rowDigest)
            }
            val context = currentCoroutineContext()
            val hash = prefixStream.use { mediaStreamDigest(it) { context.ensureActive() } }
            val fresh = reopen(hash) ?: return@withContext null
            IdentityAdoptionMediaSourceStream(LogDateBlobStream(rawSize, fresh), "stored", hash, rowDigest)
        } catch (error: Throwable) {
            prefixStream.close()
            throw error
        }
    }

private suspend fun decryptedMediaSource(
    input: InputStream,
    rowDigest: String,
    encryption: EncryptionService,
): IdentityAdoptionMediaSourceStream {
    val directory =
        Files.createTempDirectory(
            "identity-adoption-media-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
    val stored = directory.resolve("source")
    val plain = directory.resolve("plain")
    try {
        for (file in listOf(stored, plain)) {
            Files.createFile(
                file,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        }
        val context = currentCoroutineContext()
        copyMediaSource(input, stored) { context.ensureActive() }
        encryption.processMediaDownload(stored, plain, shouldDecrypt = true) { context.ensureActive() }
        val size = Files.size(plain)
        check(size in 0..IDENTITY_ADOPTION_MAX_BLOB_BYTES)
        val hash = Files.newInputStream(plain).use { mediaStreamDigest(it) { context.ensureActive() } }
        val stream =
            object : FilterInputStream(Files.newInputStream(plain)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        removeMediaSourceFiles(directory)
                    }
                }
            }
        return IdentityAdoptionMediaSourceStream(LogDateBlobStream(size, stream), "legacy-server-decrypted", hash, rowDigest)
    } catch (error: Throwable) {
        removeMediaSourceFiles(directory)
        throw error
    }
}

private fun copyMediaSource(
    input: InputStream,
    destination: Path,
    checkActive: () -> Unit,
) {
    input.use { source ->
        Files.newOutputStream(destination).use { output ->
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                checkActive()
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                check(total <= IDENTITY_ADOPTION_MAX_BLOB_BYTES)
                output.write(buffer, 0, count)
            }
        }
    }
}

private fun mediaStreamDigest(
    input: InputStream,
    checkActive: () -> Unit,
): String {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        hash.update(buffer, 0, count)
    }
    return HexFormat.of().formatHex(hash.digest())
}

private fun removeMediaSourceFiles(directory: Path) {
    Files.deleteIfExists(directory.resolve("plain"))
    Files.deleteIfExists(directory.resolve("source"))
    Files.deleteIfExists(directory)
}
