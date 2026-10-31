@file:Suppress("DEPRECATION")

package app.logdate.client.repository.assistants

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * What a connected assistant may see or do. None of these lets an assistant author journal entries:
 * [START_CAPTURE] only opens LogDate so the person can record or write it themselves.
 */
@Serializable
enum class AssistantCapability {
    /** Entry text and transcripts, limited to the journals in the grant's content scope. */
    @SerialName("memories:read")
    READ_MEMORIES,

    /** Places and the person's location history. */
    @SerialName("places:read")
    READ_PLACES,

    /** People who come up in the person's entries. */
    @SerialName("people:read")
    READ_PEOPLE,

    /** Opens a new voice note or entry in LogDate for the person to finish. */
    @SerialName("capture:start")
    START_CAPTURE,
}

/** Device-held consent. Changes to any permission require a new revision. */
data class AssistantGrant(
    val id: Uuid,
    val revision: Long,
    val accountId: Uuid,
    val accountServerOrigin: String,
    val clientId: String,
    val deviceId: String,
    val resource: String,
    val capabilities: Set<AssistantCapability>,
    val contentScope: AssistantContentScope,
    val approvedAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
) {
    override fun toString(): String = "AssistantGrant([redacted])"
}
