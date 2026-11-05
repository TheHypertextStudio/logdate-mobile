package app.logdate.server.identity.adoption

import app.logdate.server.logdate.LogDateBlobStorage
import kotlin.time.Clock
import kotlin.time.Instant

internal interface IdentityAdoptionBlobRepository {
    suspend fun admit(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        cid: String,
        now: Instant,
    ): IdentityAdoptionBlobAdmission?

    suspend fun complete(
        operation: IdentityAdoptionOperation,
        verified: IdentityAdoptionVerifiedBlob,
        now: Instant,
    ): Boolean
}

internal data class IdentityAdoptionMediaVerification(
    val operationId: String,
    val admissionId: String,
    val generation: Long,
    val cid: String,
    val size: Long,
    val mimeType: String,
)

/** The provider read belongs between the durable admission and a fresh-time SQL acknowledgement. */
internal class IdentityAdoptionMediaService(
    private val repository: IdentityAdoptionBlobRepository,
    private val storage: LogDateBlobStorage,
    private val clock: Clock = Clock.System,
) {
    suspend fun verify(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        cid: String,
    ): IdentityAdoptionMediaVerification? {
        val admission = repository.admit(operation, challenge, signature, cid, clock.now()) ?: return null
        val verified = IdentityAdoptionVerifiedBlob.read(admission, storage) ?: return null
        if (!repository.complete(operation, verified, clock.now())) return null
        return IdentityAdoptionMediaVerification(
            admission.operationId,
            admission.id,
            admission.generation,
            admission.cid,
            admission.size,
            admission.mimeType,
        )
    }
}
