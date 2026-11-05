@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.uuid.Uuid

internal enum class IdentityAdoptionAction { HOLD, RENEW, STAGE_RECORDS, BEGIN_BLOB, FINALIZE_BLOB, READ_STAGED, VERIFY, COMMIT, CANCEL }

internal data class IdentityAdoptionActionChallenge(
    val id: Uuid,
    val nonce: String,
    val action: IdentityAdoptionAction,
    val bodyDigest: String,
)

internal object IdentityAdoptionActionProof {
    fun message(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        key: IdentityAdoptionDeviceKey,
    ): ByteArray? {
        if (!allowed(operation.phase, challenge.action) || !challenge.bodyDigest.matches(Regex("[0-9a-f]{64}"))) return null
        val binding =
            IdentityAdoptionDeviceProof.message(
                operation.copy(phase = IdentityAdoptionPhase.PREPARING),
                challenge.id,
                challenge.nonce,
                key,
            ) ?: return null
        val output = ByteArrayOutputStream()
        output.write("app.logdate.identity-adoption.action|1\u0000".encodeToByteArray())
        for (field in listOf(
            binding,
            operation.phase.name.encodeToByteArray(),
            challenge.action.name.encodeToByteArray(),
            challenge.bodyDigest.encodeToByteArray(),
        )) {
            output.write(ByteBuffer.allocate(4).putInt(field.size).array())
            output.write(field)
        }
        return output.toByteArray()
    }

    fun verify(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        key: IdentityAdoptionDeviceKey,
        signature: String,
    ): Boolean = message(operation, challenge, key)?.let { IdentityAdoptionDeviceProof.verifyMessage(it, key, signature) } ?: false

    private fun allowed(
        phase: IdentityAdoptionPhase,
        action: IdentityAdoptionAction,
    ): Boolean =
        when (action) {
            IdentityAdoptionAction.HOLD -> phase == IdentityAdoptionPhase.PREPARING
            IdentityAdoptionAction.RENEW -> phase == IdentityAdoptionPhase.FENCED || phase == IdentityAdoptionPhase.VERIFIED
            IdentityAdoptionAction.STAGE_RECORDS, IdentityAdoptionAction.BEGIN_BLOB,
            IdentityAdoptionAction.FINALIZE_BLOB,
            -> phase == IdentityAdoptionPhase.PREPARING || phase == IdentityAdoptionPhase.FENCED
            IdentityAdoptionAction.VERIFY -> phase == IdentityAdoptionPhase.FENCED
            IdentityAdoptionAction.READ_STAGED ->
                phase in setOf(IdentityAdoptionPhase.PREPARING, IdentityAdoptionPhase.FENCED, IdentityAdoptionPhase.VERIFIED)
            IdentityAdoptionAction.COMMIT -> phase == IdentityAdoptionPhase.VERIFIED
            IdentityAdoptionAction.CANCEL ->
                phase in
                    setOf(IdentityAdoptionPhase.PREPARING, IdentityAdoptionPhase.FENCED, IdentityAdoptionPhase.VERIFIED)
        }
}
