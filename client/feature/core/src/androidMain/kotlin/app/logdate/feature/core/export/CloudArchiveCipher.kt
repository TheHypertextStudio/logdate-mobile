package app.logdate.feature.core.export

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Seals Cloud archive bytes with a key derived from the person's recovery identity. */
class CloudArchiveCipher(
    private val keyProvider: suspend () -> ByteArray,
) {
    /** Legacy in-memory envelope helper. Production backup uses the file-backed chunked overload. */
    suspend fun encrypt(archive: File): ByteArray {
        val key = keyProvider().also { require(it.size == KEY_SIZE) }
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))

        val output = ByteArrayOutputStream()
        output.write(PREFIX_V1)
        output.write(nonce)
        CipherOutputStream(output, cipher).use { encrypted -> archive.inputStream().use { it.copyTo(encrypted) } }
        return output.toByteArray()
    }

    /**
     * Writes independently authenticated 1 MiB chunks to a private file. The authenticated final
     * marker makes truncation detectable while keeping production memory use independent of archive size.
     */
    suspend fun encrypt(
        archive: File,
        destination: File,
    ) {
        require(archive != destination)
        val key = keyProvider().also { require(it.size == KEY_SIZE) }
        val noncePrefix = ByteArray(NONCE_PREFIX_SIZE).also { SecureRandom().nextBytes(it) }
        destination.parentFile?.let { require(it.exists() || it.mkdirs()) }

        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(destination), BUFFER_SIZE)).use { output ->
                output.write(PREFIX_V2)
                output.write(noncePrefix)
                BufferedInputStream(FileInputStream(archive), CHUNK_SIZE).use { input ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var counter = 0L
                    while (true) {
                        val count = input.readChunk(buffer)
                        if (count == 0) break
                        output.writeInt(count)
                        output.write(
                            chunkCipher(Cipher.ENCRYPT_MODE, key, noncePrefix, counter, count)
                                .doFinal(buffer, 0, count),
                        )
                        counter++
                    }

                    output.writeInt(0)
                    output.write(chunkCipher(Cipher.ENCRYPT_MODE, key, noncePrefix, counter, 0).doFinal())
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    suspend fun decrypt(
        payload: ByteArray,
        destination: File,
    ) {
        try {
            if (!payload.startsWith(PREFIX_V1)) {
                require(payload.isZip()) { "Cloud archive has an unknown format" }
                destination.writeBytes(payload)
                return
            }
            require(payload.size > PREFIX_V1.size + NONCE_SIZE + TAG_BYTES) { "Cloud archive is incomplete" }
            val key = keyProvider().also { require(it.size == KEY_SIZE) }
            val nonce = payload.copyOfRange(PREFIX_V1.size, PREFIX_V1.size + NONCE_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))

            val bodyOffset = PREFIX_V1.size + NONCE_SIZE
            ByteArrayInputStream(payload, bodyOffset, payload.size - bodyOffset).use { source ->
                CipherInputStream(source, cipher).use { decrypted ->
                    destination.outputStream().use { decrypted.copyTo(it) }
                }
            }
            require(destination.inputStream().use { input -> input.read() == 'P'.code && input.read() == 'K'.code }) {
                "Cloud archive did not decrypt to a ZIP"
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    /** Authenticates a complete encrypted archive before returning the private restore file. */
    suspend fun decrypt(
        source: File,
        destination: File,
    ) {
        try {
            BufferedInputStream(FileInputStream(source), BUFFER_SIZE).use { input ->
                val prefix = input.readExactly(PREFIX_V1.size)
                if (prefix.contentEquals(PREFIX_V2)) {
                    decryptChunked(input, destination)
                    return
                }
                if (!prefix.contentEquals(PREFIX_V1)) {
                    require(prefix.isZip()) { "Cloud archive has an unknown format" }
                    BufferedOutputStream(FileOutputStream(destination), BUFFER_SIZE).use { output ->
                        output.write(prefix)
                        input.copyTo(output, BUFFER_SIZE)
                    }
                    return
                }

                require(source.length() > (PREFIX_V1.size + NONCE_SIZE + TAG_BYTES).toLong()) {
                    "Cloud archive is incomplete"
                }
                val nonce = input.readExactly(NONCE_SIZE)
                val key = keyProvider().also { require(it.size == KEY_SIZE) }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))

                var plainSize = 0L
                var first = -1
                var second = -1

                fun writePlain(
                    output: OutputStream,
                    bytes: ByteArray,
                ) {
                    if (first < 0 && bytes.isNotEmpty()) first = bytes[0].toInt() and 0xff
                    if (second < 0 && bytes.size > 1) second = bytes[1].toInt() and 0xff
                    output.write(bytes)
                    plainSize += bytes.size
                }

                destination.parentFile?.let { require(it.exists() || it.mkdirs()) }
                BufferedOutputStream(FileOutputStream(destination), BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val plain = cipher.update(buffer, 0, count)
                        if (plain != null && plain.isNotEmpty()) writePlain(output, plain)
                    }
                    val finalBytes = cipher.doFinal()
                    if (finalBytes.isNotEmpty()) writePlain(output, finalBytes)
                    output.flush()
                }

                require(plainSize >= 2L && first == 'P'.code && second == 'K'.code) {
                    "Cloud archive did not decrypt to a ZIP"
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private suspend fun decryptChunked(
        input: BufferedInputStream,
        destination: File,
    ) {
        val noncePrefix = input.readExactly(NONCE_PREFIX_SIZE)
        val key = keyProvider().also { require(it.size == KEY_SIZE) }
        destination.parentFile?.let { require(it.exists() || it.mkdirs()) }
        var plaintextSize = 0L
        var first = -1
        var second = -1
        var counter = 0L
        var previousChunkWasShort = false

        DataInputStream(input).let { encrypted ->
            BufferedOutputStream(FileOutputStream(destination), CHUNK_SIZE).use { output ->
                while (true) {
                    val count = encrypted.readInt()
                    require(count in 0..CHUNK_SIZE) { "Cloud archive chunk is invalid" }
                    if (count == 0) {
                        val finalTag = encrypted.readExactly(TAG_BYTES)
                        val finalBytes = chunkCipher(Cipher.DECRYPT_MODE, key, noncePrefix, counter, 0).doFinal(finalTag)
                        require(finalBytes.isEmpty()) { "Cloud archive final marker is invalid" }
                        require(encrypted.read() == -1) { "Cloud archive has trailing data" }
                        break
                    }

                    require(!previousChunkWasShort) { "Cloud archive chunk sequence is invalid" }
                    val ciphertext = encrypted.readExactly(count + TAG_BYTES)
                    val plaintext = chunkCipher(Cipher.DECRYPT_MODE, key, noncePrefix, counter, count).doFinal(ciphertext)
                    require(plaintext.size == count) { "Cloud archive chunk length is invalid" }
                    if (first < 0 && plaintext.isNotEmpty()) first = plaintext[0].toInt() and 0xff
                    if (second < 0 && plaintext.size > 1) second = plaintext[1].toInt() and 0xff
                    output.write(plaintext)
                    plaintextSize += plaintext.size
                    previousChunkWasShort = count < CHUNK_SIZE
                    counter++
                }
                output.flush()
            }
        }

        require(plaintextSize >= 2L && first == 'P'.code && second == 'K'.code) {
            "Cloud archive did not decrypt to a ZIP"
        }
    }

    private fun chunkCipher(
        mode: Int,
        key: ByteArray,
        noncePrefix: ByteArray,
        counter: Long,
        plaintextLength: Int,
    ): Cipher {
        require(counter in 0..MAX_CHUNK_COUNTER) { "Cloud archive has too many chunks" }
        val counterBytes = counter.toInt().toBigEndianBytes()
        val lengthBytes = plaintextLength.toBigEndianBytes()
        val nonce = noncePrefix + counterBytes
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(PREFIX_V2)
            updateAAD(noncePrefix)
            updateAAD(counterBytes)
            updateAAD(lengthBytes)
        }
    }

    companion object {
        const val MANIFEST = "{\"format\":\"logdate-cloud-backup\",\"encryption\":\"identity-aes-gcm-chunked-v2\"}"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 32
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val NONCE_PREFIX_SIZE = NONCE_SIZE - Int.SIZE_BYTES
        private const val BUFFER_SIZE = 64 * 1024
        private const val CHUNK_SIZE = 1024 * 1024
        private const val MAX_CHUNK_COUNTER = 0xFFFF_FFFFL
        private val PREFIX_V1 = "LDCB1".encodeToByteArray()
        private val PREFIX_V2 = "LDCB2".encodeToByteArray()
    }
}

private fun java.io.InputStream.readChunk(buffer: ByteArray): Int {
    var count = 0
    while (count < buffer.size) {
        val read = read(buffer, count, buffer.size - count)
        if (read < 0) break
        count += read
    }
    return count
}

private fun java.io.InputStream.readExactly(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(bytes, offset, size - offset)
        require(count >= 0) { "Cloud archive is incomplete" }
        offset += count
    }
    return bytes
}

private fun Int.toBigEndianBytes(): ByteArray =
    byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        toByte(),
    )

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.isZip(): Boolean = size >= 2 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte()
