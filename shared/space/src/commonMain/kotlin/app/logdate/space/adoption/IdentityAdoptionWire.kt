package app.logdate.space.adoption

import app.logdate.lexicon.app.logdate.crypto.Sealed
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.lexicon.data.LexiconData
import kotlin.coroutines.cancellation.CancellationException

/** Adoption accepts raw records so a generated serializer cannot silently discard future wire fields. */
internal object IdentityAdoptionWire {
    private val fields = setOf("\$type", "version", "keyId", "nonce", "ciphertext")

    fun envelope(value: JsonObject): Sealed {
        check(value.keys == fields)
        check((value["\$type"] as? JsonPrimitive)?.let { it.isString && it.content == Sealed.TYPE } == true)
        check((value["version"] as? JsonPrimitive)?.let { !it.isString && it.longOrNull == 1L } == true)
        check((value["keyId"] as? JsonPrimitive)?.isString == true)
        IdentityAdoptionEncoding.encode(value)
        return LexiconData.json.decodeFromJsonElement(Sealed.serializer(), value)
    }

    suspend fun digest(value: JsonObject): Bytes =
        Bytes(
            CryptographyProvider.Default
                .get(SHA256)
                .hasher()
                .hash(IdentityAdoptionEncoding.encode(value)),
        )

    fun check(valid: Boolean) {
        if (!valid) throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)
    }

    suspend fun <T> protect(action: suspend () -> T): T =
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IdentityAdoptionException) {
            throw error
        } catch (_: Exception) {
            throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)
        }
}
