package app.logdate.client.domain.assistants

import app.logdate.client.repository.assistants.AssistantAccessSnapshot
import app.logdate.client.repository.assistants.AssistantAccountLease
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantContentScope
import app.logdate.client.repository.assistants.AssistantDeviceAuthority
import app.logdate.client.repository.assistants.AssistantGrant
import app.logdate.client.repository.assistants.AssistantRequestAuthority
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object AssistantAuthorityFixture {
    val now = Instant.parse("2026-10-20T12:00:00Z")
    val journalId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    val otherId = Uuid.parse("00000000-0000-0000-0000-000000000002")
    val grant =
        AssistantGrant(
            id = Uuid.parse("00000000-0000-0000-0000-000000000003"),
            revision = 1,
            accountId = Uuid.parse("00000000-0000-0000-0000-000000000004"),
            accountServerOrigin = "https://accounts.logdate.test",
            clientId = "approved-assistant",
            deviceId = "serving-desktop",
            resource = "https://device.logdate.test/mcp",
            capabilities = setOf(AssistantCapability.READ_MEMORIES),
            contentScope = AssistantContentScope(setOf(journalId)),
            approvedAt = now - 1.days,
            expiresAt = now + 29.days,
        )
    val request =
        AssistantRequestAuthority(
            grantId = grant.id,
            grantRevision = grant.revision,
            accountId = grant.accountId,
            accountServerOrigin = grant.accountServerOrigin,
            clientId = grant.clientId,
            deviceId = grant.deviceId,
            resource = grant.resource,
            capabilities = setOf(AssistantCapability.READ_MEMORIES),
            issuedAt = now - 1.seconds,
            expiresAt = now + 299.seconds,
        )
    val lease =
        AssistantAccountLease(
            accountId = grant.accountId,
            accountServerOrigin = grant.accountServerOrigin,
            deviceId = grant.deviceId,
            issuedAt = now - 1.seconds,
            expiresAt = now + 59.seconds,
            deviceActive = true,
        )
    val device =
        AssistantDeviceAuthority(
            accountId = grant.accountId,
            accountServerOrigin = grant.accountServerOrigin,
            deviceId = grant.deviceId,
            resource = grant.resource,
            enabled = true,
            unlocked = true,
            paused = false,
            accessEpoch = 0,
            accountLease = lease,
            leaseAge = 1.seconds,
        )
    val snapshot = AssistantAccessSnapshot(grant, device)

    fun freshAt(time: Instant) =
        device.copy(
            accountLease = lease.copy(issuedAt = time, expiresAt = time + 1.minutes),
            leaseAge = 0.seconds,
        )
}
