package app.logdate.server.identity.adoption

import kotlin.time.Instant

internal data class IdentityAdoptionVerificationRequest(
    val manifest: ByteArray,
    val coverageDigest: String,
) {
    override fun toString(): String = "IdentityAdoptionVerificationRequest()"
}

internal data class IdentityAdoptionVerifiedCopy(
    val operation: IdentityAdoptionOperation,
    val sourceRevision: Long,
    val generation: Long,
    val manifestDigest: String,
    val coverageDigest: String,
    val sourceSequence: Long,
    val verifiedAt: Instant,
) {
    override fun toString(): String = "IdentityAdoptionVerifiedCopy()"
}
