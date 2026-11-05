package app.logdate.space.adoption

import app.logdate.space.SealingKeys
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.JsonElement
import studio.hypertext.atproto.lexicon.data.Bytes

public data class IdentityAdoptionCommitments(
    public val pending: Bytes,
    public val conflicts: Bytes,
)

/** Keyed commitments keep local edits out of manifests, including unkeyed plaintext hashes. */
public object IdentityAdoptionLocalCommitments {
    public suspend fun make(
        keys: SealingKeys,
        pending: List<JsonElement>,
        conflicts: List<JsonElement>,
    ): IdentityAdoptionCommitments {
        val key =
            CryptographyProvider.Default
                .get(HKDF)
                .secretDerivation(
                    SHA256,
                    32.bytes,
                    "app.logdate.identity-adoption".encodeToByteArray(),
                    "local-state-commitment v1".encodeToByteArray(),
                ).deriveSecretToByteArray(keys.indexKey)
        return IdentityAdoptionCommitments(commit(key, "pending", pending), commit(key, "conflicts", conflicts))
    }

    private suspend fun commit(
        key: ByteArray,
        domain: String,
        values: List<JsonElement>,
    ): Bytes {
        val generator =
            CryptographyProvider.Default
                .get(HMAC)
                .keyDecoder(SHA256)
                .decodeFromByteArray(HMAC.Key.Format.RAW, key)
                .signatureGenerator()
        return generator.createSignFunction().use { function ->
            function.update(IdentityAdoptionEncoding.domain(domain))
            for (value in values) function.update(IdentityAdoptionEncoding.frame(IdentityAdoptionEncoding.encode(value)))
            Bytes(function.signToByteArray())
        }
    }
}
