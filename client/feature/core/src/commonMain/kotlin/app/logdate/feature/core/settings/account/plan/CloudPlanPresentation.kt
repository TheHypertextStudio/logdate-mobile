package app.logdate.feature.core.settings.account.plan

import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.PlanOption
import app.logdate.shared.model.PlanPrice

/** What the button on a plan's card does, if the card has one. */
sealed interface PlanAction {
    /** Nothing can be done with this plan here and now, so the card has no button. */
    data object None : PlanAction

    /** Start paying for this plan. Opens a checkout in the browser. */
    data object Buy : PlanAction

    /** Move the paid plan to this one. */
    data object Switch : PlanAction

    /** Keep this plan and bill it at [interval] instead. */
    data class BillAt(
        val interval: BillingInterval,
    ) : PlanAction
}

/**
 * What the account can do with [plan] right now.
 *
 * A card gets a button only for something the server will carry out: purchases are available, the
 * plan is on sale and priced for the chosen interval, and the change makes sense for this account.
 * The plan a beta member has at no charge is never sold to them; they return to it by cancelling.
 */
fun CloudPlanUiState.Ready.actionFor(plan: PlanOption): PlanAction {
    if (!subscription.purchasesAvailable || !plan.isPurchasable) return PlanAction.None
    if (plan.priceFor(interval) == null) return PlanAction.None
    if (plan.id == subscription.freePlanId) return PlanAction.None
    if (!isPaying) return PlanAction.Buy
    // A plan that is ending is changed by keeping it first.
    if (isEnding) return PlanAction.None
    if (plan.id != subscription.planId) return PlanAction.Switch
    return if (subscription.interval == interval) PlanAction.None else PlanAction.BillAt(interval)
}

fun PlanOption.priceFor(interval: BillingInterval): PlanPrice? = prices.firstOrNull { it.interval == interval }

/** Whether the plans can be billed more than one way, so choosing between them means something. */
fun List<PlanOption>.offerBothIntervals(): Boolean =
    any { plan ->
        plan.prices
            .map { it.interval }
            .toSet()
            .size > 1
    }

/**
 * An amount of money as the payment provider gave it, written for reading: `$3.99`, `$1`, `€7.99`,
 * `¥500`, `12.50 CHF`.
 *
 * [amountMinor] is in the currency's smallest unit. A whole amount drops its empty decimals. A
 * currency without a familiar symbol is written with its code after the amount, which is always
 * unambiguous.
 */
fun formatMoney(
    amountMinor: Long,
    currency: String,
): String {
    val code = currency.lowercase()
    val amount =
        if (code in ZERO_DECIMAL_CURRENCIES) {
            amountMinor.toString()
        } else {
            val whole = amountMinor / MINOR_PER_MAJOR
            val fraction = amountMinor % MINOR_PER_MAJOR
            if (fraction == 0L) whole.toString() else "$whole.${fraction.toString().padStart(2, '0')}"
        }
    val symbol = CURRENCY_SYMBOLS[code]
    return if (symbol != null) "$symbol$amount" else "$amount ${code.uppercase()}"
}

fun PlanPrice.formatted(): String = formatMoney(amountMinor, currency)

private const val MINOR_PER_MAJOR = 100L

private val CURRENCY_SYMBOLS =
    mapOf(
        "usd" to "$",
        "eur" to "€",
        "gbp" to "£",
        "jpy" to "¥",
        "cad" to "CA$",
        "aud" to "A$",
    )

private val ZERO_DECIMAL_CURRENCIES = setOf("jpy", "krw", "vnd", "clp", "isk")
