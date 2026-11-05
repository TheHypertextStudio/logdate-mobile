@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import app.logdate.shared.model.PasskeyAuthenticationOptions
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal data class IdentityAdoptionDecision(
    val id: Uuid,
    val operationId: String,
    val accountId: Uuid,
    val revision: Long,
    val bindingHash: String,
    val challengeHash: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val authorizedAt: Instant? = null,
    val consumedAt: Instant? = null,
    val deviceKey: IdentityAdoptionDeviceKey? = null,
) {
    fun matchesChallenge(challenge: String): Boolean =
        challenge.length <= 512 &&
            MessageDigest.isEqual(
                challengeHash.encodeToByteArray(),
                identityAdoptionChallengeDigest(challenge).encodeToByteArray(),
            )
}

internal interface IdentityAdoptionDecisionRepository {
    suspend fun create(
        id: Uuid,
        operation: IdentityAdoptionOperation,
        challenge: String,
        now: Instant,
        deviceKey: IdentityAdoptionDeviceKey? = null,
    ): IdentityAdoptionDecision?

    suspend fun get(
        id: Uuid,
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionDecision?

    suspend fun authorize(
        id: Uuid,
        operation: IdentityAdoptionOperation,
        challenge: String,
        now: Instant,
    ): Boolean
}

internal data class IdentityAdoptionDecisionChallenge(
    val decisionId: Uuid,
    val operationId: String,
    val expectedRevision: Long,
    val options: PasskeyAuthenticationOptions,
    val expiresAt: Instant,
    val deviceKey: IdentityAdoptionDeviceKey? = null,
)

internal fun identityAdoptionChallengeDigest(challenge: String): String =
    HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
            ("app.logdate.identity-adoption.challenge|1:" + challenge).encodeToByteArray(),
        ),
    )

internal fun identityAdoptionDecisionBindingDigest(
    operation: IdentityAdoptionOperation,
    deviceKey: IdentityAdoptionDeviceKey?,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("app.logdate.identity-adoption.decision|1:".encodeToByteArray())
    val binding = operation.binding
    val fields =
        listOf(
            operation.id,
            binding.accountId.toString(),
            operation.revision.toString(),
            binding.sourceDid,
            binding.targetDid,
            binding.deviceId,
            binding.sourceEpoch.toString(),
        ) + if (deviceKey == null) emptyList() else listOf("device-key-v1", deviceKey.origin, deviceKey.publicKey)
    for (field in fields) {
        val bytes = field.encodeToByteArray()
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return HexFormat.of().formatHex(digest.digest())
}
