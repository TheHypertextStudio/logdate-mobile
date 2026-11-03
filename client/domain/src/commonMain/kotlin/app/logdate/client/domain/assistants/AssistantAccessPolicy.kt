@file:Suppress("DEPRECATION")

package app.logdate.client.domain.assistants

import app.logdate.client.repository.assistants.AssistantAccessSnapshot
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantDeviceAuthority
import app.logdate.client.repository.assistants.AssistantGrant
import app.logdate.client.repository.assistants.AssistantRequestAuthority
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class AssistantAccessDenial {
    UNAVAILABLE,
    LOCKED,
    PAUSED,
    INVALID_AUTHORITY,
    EXPIRED,
    REVOKED,
    INSUFFICIENT_SCOPE,
    FRESHNESS_UNAVAILABLE,
}

sealed interface AssistantAccessDecision {
    data object Allowed : AssistantAccessDecision

    data class Denied(
        val reason: AssistantAccessDenial,
    ) : AssistantAccessDecision
}

class AssistantAccessPolicy {
    fun authorize(
        snapshot: AssistantAccessSnapshot,
        request: AssistantRequestAuthority,
        capability: AssistantCapability,
        now: Instant?,
    ): AssistantAccessDecision {
        val device = snapshot.device
        if (!device.enabled) return denied(AssistantAccessDenial.UNAVAILABLE)
        if (!device.unlocked) return denied(AssistantAccessDenial.LOCKED)
        if (device.paused) return denied(AssistantAccessDenial.PAUSED)
        val grant = snapshot.grant ?: return denied(AssistantAccessDenial.INVALID_AUTHORITY)
        if (grant.revokedAt != null) return denied(AssistantAccessDenial.REVOKED)
        if (now == null || !bindingsMatch(grant, device, request)) {
            return denied(AssistantAccessDenial.INVALID_AUTHORITY)
        }
        if (!validInterval(grant.approvedAt, grant.expiresAt, now, 30.days) ||
            !validInterval(request.issuedAt, request.expiresAt, now, 5.minutes) ||
            request.issuedAt < grant.approvedAt
        ) {
            return denied(AssistantAccessDenial.INVALID_AUTHORITY)
        }
        if (now >= grant.expiresAt || now >= request.expiresAt) return denied(AssistantAccessDenial.EXPIRED)
        if (capability !in grant.capabilities || capability !in request.capabilities) {
            return denied(AssistantAccessDenial.INSUFFICIENT_SCOPE)
        }
        if (!freshAccount(device, now)) return denied(AssistantAccessDenial.FRESHNESS_UNAVAILABLE)
        return AssistantAccessDecision.Allowed
    }

    private fun bindingsMatch(
        grant: AssistantGrant,
        device: AssistantDeviceAuthority,
        request: AssistantRequestAuthority,
    ): Boolean =
        grant.revision > 0 &&
            grant.accountServerOrigin.isNotBlank() &&
            grant.clientId.isNotBlank() &&
            grant.deviceId.isNotBlank() &&
            grant.resource.isNotBlank() &&
            request.grantId == grant.id &&
            request.grantRevision == grant.revision &&
            request.accountId == grant.accountId &&
            device.accountId == grant.accountId &&
            request.accountServerOrigin == grant.accountServerOrigin &&
            device.accountServerOrigin == grant.accountServerOrigin &&
            request.clientId == grant.clientId &&
            request.deviceId == grant.deviceId &&
            device.deviceId == grant.deviceId &&
            request.resource == grant.resource &&
            device.resource == grant.resource

    private fun validInterval(
        issued: Instant,
        expires: Instant,
        now: Instant,
        maximum: Duration,
    ): Boolean = issued <= now && expires > issued && expires - issued <= maximum

    private fun freshAccount(
        device: AssistantDeviceAuthority,
        now: Instant,
    ): Boolean {
        val lease = device.accountLease ?: return false
        val age = device.leaseAge ?: return false
        return age >= Duration.ZERO &&
            age < 60.seconds &&
            lease.deviceActive &&
            lease.accountId == device.accountId &&
            lease.accountServerOrigin == device.accountServerOrigin &&
            lease.deviceId == device.deviceId &&
            validInterval(lease.issuedAt, lease.expiresAt, now, 60.seconds) &&
            now < lease.expiresAt
    }

    private fun denied(reason: AssistantAccessDenial) = AssistantAccessDecision.Denied(reason)
}
