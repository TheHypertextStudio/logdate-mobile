package app.logdate.server.crypto

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.file.Files
import java.nio.file.Path

class PayloadCodec(
    private val keyring: EncryptionKeyring,
    private val cipher: AesGcmCipher = AesGcmCipher(),
) {
    fun encryptBackupFile(
        input: Path,
        output: Path,
        checkActive: () -> Unit = {},
    ) {
        val key = keyring.getActiveKey()
        val iv = cipher.generateIV()
        val header = PayloadHeaderCodec.encode(PayloadPrefixes.SERVER_BACKUP, key.keyId, iv, ByteArray(0))
        Files.newOutputStream(output).buffered().use { destination ->
            destination.write(header)
            Files.newInputStream(input).buffered().use { source ->
                transformBackup(source, destination, key.keyBytes, iv, encrypt = true, checkActive = checkActive)
            }
        }
    }

    fun decryptBackupFile(
        input: Path,
        output: Path,
        checkActive: () -> Unit = {},
    ) = decryptFile(input, output, PayloadPrefixes.SERVER_BACKUP, checkActive)

    fun decryptMediaFile(
        input: Path,
        output: Path,
        checkActive: () -> Unit = {},
    ) = decryptFile(input, output, PayloadPrefixes.SERVER_MEDIA, checkActive)

    private fun decryptFile(
        input: Path,
        output: Path,
        expectedPrefix: ByteArray,
        checkActive: () -> Unit,
    ) {
        Files.newInputStream(input).buffered().use { source ->
            val prefix = source.readNBytes(256)
            val header = PayloadHeaderCodec.decode(prefix, expectedPrefix)
            val key = keyring.getKey(header.keyId) ?: throw EncryptionException("Decryption key is unavailable")
            // Authentication completes before the staging output can be served.
            Files.newInputStream(input).buffered().use { encryptedSource ->
                encryptedSource.skipNBytes(header.ciphertextOffset.toLong())
                Files.newOutputStream(output).buffered().use { destination ->
                    transformBackup(encryptedSource, destination, key.keyBytes, header.iv, encrypt = false, checkActive = checkActive)
                }
            }
        }
    }

    private fun transformBackup(
        source: java.io.InputStream,
        destination: java.io.OutputStream,
        key: ByteArray,
        iv: ByteArray,
        encrypt: Boolean,
        checkActive: () -> Unit,
    ) {
        val engine = GCMBlockCipher.newInstance(AESEngine.newInstance())
        engine.init(encrypt, AEADParameters(KeyParameter(key), 128, iv))
        val input = ByteArray(BUFFER_SIZE)
        val output = ByteArray(BUFFER_SIZE + 32)
        while (true) {
            checkActive()
            val read = source.read(input)
            if (read == -1) break
            val produced = engine.processBytes(input, 0, read, output, 0)
            if (produced > 0) destination.write(output, 0, produced)
        }
        checkActive()
        val finalBytes = engine.doFinal(output, 0)
        if (finalBytes > 0) destination.write(output, 0, finalBytes)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }

    fun encryptMedia(
        plaintext: ByteArray,
        userId: String,
        mediaId: String,
        contentId: String,
    ): ByteArray {
        val activeKey = keyring.getActiveKey()
        val iv = cipher.generateIV()

        val ciphertext = cipher.encrypt(plaintext, activeKey.keyBytes, iv, null)
        return PayloadHeaderCodec.encode(PayloadPrefixes.SERVER_MEDIA, activeKey.keyId, iv, ciphertext)
    }

    fun decryptMedia(payload: ByteArray): ByteArray {
        val header = PayloadHeaderCodec.decode(payload, PayloadPrefixes.SERVER_MEDIA)
        val key =
            keyring.getKey(header.keyId)
                ?: throw EncryptionException("Key not found: ${header.keyId}")

        val ciphertext = payload.copyOfRange(header.ciphertextOffset, payload.size)
        return cipher.decrypt(ciphertext, key.keyBytes, header.iv, null)
    }

    fun encryptBackup(
        plaintext: ByteArray,
        userId: String,
        backupId: String,
    ): ByteArray {
        val activeKey = keyring.getActiveKey()
        val iv = cipher.generateIV()

        val ciphertext = cipher.encrypt(plaintext, activeKey.keyBytes, iv, null)
        return PayloadHeaderCodec.encode(PayloadPrefixes.SERVER_BACKUP, activeKey.keyId, iv, ciphertext)
    }

    fun decryptBackup(payload: ByteArray): ByteArray {
        val header = PayloadHeaderCodec.decode(payload, PayloadPrefixes.SERVER_BACKUP)
        val key =
            keyring.getKey(header.keyId)
                ?: throw EncryptionException("Key not found: ${header.keyId}")

        val ciphertext = payload.copyOfRange(header.ciphertextOffset, payload.size)
        return cipher.decrypt(ciphertext, key.keyBytes, header.iv, null)
    }
}

class EncryptionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
