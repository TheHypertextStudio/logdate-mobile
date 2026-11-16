package app.logdate.shared.model

import kotlinx.serialization.Serializable

/** How often a plan bills. */
@Serializable
enum class BillingInterval {
    MONTH,
    YEAR,
}

/**
 * What a plan costs for one [interval], as the billing provider charges it.
 *
 * [amountMinor] is in the currency's smallest unit (cents for USD) and [currency] is its ISO 4217
 * code in lower case, exactly as the provider reports them. The app formats the amount for the
 * viewer; it never carries a price of its own.
 */
@Serializable
data class PlanPrice(
    val interval: BillingInterval,
    val amountMinor: Long,
    val currency: String,
)

/**
 * Storage a plan sells past what it includes: one [stepBytes] at a time, charged each month only
 * while it is used. [monthlyPrice] is the charge for one step, or null when the provider's price
 * could not be read.
 */
@Serializable
data class ExtraStorageOffer(
    val stepBytes: Long,
    val monthlyPrice: PlanPrice? = null,
)

/**
 * An account's LogDate Cloud plan and how it is paid for, as returned by
 * `GET /api/v1/billing/subscription`.
 *
 * This is the billing view of an account. [EntitlementResponse] says what the account may do; this
 * says what it is paying, when that changes, and which changes the server can make right now.
 */
@Serializable
data class CloudSubscription(
    /** The plan the account is on, or null when it has none. */
    val planId: String?,
    /** Whether payments are current. */
    val status: EntitlementStatusWire,
    /** The account has its current plan at no charge because it joined during the beta. */
    val includedWithBeta: Boolean,
    /** The account joined during the beta, so the base plan never costs it anything. */
    val betaMember: Boolean,
    /**
     * The plan the account has at no charge whenever it pays for nothing: the base plan for a beta
     * member, null for anyone else. It cannot be bought; cancelling a paid plan returns to it.
     */
    val freePlanId: String? = null,
    /** How often the plan bills, or null when nothing is billed. */
    val interval: BillingInterval? = null,
    /** When the plan next renews, in seconds since the epoch. Null when nothing is billed or it is ending. */
    val renewsAtEpochSeconds: Long? = null,
    /** When a cancelled plan stops, in seconds since the epoch. Null unless it was cancelled. */
    val endsAtEpochSeconds: Long? = null,
    /** Bytes the account stores now. */
    val storageUsedBytes: Long,
    /** Bytes the plan includes, or null when it has no limit. Zero when there is no plan. */
    val storageIncludedBytes: Long?,
    /** Whole steps of extra storage in use past [storageIncludedBytes]. */
    val extraStorageSteps: Int = 0,
    /** The server can start, change and cancel a plan right now. When false, show no purchase action. */
    val purchasesAvailable: Boolean,
    /** The account has billing details with the provider, so payment methods and receipts can be opened. */
    val paymentDetailsAvailable: Boolean,
)

/** Body of `POST /api/v1/billing/checkout` and `POST /api/v1/billing/subscription/plan`. */
@Serializable
data class CloudPlanSelection(
    val planId: String,
    val interval: BillingInterval,
)

/** A page at the billing provider to open in the browser: a checkout, or payment details. */
@Serializable
data class BillingPage(
    val url: String,
)
