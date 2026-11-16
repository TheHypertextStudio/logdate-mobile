package app.logdate.server.entitlements

import app.logdate.server.billing.CloudLaunch
import app.logdate.server.billing.StripeCatalogNames
import app.logdate.server.billing.StripePlanPrices
import app.logdate.server.billing.StripePrice
import app.logdate.server.database.dbQuery
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.EntitlementTierWire
import app.logdate.shared.model.ExtraStorageOffer
import app.logdate.shared.model.PlanOption
import app.logdate.shared.model.PlanPrice
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Supplies the set of plans this deployment offers.
 *
 * Separate from [EntitlementService], which answers what a particular account already has. The
 * catalog has no account context and is readable before sign-up, so someone can see what is on
 * offer while deciding whether to create an account.
 */
interface PlanCatalogService {
    suspend fun plans(): List<PlanOption>
}

/**
 * Catalog for a deployment with billing switched off, including every self-hosted server.
 *
 * Returns nothing rather than failing: there is genuinely nothing to sell, and clients treat an
 * empty catalog as "hide plan selection" instead of as an error.
 */
object EmptyPlanCatalogService : PlanCatalogService {
    override suspend fun plans(): List<PlanOption> = emptyList()
}

/**
 * Reads the catalog from the `plans` table, smallest plan first, and adds what each plan costs.
 *
 * Prices come from [prices], which reads them from Stripe; the table holds none. A plan is listed
 * as on sale only when Stripe gave it a price and, for the base plan, once LogDate Cloud has
 * launched: until then the base plan is free and not sold, while Plus can be bought. So a client
 * never offers a purchase this server would refuse. With no [prices] (a deployment without Stripe keys) the plans
 * are listed with their storage and nothing to buy.
 */
class StoredPlanCatalogService(
    private val database: Database,
    private val prices: StripePlanPrices? = null,
    private val launch: CloudLaunch = CloudLaunch(launched = false),
) : PlanCatalogService {
    override suspend fun plans(): List<PlanOption> {
        val plans =
            dbQuery(database) {
                PlansTable
                    .selectAll()
                    .where { PlansTable.active eq true }
                    .map { row ->
                        PlanOption(
                            id = row[PlansTable.id],
                            name = row[PlansTable.name],
                            tier = parseTier(row[PlansTable.tier]),
                            storageBytesLimit = row[PlansTable.monthlyBytesLimit],
                            backupCountLimit = row[PlansTable.backupCountLimit],
                            features = parseFeatures(row[PlansTable.features]),
                            playProductId = row[PlansTable.playProductId],
                            extraStorage = row[PlansTable.extraStorageStepBytes]?.let { ExtraStorageOffer(stepBytes = it) },
                        )
                    }.sortedWith(compareBy({ it.tier.ordinal }, { it.storageBytesLimit ?: Long.MAX_VALUE }))
            }
        val stripePrices = prices?.pricesFor(plans.map { it.id }.toSet()).orEmpty()
        return plans.map { plan ->
            val planPrices =
                listOfNotNull(
                    stripePrices[StripeCatalogNames.monthlyLookupKey(plan.id)]?.toPlanPrice(BillingInterval.MONTH),
                    stripePrices[StripeCatalogNames.yearlyLookupKey(plan.id)]?.toPlanPrice(BillingInterval.YEAR),
                )
            plan.copy(
                prices = planPrices,
                extraStorage =
                    plan.extraStorage?.copy(
                        monthlyPrice = stripePrices[StripeCatalogNames.extraStorageLookupKey(plan.id)]?.toPlanPrice(BillingInterval.MONTH),
                    ),
                onSale = planPrices.isNotEmpty() && (launch.launched || plan.id != StoredEntitlementService.BASE_PLAN_ID),
            )
        }
    }

    private fun StripePrice.toPlanPrice(interval: BillingInterval): PlanPrice =
        PlanPrice(interval = interval, amountMinor = unitAmount, currency = currency)

    private fun parseTier(raw: String): EntitlementTierWire =
        when (raw.lowercase()) {
            "free" -> {
                EntitlementTierWire.FREE
            }

            "standard" -> {
                EntitlementTierWire.STANDARD
            }

            "pro" -> {
                EntitlementTierWire.PRO
            }

            "unlimited" -> {
                EntitlementTierWire.UNLIMITED
            }

            else -> {
                Napier.w("Plan catalog: unrecognized tier '$raw'; treating as free")
                EntitlementTierWire.FREE
            }
        }

    private fun parseFeatures(raw: String): Map<String, Boolean> =
        try {
            (Json.parseToJsonElement(raw) as? JsonObject)
                ?.mapValues { (_, value) -> value.jsonPrimitive.content.toBoolean() }
                ?: emptyMap()
        } catch (e: Exception) {
            Napier.w("Plan catalog: failed to parse plans.features JSON: '$raw'", e)
            emptyMap()
        }
}
