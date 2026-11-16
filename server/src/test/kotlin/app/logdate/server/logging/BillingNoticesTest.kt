package app.logdate.server.logging

import io.github.aakira.napier.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class BillingNoticesTest {
    @Test
    fun `billing outcomes keep their own codes in production logs`() {
        mapOf(
            BillingNotices.PLAN_MISSING to "SERVER_BILLING_PLAN_MISSING",
            BillingNotices.BASE_PLAN_MISSING to "SERVER_BILLING_BASE_PLAN_MISSING",
            BillingNotices.PRICES_UNREADABLE to "SERVER_BILLING_PRICES_UNREADABLE",
            BillingNotices.PROVIDER_FAILED to "SERVER_BILLING_PROVIDER_FAILED",
            BillingNotices.METER_REPORT_FAILED to "SERVER_BILLING_METER_REPORT_FAILED",
            BillingNotices.METER_RUN_FAILED to "SERVER_BILLING_METER_RUN_FAILED",
            BillingNotices.METERED_PRICE_MISSING to "SERVER_BILLING_METERED_PRICE_MISSING",
            BillingNotices.SUBSCRIPTION_UNOWNED to "SERVER_BILLING_SUBSCRIPTION_UNOWNED",
            BillingNotices.SUBSCRIPTION_NOT_A_PLAN to "SERVER_BILLING_SUBSCRIPTION_NOT_A_PLAN",
            BillingNotices.WEBHOOK_REJECTED to "SERVER_BILLING_WEBHOOK_REJECTED",
            BillingNotices.WEBHOOK_NOT_APPLIED to "SERVER_BILLING_WEBHOOK_NOT_APPLIED",
            BillingNotices.WEBHOOK_CHANGED_NOTHING to "SERVER_BILLING_WEBHOOK_CHANGED_NOTHING",
            BillingNotices.CATALOG_READY to "SERVER_BILLING_CATALOG_READY",
            BillingNotices.CATALOG_NOT_READY to "SERVER_BILLING_CATALOG_NOT_READY",
            BillingNotices.CATALOG_REFUSED_USAGE to "SERVER_BILLING_CATALOG_REFUSED_USAGE",
            BillingNotices.CATALOG_REFUSED_NO_KEY to "SERVER_BILLING_CATALOG_REFUSED_NO_KEY",
            BillingNotices.CATALOG_FAILED to "SERVER_BILLING_CATALOG_FAILED",
        ).forEach { (message, code) ->
            assertEquals(code, safeServerMessage(LogLevel.ERROR, tag = null, message = message))
        }
    }

    @Test
    fun `the count of accounts metered passes through, since a count names no one`() {
        val message = BillingNotices.meterReported(12)

        assertEquals(message, safeServerMessage(LogLevel.INFO, tag = null, message = message))
    }
}
