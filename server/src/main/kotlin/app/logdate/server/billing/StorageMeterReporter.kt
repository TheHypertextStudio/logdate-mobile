package app.logdate.server.billing

import app.logdate.server.entitlements.UsageCalculator
import app.logdate.server.logging.BillingNotices
import io.github.aakira.napier.Napier
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Tells Stripe how much extra storage each Plus subscriber is using, so Stripe can bill it.
 *
 * The meter takes the last value reported in a billing period, counted in whole steps past the
 * storage the plan includes. So a report goes out whenever an account's step count changes, and
 * again every [heartbeat] while it stays the same: the heartbeat keeps a recent value in every
 * period, and a period with no report at all would bill for none.
 *
 * A subscription whose payment is failing is still reported. It cannot grow past its included
 * storage until the payment is put right, but what it already holds is still in use.
 */
class StorageMeterReporter(
    private val store: BillingStore,
    private val stripe: StripeApi,
    private val usage: UsageCalculator,
    private val clock: Clock = Clock.System,
    private val heartbeat: Duration = 6.hours,
) {
    /** Reports every account that is due and returns how many reports Stripe accepted. */
    suspend fun reportDue(): Int {
        var reported = 0
        for (subscription in store.liveSubscriptions()) {
            try {
                if (report(subscription)) reported++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One account's failure must not stop the rest; the next pass tries it again.
                Napier.e(BillingNotices.METER_REPORT_FAILED, e)
            }
        }
        return reported
    }

    private suspend fun report(subscription: StoredSubscription): Boolean {
        val plan = store.plan(subscription.planId) ?: return false
        val stepBytes = plan.extraStorageStepBytes ?: return false
        val customerId = store.stripeCustomerId(subscription.accountId) ?: return false
        val units = extraStorageSteps(usage.storageBytes(subscription.accountId), plan.includedBytes, stepBytes)
        val now = clock.now()
        val last = store.meterReport(subscription.accountId)
        if (last != null && last.units == units && now - last.at < heartbeat) return false
        stripe.reportMeterEvent(
            eventName = StripeCatalogNames.EXTRA_STORAGE_METER_EVENT,
            customerId = customerId,
            value = units.toLong(),
            identifier = "${subscription.accountId}-${now.epochSeconds}",
            timestampEpochSeconds = now.epochSeconds,
        )
        store.saveMeterReport(subscription.accountId, MeterReport(units = units, at = now))
        return true
    }
}
