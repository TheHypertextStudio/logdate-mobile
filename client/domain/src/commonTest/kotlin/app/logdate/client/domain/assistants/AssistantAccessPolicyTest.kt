package app.logdate.client.domain.assistants

import app.logdate.client.repository.assistants.AssistantCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AssistantAccessPolicyTest {
    private val fixture = AssistantAuthorityFixture
    private val policy = AssistantAccessPolicy()

    @Test
    fun `valid live read authority is allowed`() {
        assertEquals(AssistantAccessDecision.Allowed, authorize())
    }

    @Test
    fun `a grant or token without read permission is refused`() {
        assertEquals(
            AssistantAccessDecision.Denied(AssistantAccessDenial.INSUFFICIENT_SCOPE),
            authorize(fixture.snapshot.copy(grant = fixture.grant.copy(capabilities = emptySet()))),
        )
        assertEquals(
            AssistantAccessDecision.Denied(AssistantAccessDenial.INSUFFICIENT_SCOPE),
            policy.authorize(
                fixture.snapshot,
                fixture.request.copy(capabilities = emptySet()),
                AssistantCapability.READ_MEMORIES,
                fixture.now,
            ),
        )
    }

    @Test
    fun `every credential binding must exactly match the stored grant`() {
        val request = fixture.request
        val mismatches =
            listOf(
                request.copy(grantId = fixture.otherId),
                request.copy(grantRevision = request.grantRevision + 1),
                request.copy(accountId = fixture.otherId),
                request.copy(accountServerOrigin = "https://other.logdate.test"),
                request.copy(clientId = "other-assistant"),
                request.copy(deviceId = "other-desktop"),
                request.copy(resource = request.resource + "/other"),
            )
        mismatches.forEachIndexed { index, changed ->
            assertIs<AssistantAccessDecision.Denied>(authorize(request = changed), "binding $index")
        }
    }

    @Test
    fun `current serving identity must match even when token and grant agree`() {
        val device = fixture.device
        listOf(
            device.copy(accountId = fixture.otherId),
            device.copy(accountId = null),
            device.copy(accountServerOrigin = "https://other.logdate.test"),
            device.copy(accountServerOrigin = null),
            device.copy(deviceId = "other-desktop"),
            device.copy(resource = device.resource + "/other"),
        ).forEachIndexed { index, changed ->
            assertIs<AssistantAccessDecision.Denied>(authorize(fixture.snapshot.copy(device = changed)), "device $index")
        }
    }

    @Test
    fun `missing revoked or invalid grant denies access`() {
        assertIs<AssistantAccessDecision.Denied>(authorize(fixture.snapshot.copy(grant = null)))
        listOf(
            fixture.grant.copy(revokedAt = fixture.now),
            fixture.grant.copy(revokedAt = fixture.now + 1.days),
            fixture.grant.copy(revision = 0),
        ).forEach { grant -> assertIs<AssistantAccessDecision.Denied>(authorize(fixture.snapshot.copy(grant = grant))) }
    }

    @Test
    fun `disabled paused and locked states deny access`() {
        val device = fixture.device
        mapOf(
            device.copy(enabled = false) to AssistantAccessDenial.UNAVAILABLE,
            device.copy(paused = true) to AssistantAccessDenial.PAUSED,
            device.copy(unlocked = false) to AssistantAccessDenial.LOCKED,
        ).forEach { (changed, reason) ->
            assertEquals(AssistantAccessDecision.Denied(reason), authorize(fixture.snapshot.copy(device = changed)))
        }
    }

    @Test
    fun `grant cannot exceed thirty days or start in the future`() {
        val grant = fixture.grant
        listOf(
            grant.copy(expiresAt = grant.approvedAt + 30.days + 1.seconds),
            grant.copy(approvedAt = fixture.now + 1.seconds),
            grant.copy(expiresAt = grant.approvedAt),
            grant.copy(expiresAt = grant.approvedAt - 1.seconds),
        ).forEachIndexed { index, changed ->
            assertIs<AssistantAccessDecision.Denied>(authorize(fixture.snapshot.copy(grant = changed)), "grant time $index")
        }
    }

    @Test
    fun `token cannot exceed five minutes or start in the future`() {
        val request = fixture.request
        listOf(
            request.copy(expiresAt = request.issuedAt + 5.minutes + 1.seconds),
            request.copy(issuedAt = fixture.now + 1.seconds),
            request.copy(expiresAt = request.issuedAt),
            request.copy(expiresAt = request.issuedAt - 1.seconds),
            request.copy(issuedAt = fixture.grant.approvedAt - 1.seconds, expiresAt = fixture.grant.approvedAt + 1.seconds),
        ).forEachIndexed { index, changed ->
            assertIs<AssistantAccessDecision.Denied>(authorize(request = changed), "token time $index")
        }
    }

    @Test
    fun `grant and token expiry equality deny despite freshly renewed account lease`() {
        listOf(fixture.grant.expiresAt, fixture.request.expiresAt).forEach { deadline ->
            assertIs<AssistantAccessDecision.Denied>(
                policy.authorize(
                    fixture.snapshot.copy(device = fixture.freshAt(deadline)),
                    fixture.request,
                    AssistantCapability.READ_MEMORIES,
                    deadline,
                ),
            )
        }
    }

    @Test
    fun `authority is valid immediately before its earliest expiry`() {
        val deadline = fixture.request.expiresAt
        val before = deadline - 1.seconds
        assertEquals(
            AssistantAccessDecision.Allowed,
            policy.authorize(
                fixture.snapshot.copy(device = fixture.freshAt(before)),
                fixture.request,
                AssistantCapability.READ_MEMORIES,
                before,
            ),
        )
    }

    @Test
    fun `lease identity activity and bounded timestamps are required`() {
        val lease = fixture.lease
        listOf(
            null,
            lease.copy(accountId = fixture.otherId),
            lease.copy(accountServerOrigin = "https://other.logdate.test"),
            lease.copy(deviceId = "other-desktop"),
            lease.copy(deviceActive = false),
            lease.copy(issuedAt = fixture.now + 1.seconds),
            lease.copy(expiresAt = lease.issuedAt + 61.seconds),
            lease.copy(expiresAt = lease.issuedAt),
            lease.copy(expiresAt = lease.issuedAt - 1.seconds),
            lease.copy(expiresAt = fixture.now),
        ).forEachIndexed { index, changed ->
            assertIs<AssistantAccessDecision.Denied>(
                authorize(fixture.snapshot.copy(device = fixture.device.copy(accountLease = changed))),
                "lease $index",
            )
        }
    }

    @Test
    fun `fresh lease cannot hide missing negative or stale monotonic request age`() {
        listOf(null, (-1).seconds, 60.seconds, 61.seconds).forEach { age ->
            assertEquals(
                AssistantAccessDecision.Denied(AssistantAccessDenial.FRESHNESS_UNAVAILABLE),
                authorize(fixture.snapshot.copy(device = fixture.device.copy(leaseAge = age))),
            )
        }
        assertEquals(
            AssistantAccessDecision.Allowed,
            authorize(fixture.snapshot.copy(device = fixture.device.copy(leaseAge = 59.seconds))),
        )
    }

    @Test
    fun `missing trusted security time denies even with fresh account lease`() {
        assertIs<AssistantAccessDecision.Denied>(
            policy.authorize(fixture.snapshot, fixture.request, AssistantCapability.READ_MEMORIES, null),
        )
    }

    private fun authorize(
        snapshot: app.logdate.client.repository.assistants.AssistantAccessSnapshot = fixture.snapshot,
        request: app.logdate.client.repository.assistants.AssistantRequestAuthority = fixture.request,
    ) = policy.authorize(snapshot, request, AssistantCapability.READ_MEMORIES, fixture.now)
}
