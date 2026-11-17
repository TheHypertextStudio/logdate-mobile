package app.logdate.client.billing

import app.logdate.client.billing.model.LogDateBackupPlanOption
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSubscriptionBillerTest {
    @Test
    fun `purchase opens configured checkout url for paid plan`() =
        runTest {
            val opened = mutableListOf<String>()
            val biller =
                DesktopSubscriptionBiller(
                    checkoutUrlProvider = { plan -> "https://billing.logdate.app/checkout?plan=${plan.sku}" },
                    managementUrlProvider = { "https://billing.logdate.app/account" },
                    browserLauncher = { opened.add(it) },
                )

            biller.purchasePlan(LogDateBackupPlanOption.STANDARD)

            assertEquals(listOf("https://billing.logdate.app/checkout?plan=logdate_backup_plan_standard"), opened)
        }

    @Test
    fun `basic plan does not open checkout`() =
        runTest {
            val opened = mutableListOf<String>()
            val biller =
                DesktopSubscriptionBiller(
                    checkoutUrlProvider = { "https://billing.logdate.app/checkout" },
                    managementUrlProvider = { "https://billing.logdate.app/account" },
                    browserLauncher = { opened.add(it) },
                )

            biller.purchasePlan(LogDateBackupPlanOption.BASIC)

            assertTrue(opened.isEmpty())
        }

    @Test
    fun `cancel opens management url`() =
        runTest {
            val opened = mutableListOf<String>()
            val biller =
                DesktopSubscriptionBiller(
                    checkoutUrlProvider = { "https://billing.logdate.app/checkout" },
                    managementUrlProvider = { "https://billing.logdate.app/account" },
                    browserLauncher = { opened.add(it) },
                )

            biller.cancelPlan()

            assertEquals(listOf("https://billing.logdate.app/account"), opened)
        }
}
