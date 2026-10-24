package app.logdate.server.crypto

import java.nio.file.Files
import java.nio.file.Path
import app.logdate.server.logdate.copyBackupFile as copyBackupStream

class EncryptionService(
    private val policy: EncryptionPolicy,
    private val codec: PayloadCodec,
) {
    fun processBackupUpload(
        input: Path,
        output: Path,
        userId: String,
        backupId: String,
        checkActive: () -> Unit = {},
    ): Boolean {
        val prefix = Files.newInputStream(input).use { it.readNBytes(5) }
        val decision = policy.evaluate(prefix)
        return when (decision) {
            is PolicyDecision.Reject -> {
                throw EncryptionPolicyException(decision.reason)
            }

            PolicyDecision.EncryptAtRest -> {
                codec.encryptBackupFile(input, output, checkActive)
                true
            }

            PolicyDecision.AcceptPlaintext -> {
                copyBackupFile(input, output, checkActive)
                false
            }

            PolicyDecision.AcceptClientCiphertext, PolicyDecision.AcceptServerCiphertext -> {
                copyBackupFile(input, output, checkActive)
                true
            }
        }
    }

    fun processBackupDownload(
        input: Path,
        output: Path,
        shouldDecrypt: Boolean,
        checkActive: () -> Unit = {},
    ) {
        val prefix = Files.newInputStream(input).use { it.readNBytes(PayloadPrefixes.SERVER_BACKUP.size) }
        if (shouldDecrypt && prefix.hasPrefix(PayloadPrefixes.SERVER_BACKUP)) {
            try {
                codec.decryptBackupFile(input, output, checkActive)
            } catch (error: Exception) {
                Files.deleteIfExists(output)
                throw error
            }
        } else {
            copyBackupFile(input, output, checkActive)
        }
    }

    private fun copyBackupFile(
        input: Path,
        output: Path,
        checkActive: () -> Unit,
    ) {
        Files.newInputStream(input).use { source ->
            Files.newOutputStream(output).use { destination ->
                copyBackupStream(source, destination, checkActive)
            }
        }
    }

    fun processMediaUpload(
        payload: ByteArray,
        userId: String,
        mediaId: String,
        contentId: String,
    ): ProcessedPayload {
        val decision = policy.evaluate(payload)
        return applyUploadDecision(decision, payload) {
            codec.encryptMedia(payload, userId, mediaId, contentId)
        }
    }

    fun processMediaDownload(
        payload: ByteArray,
        shouldDecrypt: Boolean,
    ): ByteArray {
        if (!shouldDecrypt) return payload
        return if (payload.hasPrefix(PayloadPrefixes.SERVER_MEDIA)) {
            codec.decryptMedia(payload)
        } else {
            payload
        }
    }

    fun processBackupUpload(
        payload: ByteArray,
        userId: String,
        backupId: String,
    ): ProcessedPayload {
        val decision = policy.evaluate(payload)
        return applyUploadDecision(decision, payload) {
            codec.encryptBackup(payload, userId, backupId)
        }
    }

    fun processBackupDownload(
        payload: ByteArray,
        shouldDecrypt: Boolean,
    ): ByteArray {
        if (!shouldDecrypt) return payload
        return if (payload.hasPrefix(PayloadPrefixes.SERVER_BACKUP)) {
            codec.decryptBackup(payload)
        } else {
            payload
        }
    }

    private fun applyUploadDecision(
        decision: PolicyDecision,
        payload: ByteArray,
        encrypt: () -> ByteArray,
    ): ProcessedPayload =
        when (decision) {
            is PolicyDecision.Reject -> throw EncryptionPolicyException(decision.reason)
            PolicyDecision.AcceptPlaintext -> ProcessedPayload(payload, encrypted = false)
            PolicyDecision.AcceptClientCiphertext -> ProcessedPayload(payload, encrypted = true)
            PolicyDecision.AcceptServerCiphertext -> ProcessedPayload(payload, encrypted = true)
            PolicyDecision.EncryptAtRest -> ProcessedPayload(encrypt(), encrypted = true)
        }

    companion object {
        fun fromEnvironment(): EncryptionService {
            val policy = EncryptionPolicy.fromEnvironment()
            val keyring =
                EnvironmentKeyring.fromEnvironmentOrNull()
                    ?: NoOpKeyring
            val codec = PayloadCodec(keyring)
            return EncryptionService(policy, codec)
        }
    }
}

data class ProcessedPayload(
    val data: ByteArray,
    val encrypted: Boolean,
)

class EncryptionPolicyException(
    message: String,
) : Exception(message)
