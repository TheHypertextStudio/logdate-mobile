package app.logdate.server.entitlements

import app.logdate.server.space.SpaceQuota
import java.util.UUID

/**
 * Summarises how much of their [Entitlement.limits] an account has consumed. Used by
 * entitlement enforcement at the sync/backup endpoints to turn "is there room for this upload?"
 * into a boolean.
 *
 * Concrete implementation reads the media, backup and personal space tables. Keeping it behind an
 * interface lets tests feed synthetic usage without spinning up a database.
 */
interface UsageCalculator {
    /** Every byte counted towards the account's storage quota: media, backups and spaces. */
    suspend fun storageBytes(accountId: UUID): Long

    /** The bytes media and backups take. The space store counts its own, so it can check the quota as it writes. */
    suspend fun storageBytesOutsideSpaces(accountId: UUID): Long

    suspend fun backupCount(accountId: UUID): Int
}

/**
 * Indicates whether a pending write would exceed the account's quota. Returned by
 * [EntitlementEnforcer]; endpoints translate it into a 402 Payment Required with a structured
 * error when violated.
 */
sealed class QuotaCheck {
    data object Allowed : QuotaCheck()

    data class Denied(
        val reason: QuotaReason,
        val limit: Long,
        val current: Long,
        /** The plan whose limit refused the write, so a client can say which plan is full. */
        val planId: String,
    ) : QuotaCheck()
}

enum class QuotaReason {
    /** The plan's storage is full and the plan sells none past it. */
    STORAGE_BYTES,

    BACKUP_COUNT,

    /** The plan sells storage past what it includes, but the account's payment is not current. */
    EXTRA_STORAGE_UNPAID,

    /** The account has no plan, so it may store nothing. */
    NO_PLAN,
}

/**
 * Quota gate used by write endpoints. Computes `current + incremental vs. limit` for the
 * dimension in question and emits [QuotaCheck.Denied] when the write would push past.
 *
 * **Storage past what a plan includes.** A plan with [ExtraStorage] keeps accepting writes past
 * its included storage while the account's use is billable; the storage meter then charges for the
 * extra by the step. When it is not billable (a failing payment, or a plan granted without a
 * subscription) the account stops at the included storage until that is put right.
 *
 * **Payment state.** A past-due or grace subscription keeps its plan's included storage, so someone
 * whose card failed is nudged by the billing screen rather than surprised by refused uploads. A
 * subscription that has ended gives no plan at all: [EntitlementService] resolves the account to the
 * base plan when it joined during the beta, and to no plan otherwise.
 */
class EntitlementEnforcer(
    private val entitlementService: EntitlementService,
    private val usageCalculator: UsageCalculator,
) {
    suspend fun checkMediaUpload(
        accountId: UUID,
        pendingBytes: Long,
    ): QuotaCheck {
        val entitlement = entitlementService.resolve(accountId)
        return checkStorage(entitlement, accountId, pendingBytes)
    }

    /**
     * The account's storage quota as the space store enforces it, or null when the account may
     * store without limit: its plan has none, or it sells extra storage this account can be billed
     * for. Spaces share the plan's one storage limit with media and backups; the store adds its own
     * bytes and checks each write against the limit in the same transaction.
     */
    suspend fun spaceQuota(accountId: UUID): SpaceQuota? {
        val limits = entitlementService.resolve(accountId).limits
        val limit = limits.storageBytes ?: return null
        if (limits.extraStorage?.billable == true) return null
        return SpaceQuota(limitBytes = limit, otherBytes = usageCalculator.storageBytesOutsideSpaces(accountId))
    }

    suspend fun checkBackupUpload(
        accountId: UUID,
        pendingBytes: Long,
    ): QuotaCheck {
        val entitlement = entitlementService.resolve(accountId)
        val storage = checkStorage(entitlement, accountId, pendingBytes)
        if (storage is QuotaCheck.Denied) return storage
        val backupLimit = entitlement.limits.backupCount ?: return QuotaCheck.Allowed
        val current = usageCalculator.backupCount(accountId)
        return if (current + 1 > backupLimit) {
            QuotaCheck.Denied(
                QuotaReason.BACKUP_COUNT,
                limit = backupLimit.toLong(),
                current = current.toLong(),
                planId = entitlement.planId,
            )
        } else {
            QuotaCheck.Allowed
        }
    }

    private suspend fun checkStorage(
        entitlement: Entitlement,
        accountId: UUID,
        pendingBytes: Long,
    ): QuotaCheck {
        val limit = entitlement.limits.storageBytes ?: return QuotaCheck.Allowed
        val current = usageCalculator.storageBytes(accountId)
        if (entitlement.planId == Entitlement.NO_PLAN_ID) {
            return QuotaCheck.Denied(QuotaReason.NO_PLAN, limit = limit, current = current, planId = entitlement.planId)
        }
        if (current + pendingBytes <= limit) return QuotaCheck.Allowed
        val extraStorage = entitlement.limits.extraStorage
        return when {
            extraStorage == null -> QuotaCheck.Denied(QuotaReason.STORAGE_BYTES, limit, current, entitlement.planId)
            extraStorage.billable -> QuotaCheck.Allowed
            else -> QuotaCheck.Denied(QuotaReason.EXTRA_STORAGE_UNPAID, limit, current, entitlement.planId)
        }
    }
}
