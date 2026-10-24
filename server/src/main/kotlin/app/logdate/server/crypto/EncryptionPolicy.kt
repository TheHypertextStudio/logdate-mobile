package app.logdate.server.crypto

class EncryptionPolicy(
    private val mode: EncryptionMode,
    private val serverEncryptionEnabled: Boolean,
    private val allowPassthroughClientCiphertext: Boolean,
) {
    fun evaluate(payload: ByteArray): PolicyDecision {
        val payloadType = detectPayloadType(payload)

        return when (mode) {
            EncryptionMode.E2EE_REQUIRED -> evaluateE2EEMode(payloadType)
            EncryptionMode.AT_REST_ONLY -> evaluateAtRestMode(payloadType)
        }
    }

    private fun evaluateE2EEMode(type: PayloadType): PolicyDecision =
        when (type) {
            PayloadType.PLAINTEXT -> PolicyDecision.Reject("E2EE required: plaintext not allowed")
            PayloadType.CLIENT_CIPHERTEXT -> PolicyDecision.AcceptClientCiphertext
            PayloadType.SERVER_CIPHERTEXT -> PolicyDecision.AcceptServerCiphertext
        }

    private fun evaluateAtRestMode(type: PayloadType): PolicyDecision =
        when (type) {
            PayloadType.PLAINTEXT -> {
                if (serverEncryptionEnabled) {
                    PolicyDecision.EncryptAtRest
                } else {
                    PolicyDecision.AcceptPlaintext
                }
            }

            PayloadType.CLIENT_CIPHERTEXT -> {
                if (allowPassthroughClientCiphertext) {
                    PolicyDecision.AcceptClientCiphertext
                } else {
                    PolicyDecision.EncryptAtRest
                }
            }

            PayloadType.SERVER_CIPHERTEXT -> {
                PolicyDecision.AcceptServerCiphertext
            }
        }

    private fun detectPayloadType(payload: ByteArray): PayloadType =
        when {
            payload.hasPrefix(PayloadPrefixes.CLIENT_MEDIA) -> PayloadType.CLIENT_CIPHERTEXT
            payload.hasPrefix(PayloadPrefixes.CLIENT_MEDIA_CHUNKED) -> PayloadType.CLIENT_CIPHERTEXT
            payload.hasPrefix(PayloadPrefixes.SERVER_MEDIA) -> PayloadType.SERVER_CIPHERTEXT
            payload.hasPrefix(PayloadPrefixes.SERVER_BACKUP) -> PayloadType.SERVER_CIPHERTEXT
            else -> PayloadType.PLAINTEXT
        }

    private enum class PayloadType {
        PLAINTEXT,
        CLIENT_CIPHERTEXT,
        SERVER_CIPHERTEXT,
    }

    companion object {
        fun fromEnvironment(): EncryptionPolicy {
            val mode = EncryptionMode.fromEnvironment()
            val serverEncryptionEnabled = System.getenv("SERVER_ENCRYPTION_ENABLED")?.toBoolean() ?: true
            val allowPassthrough = System.getenv("ALLOW_PASSTHROUGH_CLIENT_CIPHERTEXT")?.toBoolean() ?: true

            return EncryptionPolicy(mode, serverEncryptionEnabled, allowPassthrough)
        }
    }
}

sealed class PolicyDecision {
    data class Reject(
        val reason: String,
    ) : PolicyDecision()

    object AcceptPlaintext : PolicyDecision()

    object AcceptClientCiphertext : PolicyDecision()

    object AcceptServerCiphertext : PolicyDecision()

    object EncryptAtRest : PolicyDecision()
}
