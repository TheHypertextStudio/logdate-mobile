@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import kotlinx.serialization.Serializable
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Server-issued proposal binding, not journal authority or proof of a registered device key. */
@Serializable
internal data class IdentityAdoptionOAuthBinding(
    val accountId: Uuid,
    val sourceDid: String,
    val sourceEpoch: Long,
    val deviceId: String,
    val proposalId: Uuid,
) {
    override fun toString(): String = "IdentityAdoptionOAuthBinding()"
}

internal data class VerifiedIdentityAdoptionOAuth(
    val did: AtprotoDid,
    val binding: IdentityAdoptionOAuthBinding,
    val expiresAt: Instant,
) {
    override fun toString(): String = "VerifiedIdentityAdoptionOAuth()"
}
