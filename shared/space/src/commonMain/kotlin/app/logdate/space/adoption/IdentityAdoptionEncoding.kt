package app.logdate.space.adoption

import app.logdate.space.SealedPayloadCbor
import app.logdate.space.SealedPayloadSizing
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.operations.HashFunction
import kotlinx.serialization.json.JsonElement
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.lexicon.data.LexiconData
import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.coroutines.cancellation.CancellationException

internal object IdentityAdoptionEncoding {
    fun encode(value: JsonElement): ByteArray =
        try {
            SealedPayloadSizing.check(value)
            DagCborCodec.encode(value).also(SealedPayloadCbor::validate)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)
        }

    fun bytes(value: Bytes): JsonElement = LexiconData.json.encodeToJsonElement(Bytes.serializer(), value)

    fun domain(domain: String): ByteArray = "app.logdate.identity-adoption.$domain|1\u0000".encodeToByteArray()

    fun frame(value: ByteArray): ByteArray =
        ByteArray(8) { index ->
            (value.size.toLong() ushr ((7 - index) * 8)).toByte()
        } + value
}

internal class IdentityAdoptionEvidenceHash(
    domain: String,
) : AutoCloseable {
    private var function: HashFunction? =
        CryptographyProvider.Default.get(SHA256).hasher().createHashFunction().also {
            it.update(IdentityAdoptionEncoding.domain(domain))
        }

    fun checkOpen() {
        if (function == null) throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)
    }

    fun append(value: JsonElement) {
        checkOpen()
        val bytes = IdentityAdoptionEncoding.frame(IdentityAdoptionEncoding.encode(value))
        try {
            requireNotNull(function).update(bytes)
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (_: Exception) {
            close()
            throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)
        }
    }

    fun finish(): Bytes {
        checkOpen()
        return try {
            Bytes(requireNotNull(function).hashToByteArray())
        } finally {
            close()
        }
    }

    override fun close() {
        function?.close()
        function = null
    }
}
