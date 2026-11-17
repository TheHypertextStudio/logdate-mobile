package app.logdate.client.billing

import app.logdate.client.billing.model.LogDateBackupPlanOption
import io.github.aakira.napier.Napier
import java.awt.Desktop
import java.net.URI

private const val DEFAULT_CHECKOUT_URL = "https://logdate.app/billing/checkout"
private const val DEFAULT_MANAGEMENT_URL = "https://logdate.app/account/billing"

class DesktopSubscriptionBiller(
    private val checkoutUrlProvider: (LogDateBackupPlanOption) -> String = { plan ->
        val baseUrl = System.getenv("LOGDATE_BILLING_CHECKOUT_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_CHECKOUT_URL
        "$baseUrl?plan=${plan.sku}"
    },
    private val managementUrlProvider: () -> String = {
        System.getenv("LOGDATE_BILLING_MANAGEMENT_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MANAGEMENT_URL
    },
    private val browserLauncher: (String) -> Unit = ::openInBrowser,
) : SubscriptionBiller {
    override suspend fun purchasePlan(plan: LogDateBackupPlanOption) {
        if (plan == LogDateBackupPlanOption.BASIC) return
        browserLauncher(checkoutUrlProvider(plan))
    }

    override suspend fun cancelPlan() {
        browserLauncher(managementUrlProvider())
    }
}

private fun openInBrowser(url: String) {
    runCatching {
        require(Desktop.isDesktopSupported()) { "Desktop browser integration is not supported." }
        Desktop.getDesktop().browse(URI(url))
    }.onFailure { error ->
        Napier.e("Failed to open billing URL: $url", error)
        throw IllegalStateException("Unable to open billing page.", error)
    }
}
