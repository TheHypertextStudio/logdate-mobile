package app.logdate.client.repository.assistants

import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Verified credential claims, constructed by the device issuer, never from tool arguments. */
data class AssistantRequestAuthority(
    val grantId: Uuid,
    val grantRevision: Long,
    val accountId: Uuid,
    val accountServerOrigin: String,
    val clientId: String,
    val deviceId: String,
    val resource: String,
    val capabilities: Set<AssistantCapability>,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

/** Authenticated account freshness; this is not a content grant or a local expiry clock. */
data class AssistantAccountLease(
    val accountId: Uuid,
    val accountServerOrigin: String,
    val deviceId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val deviceActive: Boolean,
)

data class AssistantDeviceAuthority(
    val accountId: Uuid?,
    val accountServerOrigin: String?,
    val deviceId: String,
    val resource: String,
    val enabled: Boolean,
    val unlocked: Boolean,
    val paused: Boolean,
    /** Advances whenever authority is lost, even if it is restored before the next snapshot. */
    val accessEpoch: Long,
    val accountLease: AssistantAccountLease?,
    /** Trusted monotonic age measured from freshness-request start, including network time. */
    val leaseAge: Duration?,
)

data class AssistantAccessSnapshot(
    val grant: AssistantGrant?,
    val device: AssistantDeviceAuthority,
)
