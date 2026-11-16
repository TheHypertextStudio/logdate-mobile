package app.logdate.server.entitlements

import app.logdate.server.space.SpaceQuota
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [EntitlementEnforcer], validating the logic that governs user quotas
 * and service access based on subscription tiers.
 *
 * This suite covers various scenarios including free vs. unlimited plans,
 * storage and backup count limit enforcement, and the handling of edge cases
 * like past-due or cancelled subscription statuses.
 */
class EntitlementEnforcerTest {
    private val accountId = UUID.randomUUID()

    @Test
    fun `unlimited plan allows any media upload regardless of usage`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService = FakeService(entitlement = unlimited()),
                    usageCalculator = StaticUsage(bytes = 999_999_999_999L, backups = 999_999),
                )
            val result = enforcer.checkMediaUpload(accountId, pendingBytes = 1_000_000)
            assertIs<QuotaCheck.Allowed>(result)
        }

    @Test
    fun `free plan denies media upload once limit is exceeded`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService = FakeService(entitlement = tier(storage = 1_000_000L)),
                    usageCalculator = StaticUsage(bytes = 900_000L),
                )
            val result = enforcer.checkMediaUpload(accountId, pendingBytes = 200_000L)
            val denied = assertIs<QuotaCheck.Denied>(result)
            assertEquals(QuotaReason.STORAGE_BYTES, denied.reason)
            assertEquals(1_000_000L, denied.limit)
        }

    @Test
    fun `free plan allows upload that fits under the storage limit`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService = FakeService(entitlement = tier(storage = 1_000_000L)),
                    usageCalculator = StaticUsage(bytes = 900_000L),
                )
            val result = enforcer.checkMediaUpload(accountId, pendingBytes = 50_000L)
            assertIs<QuotaCheck.Allowed>(result)
        }

    @Test
    fun `the space quota is the plan's storage limit less what media and backups take`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService = FakeService(entitlement = tier(storage = 1_000L)),
                    usageCalculator = StaticUsage(bytes = 5_000L, outsideSpaces = 400L),
                )
            assertEquals(SpaceQuota(limitBytes = 1_000L, otherBytes = 400L), enforcer.spaceQuota(accountId))
        }

    @Test
    fun `a plan without a storage limit has no space quota`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService = FakeService(entitlement = tier(storage = null)),
                    usageCalculator = StaticUsage(bytes = 5_000L),
                )
            assertNull(enforcer.spaceQuota(accountId))
        }

    @Test
    fun `backup upload denies once backup-count limit is hit even when storage has room`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService =
                        FakeService(entitlement = tier(storage = 1_000_000_000L, backups = 3)),
                    usageCalculator = StaticUsage(bytes = 0L, backups = 3),
                )
            val result = enforcer.checkBackupUpload(accountId, pendingBytes = 1_000L)
            val denied = assertIs<QuotaCheck.Denied>(result)
            assertEquals(QuotaReason.BACKUP_COUNT, denied.reason)
        }

    @Test
    fun `backup upload denies on storage limit even if backup count has room`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService =
                        FakeService(entitlement = tier(storage = 1_000L, backups = 100)),
                    usageCalculator = StaticUsage(bytes = 900L, backups = 0),
                )
            val result = enforcer.checkBackupUpload(accountId, pendingBytes = 500L)
            val denied = assertIs<QuotaCheck.Denied>(result)
            assertEquals(QuotaReason.STORAGE_BYTES, denied.reason)
        }

    @Test
    fun `UnlimitedEntitlementService always returns SELF_HOST status`() =
        runTest {
            val service = UnlimitedEntitlementService()
            val result = service.resolve(accountId)
            assertEquals(EntitlementStatus.SELF_HOST, result.status)
            assertTrue(result.limits.storageBytes == null)
            assertTrue(result.limits.backupCount == null)
        }

    @Test
    fun `PAST_DUE status keeps the plan's quota so grace period is transparent`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService =
                        FakeService(
                            entitlement =
                                Entitlement(
                                    planId = "standard",
                                    tier = EntitlementTier.STANDARD,
                                    status = EntitlementStatus.PAST_DUE,
                                    limits = EntitlementLimits(storageBytes = 1_000_000L, backupCount = 10),
                                ),
                        ),
                    usageCalculator = StaticUsage(bytes = 900_000L),
                )
            // A user in grace can still upload under their existing quota — they shouldn't see
            // 402s before they see a billing-UI nudge.
            assertIs<QuotaCheck.Allowed>(enforcer.checkMediaUpload(accountId, pendingBytes = 50_000L))
        }

    @Test
    fun `the base plan refuses an upload past its included storage and names the plan`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService = FakeService(entitlement = cloud()),
                    usageCalculator = StaticUsage(bytes = 50 * GIB - 10),
                )

            val denied = assertIs<QuotaCheck.Denied>(enforcer.checkMediaUpload(accountId, pendingBytes = 11))

            assertEquals(QuotaReason.STORAGE_BYTES, denied.reason)
            assertEquals(50 * GIB, denied.limit)
            assertEquals("cloud", denied.planId)
        }

    @Test
    fun `Plus keeps accepting uploads past its included storage while its payments are current`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService = FakeService(entitlement = cloudPlus(billable = true)),
                    usageCalculator = StaticUsage(bytes = 400 * GIB, backups = 12),
                )

            assertIs<QuotaCheck.Allowed>(enforcer.checkMediaUpload(accountId, pendingBytes = 10 * GIB))
            assertIs<QuotaCheck.Allowed>(enforcer.checkBackupUpload(accountId, pendingBytes = 10 * GIB))
            assertNull(enforcer.spaceQuota(accountId), "a space write past the included storage is metered, not refused")
        }

    @Test
    fun `Plus stops growing past its included storage while a payment is failing`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService =
                        FakeService(entitlement = cloudPlus(billable = false).copy(status = EntitlementStatus.PAST_DUE)),
                    usageCalculator = StaticUsage(bytes = 250 * GIB, outsideSpaces = 100 * GIB),
                )

            val denied = assertIs<QuotaCheck.Denied>(enforcer.checkMediaUpload(accountId, pendingBytes = 1))

            assertEquals(QuotaReason.EXTRA_STORAGE_UNPAID, denied.reason)
            assertEquals(250 * GIB, denied.limit)
            assertEquals(SpaceQuota(limitBytes = 250 * GIB, otherBytes = 100 * GIB), enforcer.spaceQuota(accountId))
        }

    @Test
    fun `Plus under its included storage accepts uploads whatever its payment state`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService = FakeService(entitlement = cloudPlus(billable = false)),
                    usageCalculator = StaticUsage(bytes = 10 * GIB),
                )

            assertIs<QuotaCheck.Allowed>(enforcer.checkMediaUpload(accountId, pendingBytes = GIB))
        }

    @Test
    fun `an account with no plan is refused for having no plan, not for being full`() =
        runTest {
            val enforcer =
                EntitlementEnforcer(
                    entitlementService = FakeService(entitlement = Entitlement.noPlan()),
                    usageCalculator = StaticUsage(bytes = 0L),
                )

            val media = assertIs<QuotaCheck.Denied>(enforcer.checkMediaUpload(accountId, pendingBytes = 1))
            val backup = assertIs<QuotaCheck.Denied>(enforcer.checkBackupUpload(accountId, pendingBytes = 1))

            assertEquals(QuotaReason.NO_PLAN, media.reason)
            assertEquals(QuotaReason.NO_PLAN, backup.reason)
        }

    private fun cloud(): Entitlement =
        Entitlement(
            planId = "cloud",
            tier = EntitlementTier.STANDARD,
            status = EntitlementStatus.ACTIVE,
            limits = EntitlementLimits(storageBytes = 50 * GIB, backupCount = null),
        )

    private fun cloudPlus(billable: Boolean): Entitlement =
        Entitlement(
            planId = "cloud_plus",
            tier = EntitlementTier.PRO,
            status = EntitlementStatus.ACTIVE,
            limits =
                EntitlementLimits(
                    storageBytes = 250 * GIB,
                    backupCount = null,
                    extraStorage = ExtraStorage(stepBytes = 50 * GIB, billable = billable),
                ),
        )

    private fun unlimited(): Entitlement =
        Entitlement(
            planId = "unlimited",
            tier = EntitlementTier.UNLIMITED,
            status = EntitlementStatus.ACTIVE,
            limits = EntitlementLimits(storageBytes = null, backupCount = null),
        )

    private fun tier(
        storage: Long?,
        backups: Int? = null,
    ): Entitlement =
        Entitlement(
            planId = "standard",
            tier = EntitlementTier.STANDARD,
            status = EntitlementStatus.ACTIVE,
            limits = EntitlementLimits(storageBytes = storage, backupCount = backups),
        )

    private class FakeService(
        val entitlement: Entitlement,
    ) : EntitlementService {
        override suspend fun resolve(accountId: UUID): Entitlement = entitlement
    }

    private class StaticUsage(
        val bytes: Long = 0L,
        val backups: Int = 0,
        val outsideSpaces: Long = bytes,
    ) : UsageCalculator {
        override suspend fun storageBytes(accountId: UUID): Long = bytes

        override suspend fun storageBytesOutsideSpaces(accountId: UUID): Long = outsideSpaces

        override suspend fun backupCount(accountId: UUID): Int = backups
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
