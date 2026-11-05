package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerificationRequest
import app.logdate.space.adoption.IdentityAdoptionManifest
import app.logdate.space.adoption.identityAdoptionManifestFromCanonicalBytes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.coroutines.cancellation.CancellationException

internal data class PreparedIdentityAdoptionVerification(
    val bytes: ByteArray,
    val manifest: IdentityAdoptionManifest,
    val manifestDigest: String,
    val coverageDigest: String,
    val bodyDigest: String,
)

internal fun prepareIdentityAdoptionVerification(request: IdentityAdoptionVerificationRequest): PreparedIdentityAdoptionVerification {
    stagingCheck(
        request.manifest.size in 1..16384 && request.coverageDigest.matches(Regex("[0-9a-f]{64}")),
        IdentityAdoptionStagingFailure.INVALID_REQUEST,
    )
    val bytes = request.manifest.copyOf()
    val manifest =
        try {
            identityAdoptionManifestFromCanonicalBytes(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.INVALID_REQUEST)
        }
    val digest = stagingHash(bytes)
    val bodyDigest = identityAdoptionVerificationDigest(digest, request.coverageDigest)
    return PreparedIdentityAdoptionVerification(bytes, manifest, digest, request.coverageDigest, bodyDigest)
}

internal fun identityAdoptionVerificationDigest(
    manifestDigest: String,
    coverageDigest: String,
): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.verify")
                put("manifestDigest", manifestDigest)
                put("coverageDigest", coverageDigest)
            },
        ),
    )
