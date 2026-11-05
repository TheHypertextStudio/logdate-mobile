@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.uuid.Uuid

internal data class IdentityAdoptionDeviceKey(
    val origin: String,
    val publicKey: String,
) {
    override fun toString(): String = "IdentityAdoptionDeviceKey()"
}

internal object IdentityAdoptionDeviceProof {
    fun message(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        challenge: String,
        key: IdentityAdoptionDeviceKey,
    ): ByteArray? =
        try {
            val binding = operation.binding
            require(operation.phase == IdentityAdoptionPhase.PREPARING && operation.revision > 0)
            require(Uuid.parse(operation.id).toString() == operation.id)
            require(binding.sourceEpoch in 0 until Long.MAX_VALUE && binding.sourceDid != binding.targetDid)
            require(listOf(binding.sourceDid, binding.targetDid).all { it.length <= 2048 && AtprotoDid.parse(it).isSuccess })
            require(binding.deviceId.isNotEmpty() && binding.deviceId.encodeToByteArray().size <= 128)
            require(binding.deviceId.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
            require(challenge.matches(Regex("[A-Za-z0-9_-]{43}")))
            val rawKey = decode(key.publicKey, 65)
            publicKey(rawKey)
            val output = ByteArrayOutputStream()
            output.write("app.logdate.identity-adoption.device-confirm|1\u0000".encodeToByteArray())
            for (field in listOf(
                operation.id,
                binding.accountId.toString(),
                origin(key.origin),
                binding.deviceId,
                binding.sourceDid,
                binding.targetDid,
                binding.sourceEpoch.toString(),
                decisionId.toString(),
                operation.revision.toString(),
                challenge,
            )) {
                output.frame(field.encodeToByteArray())
            }
            output.frame(rawKey)
            output.toByteArray()
        } catch (_: Exception) {
            null
        }

    fun verify(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        challenge: String,
        key: IdentityAdoptionDeviceKey,
        signature: String,
    ): Boolean = message(operation, decisionId, challenge, key)?.let { verifyMessage(it, key, signature) } ?: false

    fun verifyMessage(
        payload: ByteArray,
        key: IdentityAdoptionDeviceKey,
        signature: String,
    ): Boolean =
        try {
            EcKeySupport.verifySha256(
                publicKey(decode(key.publicKey, 65)),
                EcCurve.P256,
                payload,
                decode(signature, 64),
                allowHighS = true,
            )
        } catch (_: Exception) {
            false
        }

    private fun origin(value: String): String {
        require(value.length <= 2048)
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null)
        require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        val port = if (uri.port == 443) -1 else uri.port
        val canonical = URI("https", null, uri.host.lowercase(), port, null, null, null).toASCIIString()
        require(value == canonical)
        return canonical
    }

    private fun decode(
        value: String,
        size: Int,
    ): ByteArray {
        require(value.length == (size * 8 + 5) / 6 && value.matches(Regex("[A-Za-z0-9_-]+")))
        return Base64.getUrlDecoder().decode(value).also {
            require(it.size == size && Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value)
        }
    }

    private fun publicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte())
        return EcKeySupport.decodePublicKeyFromJwk(EcCurve.P256, raw.copyOfRange(1, 33), raw.copyOfRange(33, 65))
    }

    private fun ByteArrayOutputStream.frame(value: ByteArray) {
        write(ByteBuffer.allocate(4).putInt(value.size).array())
        write(value)
    }
}
