package app.logdate.server.entitlements

import app.logdate.shared.model.EntitlementTierWire
import app.logdate.shared.model.PlanOption
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
 * Reads the catalog from the `plans` table, newest tier last so callers can present them in
 * ascending order without re-sorting.
 */
class StoredPlanCatalogService(
    private val database: Database,
) : PlanCatalogService {
    override suspend fun plans(): List<PlanOption> =
        transaction(database) {
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
                        stripePriceId = row[PlansTable.stripePriceId],
                    )
                }.sortedBy { it.tier.ordinal }
        }

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
