package app.logdate.feature.core.settings.account.plan

import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.CloudSubscription
import app.logdate.shared.model.EntitlementStatusWire
import app.logdate.shared.model.EntitlementTierWire
import app.logdate.shared.model.ExtraStorageOffer
import app.logdate.shared.model.PlanOption
import app.logdate.shared.model.PlanPrice

/** Plans and subscriptions as the server sends them, for the Plan screen's tests. */
internal object CloudPlanFixtures {
    const val GIB = 1024L * 1024L * 1024L

    /** 3 March 2027, 12:00 UTC. */
    const val RENEWAL = 1_804_075_200L

    fun plans(onSale: Boolean = true): List<PlanOption> =
        listOf(
            PlanOption(
                id = "cloud",
                name = "LogDate Cloud",
                tier = EntitlementTierWire.STANDARD,
                storageBytesLimit = 50 * GIB,
                backupCountLimit = null,
                prices = listOf(PlanPrice(BillingInterval.MONTH, 399, "usd"), PlanPrice(BillingInterval.YEAR, 3999, "usd")),
                onSale = onSale,
            ),
            PlanOption(
                id = "cloud_plus",
                name = "LogDate Cloud Plus",
                tier = EntitlementTierWire.PRO,
                storageBytesLimit = 250 * GIB,
                backupCountLimit = null,
                prices = listOf(PlanPrice(BillingInterval.MONTH, 799, "usd"), PlanPrice(BillingInterval.YEAR, 7999, "usd")),
                extraStorage = ExtraStorageOffer(stepBytes = 50 * GIB, monthlyPrice = PlanPrice(BillingInterval.MONTH, 100, "usd")),
                onSale = onSale,
            ),
        )

    /** A beta member on the base plan at no charge. */
    fun betaOnBase(
        purchasesAvailable: Boolean = false,
        usedBytes: Long = 32 * GIB,
    ) = CloudSubscription(
        planId = "cloud",
        status = EntitlementStatusWire.ACTIVE,
        includedWithBeta = true,
        betaMember = true,
        freePlanId = "cloud",
        storageUsedBytes = usedBytes,
        storageIncludedBytes = 50 * GIB,
        purchasesAvailable = purchasesAvailable,
        paymentDetailsAvailable = false,
    )

    /** A beta member paying for Plus, with one step of extra storage in use. */
    fun payingForPlus(
        interval: BillingInterval = BillingInterval.MONTH,
        usedBytes: Long = 281 * GIB,
        status: EntitlementStatusWire = EntitlementStatusWire.ACTIVE,
    ) = CloudSubscription(
        planId = "cloud_plus",
        status = status,
        includedWithBeta = false,
        betaMember = true,
        freePlanId = "cloud",
        interval = interval,
        renewsAtEpochSeconds = RENEWAL,
        storageUsedBytes = usedBytes,
        storageIncludedBytes = 250 * GIB,
        extraStorageSteps = if (usedBytes > 250 * GIB) ((usedBytes - 250 * GIB + 50 * GIB - 1) / (50 * GIB)).toInt() else 0,
        purchasesAvailable = true,
        paymentDetailsAvailable = true,
    )

    /** Someone who joined after launch and pays for the base plan. */
    fun payingForBase(usedBytes: Long = 12 * GIB) =
        CloudSubscription(
            planId = "cloud",
            status = EntitlementStatusWire.ACTIVE,
            includedWithBeta = false,
            betaMember = false,
            interval = BillingInterval.MONTH,
            renewsAtEpochSeconds = RENEWAL,
            storageUsedBytes = usedBytes,
            storageIncludedBytes = 50 * GIB,
            purchasesAvailable = true,
            paymentDetailsAvailable = true,
        )

    /** Someone who joined after launch and has not bought a plan. */
    fun noPlan() =
        CloudSubscription(
            planId = null,
            status = EntitlementStatusWire.CANCELLED,
            includedWithBeta = false,
            betaMember = false,
            storageUsedBytes = 0,
            storageIncludedBytes = 0,
            purchasesAvailable = true,
            paymentDetailsAvailable = false,
        )
}
