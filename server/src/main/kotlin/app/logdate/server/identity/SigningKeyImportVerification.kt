package app.logdate.server.identity

import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey

/** Verify a fresh local challenge before any key material can be prepared or activated. */
internal fun verifyImportedSigningKey(
    privateKey: PrivateKey,
    publicKeyMultibase: String,
    algorithm: String,
    challenge: ByteArray,
) {
    val matches =
        try {
            val curve = EcCurve.fromSigningKeyAlgorithm(algorithm)
            val publicKey = EcKeySupport.decodePublicKey(publicKeyMultibase)
            val privateParameters = (privateKey as? ECPrivateKey)?.params
            val publicParameters = publicKey.publicKey.params
            curve != null &&
                publicKey.curve == curve &&
                privateParameters != null &&
                privateParameters.curve == publicParameters.curve &&
                privateParameters.generator == publicParameters.generator &&
                privateParameters.order == publicParameters.order &&
                privateParameters.cofactor == publicParameters.cofactor &&
                EcKeySupport.verifySha256(
                    publicKey.publicKey,
                    curve,
                    challenge,
                    EcKeySupport.signSha256(privateKey, curve, challenge),
                )
        } catch (_: Exception) {
            false
        }
    if (!matches) throw IdentityLifecycleValidationException("The imported key does not match its public key")
}
