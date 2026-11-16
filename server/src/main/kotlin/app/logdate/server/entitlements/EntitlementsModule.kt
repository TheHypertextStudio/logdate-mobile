package app.logdate.server.entitlements

import app.logdate.server.billing.BILLING_RETURN_PATH
import app.logdate.server.billing.BillingProvider
import app.logdate.server.billing.BillingStore
import app.logdate.server.billing.CloudBillingService
import app.logdate.server.billing.CloudLaunch
import app.logdate.server.billing.HttpStripeApi
import app.logdate.server.billing.StorageMeterReporter
import app.logdate.server.billing.StripeApi
import app.logdate.server.billing.StripeConfig
import app.logdate.server.billing.StripePlanPrices
import app.logdate.server.billing.StripeSignatureVerifier
import app.logdate.server.billing.StripeWebhookHandler
import app.logdate.server.billing.SubscriptionSync
import io.ktor.client.HttpClient
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for entitlement resolution and quota enforcement.
 *
 * Selection is driven by [BillingProvider.fromEnvironment]:
 *
 *  - [BillingProvider.Disabled] (default, self-host) binds [UnlimitedEntitlementService] + a
 *    [UnlimitedUsageCalculator] — every account sees unlimited limits, no database traffic.
 *  - Any active provider binds [StoredEntitlementService] + [DatabaseUsageCalculator] so webhook
 *    handlers and the sync endpoints see the same `account_entitlements` table, and
 *    [CloudBillingService] for reading and changing an account's plan. The Stripe pieces are bound
 *    only when the provider includes Stripe and its keys are set.
 *
 * Keeping this in its own module means the billing-disabled deployment never touches the
 * `plans` / `account_entitlements` tables, and a self-hoster running without Postgres still works.
 */
fun entitlementsModule(
    provider: BillingProvider = BillingProvider.fromEnvironment(),
    databaseAvailable: Boolean,
    launch: CloudLaunch = CloudLaunch.fromEnvironment(),
    stripeConfig: StripeConfig? = StripeConfig.fromEnvironment(),
): Module =
    module {
        single { provider }
        single { launch }
        if (provider == BillingProvider.Disabled || !databaseAvailable) {
            single<EntitlementService> { UnlimitedEntitlementService() }
            single<UsageCalculator> { UnlimitedUsageCalculator }
            single<PlanCatalogService> { EmptyPlanCatalogService }
        } else {
            // Exposed's TransactionManager tracks the default Database set by Database.connect()
            // during DatabaseConfig.initializeDatabase — the rest of the server's Postgres repos
            // rely on the same thread-local handle. Binding it here makes the dependency explicit
            // for the services that consume it without requiring a broader refactor first.
            single<Database> { TransactionManager.defaultDatabase!! }
            single<EntitlementService> { StoredEntitlementService(database = get()) }
            single<UsageCalculator> { DatabaseUsageCalculator(database = get()) }
            single { BillingStore(database = get()) }
            // Stripe is in play only when the deployment both selects it and holds its keys. Without
            // them every purchase is refused and the catalog lists nothing to buy.
            val stripe = stripeConfig?.takeIf { provider.stripeEnabled }
            if (stripe != null) {
                single<StripeApi> { HttpStripeApi(http = get<HttpClient>(), config = stripe) }
                single { StripePlanPrices(stripe = get()) }
                single { SubscriptionSync(store = get(), stripe = get(), prices = get()) }
                single { StorageMeterReporter(store = get(), stripe = get(), usage = get()) }
                single {
                    StripeWebhookHandler(
                        verifier = StripeSignatureVerifier(stripe.webhookSecret),
                        stripe = get(),
                        sync = get(),
                        store = get(),
                    )
                }
            }
            single<PlanCatalogService> { StoredPlanCatalogService(database = get(), prices = getOrNull(), launch = launch) }
            single {
                CloudBillingService(
                    store = get(),
                    entitlements = get(),
                    usage = get(),
                    launch = launch,
                    stripe = getOrNull(),
                    prices = getOrNull(),
                    sync = getOrNull(),
                    returnUrl = billingReturnUrl(),
                )
            }
        }
        single { EntitlementEnforcer(entitlementService = get(), usageCalculator = get()) }
    }

/** The address of the page a browser lands on after a Stripe page, on this server's public origin. */
private fun billingReturnUrl(readEnv: (String) -> String? = System::getenv): String {
    val origin = readEnv("LOGDATE_PUBLIC_ORIGIN")?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() } ?: "http://localhost:8080"
    return "$origin/api/v1$BILLING_RETURN_PATH"
}

/**
 * No-op usage calculator used when billing is disabled. Returns zero for every dimension so the
 * [EntitlementEnforcer]'s quota checks always pass the `current + pending < limit` comparison —
 * which is immaterial anyway because [UnlimitedEntitlementService.resolve] returns `null` limits.
 */
internal object UnlimitedUsageCalculator : UsageCalculator {
    override suspend fun storageBytes(accountId: java.util.UUID): Long = 0L

    override suspend fun storageBytesOutsideSpaces(accountId: java.util.UUID): Long = 0L

    override suspend fun backupCount(accountId: java.util.UUID): Int = 0
}
