package app.logdate.client.device.auth

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64

/** A new proof for the app-to-LogDate boundary, independent of the provider's OAuth PKCE proof. */
class BlueskyAppProof private constructor(
    val verifier: String,
    val challenge: String,
) {
    override fun toString(): String = "BlueskyAppProof()"

    companion object {
        fun create(): BlueskyAppProof {
            val verifier = Base64.UrlSafe.encode(CryptographyRandom.nextBytes(32)).trimEnd('=')
            return BlueskyAppProof(verifier, challengeFor(verifier))
        }

        internal fun challengeFor(verifier: String): String =
            Base64.UrlSafe
                .encode(
                    CryptographyProvider.Default
                        .get(SHA256)
                        .hasher()
                        .hashBlocking(verifier.encodeToByteArray()),
                ).trimEnd('=')
    }
}
