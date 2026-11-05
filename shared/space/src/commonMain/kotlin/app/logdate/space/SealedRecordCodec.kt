package app.logdate.space

import app.logdate.lexicon.app.logdate.crypto.Sealed
import app.logdate.lexicon.app.logdate.crypto.SealedPayload
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.operations.IvAuthenticatedCipher
import dev.whyoleg.cryptography.random.CryptographyRandom
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.lexicon.data.LexiconData
import kotlin.coroutines.cancellation.CancellationException

/** A record ready to store: its opaque key and its envelope. */
public data class SealedWrite(
    val rkey: String,
    val record: Sealed,
)

/**
 * A sealed record that can't be produced or opened. Its message never contains the record's
 * contents, and neither does any cause it carries.
 */
public class SealedRecordException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Seals records into `app.logdate.crypto.sealed` envelopes and opens them again.
 *
 * The payload is DAG-CBOR, padded with [SealedPadding] and encrypted with AES-256-GCM under the
 * space's content key and a random 96-bit nonce. The envelope is bound to its space, author and
 * sealed record key through the AES-GCM additional data, so a server that moves an envelope
 * anywhere else makes it fail to open.
 */
public object SealedRecordCodec {
    private const val VERSION = 1L
    private const val NONCE = 12
    private const val TAG = 16
    private const val SEALED_RKEY_LENGTH = 26

    /**
     * The opaque key a record is stored under: HMAC-SHA256 of `{collection}/{rkey}` under the
     * index key, in base32, cut to 26 characters. The server can't recover the inner key from it.
     */
    public suspend fun sealedRkey(
        keys: SealingKeys,
        collection: String,
        rkey: String,
    ): String = Base32.encode(SealingKeys.hmac(keys.indexKey, "$collection/$rkey".encodeToByteArray())).take(SEALED_RKEY_LENGTH)

    /** Seals [payload] for [space], written by [author]. */
    public suspend fun seal(
        keys: SealingKeys,
        space: SpaceRef,
        author: String,
        payload: SealedPayload,
    ): SealedWrite = sealWithNonce(keys, space, author, payload, CryptographyRandom.nextBytes(NONCE))

    /**
     * Seals [payload] under the given [nonce]. Only known-answer tests choose the nonce: reusing one
     * under the same key breaks AES-GCM, so [seal] always draws a fresh random one.
     */
    internal suspend fun sealWithNonce(
        keys: SealingKeys,
        space: SpaceRef,
        author: String,
        payload: SealedPayload,
        nonce: ByteArray,
    ): SealedWrite {
        val rkey = sealedRkey(keys, payload.collection, payload.rkey)
        val encoded =
            try {
                LexiconData.encodeToDagCbor(SealedPayload.serializer(), payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The cause would quote the offending value, so it isn't attached.
                throw SealedRecordException("The record holds a value a sealed record can't encode (${e::class.simpleName})")
            }
        return SealedWrite(rkey, encrypt(keys, space, author, rkey, SealedPadding.pad(encoded), nonce))
    }

    /**
     * Opens [record], stored under [sealedRkey] in [space] by [author]. Throws
     * [SealedRecordException] when the envelope's version or key is unknown, it fails
     * authentication, its padding or payload is malformed, or [sealedRkey] isn't the key its
     * payload derives.
     */
    @OptIn(DelicateCryptographyApi::class)
    public suspend fun open(
        keys: SealingKeys,
        space: SpaceRef,
        author: String,
        sealedRkey: String,
        record: Sealed,
    ): SealedPayload {
        val plaintext = decryptPadded(keys, space, author, sealedRkey, record)
        val payload =
            try {
                LexiconData.decodeFromDagCbor(SealedPayload.serializer(), SealedPadding.unpad(plaintext))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SealedRecordException) {
                throw e
            } catch (e: Exception) {
                // The cause could quote the decrypted payload, so it isn't attached.
                throw SealedRecordException("The sealed payload isn't a valid record (${e::class.simpleName})")
            }
        if (sealedRkey(keys, payload.collection, payload.rkey) != sealedRkey) {
            throw SealedRecordException("The sealed key doesn't match the record inside")
        }
        return payload
    }

    @OptIn(DelicateCryptographyApi::class)
    internal suspend fun decryptPadded(
        keys: SealingKeys,
        space: SpaceRef,
        author: String,
        sealedRkey: String,
        record: Sealed,
    ): ByteArray {
        if (record.version != VERSION) throw SealedRecordException("Envelope version ${record.version} isn't supported")
        if (record.keyId != keys.keyId) throw SealedRecordException("The record was sealed with another key")
        if (record.nonce.size != NONCE) throw SealedRecordException("The envelope's nonce isn't $NONCE bytes")
        if (!SealedPadding.isPaddedSize(record.ciphertext.size - TAG)) {
            throw SealedRecordException("The envelope's ciphertext isn't a sealed size")
        }
        return try {
            cipher(keys).decryptWithIv(record.nonce.toByteArray(), record.ciphertext.toByteArray(), aad(space, author, sealedRkey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SealedRecordException("The sealed record failed authentication", e)
        }
    }

    /** Encrypts already padded [plaintext] into an envelope stored under [sealedRkey]. */
    @OptIn(DelicateCryptographyApi::class)
    internal suspend fun encrypt(
        keys: SealingKeys,
        space: SpaceRef,
        author: String,
        sealedRkey: String,
        plaintext: ByteArray,
        nonce: ByteArray = CryptographyRandom.nextBytes(NONCE),
    ): Sealed {
        require(nonce.size == NONCE) { "The nonce must be $NONCE bytes" }
        val ciphertext = cipher(keys).encryptWithIv(nonce, plaintext, aad(space, author, sealedRkey))
        return Sealed(ciphertext = Bytes(ciphertext), keyId = keys.keyId, nonce = Bytes(nonce), version = VERSION)
    }

    private fun aad(
        space: SpaceRef,
        author: String,
        rkey: String,
    ): ByteArray = "app.logdate.crypto.sealed|$VERSION|${space.uri}|$author|$rkey".encodeToByteArray()

    private suspend fun cipher(keys: SealingKeys): IvAuthenticatedCipher =
        CryptographyProvider.Default
            .get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArray(AES.Key.Format.RAW, keys.contentKey)
            .cipher()
}
