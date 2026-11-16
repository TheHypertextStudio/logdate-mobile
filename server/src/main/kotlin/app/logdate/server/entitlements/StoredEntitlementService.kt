package app.logdate.server.entitlements

import app.logdate.server.database.AccountsTable
import app.logdate.server.database.dbQuery
import app.logdate.server.logging.BillingNotices
import io.github.aakira.napier.Napier
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Instant

/**
 * Reads the `account_entitlements` and `plans` tables, and each account's beta membership.
 *
 * An account's plan comes from its subscription row while that subscription gives access (active,
 * past due or in grace). Without one, an account that joined during the LogDate Cloud beta has the
 * base plan at no charge, and any other account has no plan. A subscription that has ended gives
 * nothing, so a beta member who cancels Plus is back on the base plan. Accounts that existed when
 * the launch plans were introduced also carry a row with the source `beta`, which says the same
 * thing as their membership.
 *
 * If the plan a row names is missing or disabled (operator misconfiguration), the account gets a
 * hard-zero entitlement and the lookup is logged; the sync endpoints then refuse writes.
 *
 * The [database] parameter is explicit rather than relying on Exposed's global default, so tests
 * and multi-database configurations can swap the binding without touching thread-local state.
 */
class StoredEntitlementService(
    private val database: Database,
) : EntitlementService {
    override suspend fun resolve(accountId: UUID): Entitlement =
        dbQuery(database) {
            val row = findAccountEntitlement(accountId)?.takeIf { givesAccess(it[AccountEntitlementsTable.status]) }
            if (row == null) {
                return@dbQuery if (isBetaMember(accountId)) basePlanIncludedWithBeta() else Entitlement.noPlan()
            }
            val planId = row[AccountEntitlementsTable.planId]
            val plan =
                findActivePlan(planId)
                    ?: run {
                        Napier.e(BillingNotices.PLAN_MISSING)
                        return@dbQuery Entitlement.noPlan().copy(planId = planId)
                    }
            val status = parseStatus(row[AccountEntitlementsTable.status])
            val source = row[AccountEntitlementsTable.entitlementSource]
            val billable = source == SOURCE_STRIPE && status == EntitlementStatus.ACTIVE
            plan.toEntitlement(status = status, extraStorageBillable = billable).copy(includedWithBeta = source == SOURCE_BETA)
        }

    private fun basePlanIncludedWithBeta(): Entitlement {
        val plan =
            findActivePlan(BASE_PLAN_ID)
                ?: run {
                    Napier.e(BillingNotices.BASE_PLAN_MISSING)
                    return Entitlement.noPlan()
                }
        return plan.toEntitlement(status = EntitlementStatus.ACTIVE, extraStorageBillable = false).copy(includedWithBeta = true)
    }

    private fun ResultRow.toEntitlement(
        status: EntitlementStatus,
        extraStorageBillable: Boolean,
    ): Entitlement =
        Entitlement(
            planId = this[PlansTable.id],
            tier = parseTier(this[PlansTable.tier]),
            status = status,
            limits =
                EntitlementLimits(
                    storageBytes = this[PlansTable.monthlyBytesLimit],
                    backupCount = this[PlansTable.backupCountLimit],
                    extraStorage =
                        this[PlansTable.extraStorageStepBytes]?.let { step ->
                            ExtraStorage(stepBytes = step, billable = extraStorageBillable)
                        },
                ),
            features = parseFeatures(this[PlansTable.features]),
        )

    private fun findActivePlan(planId: String): ResultRow? =
        PlansTable
            .selectAll()
            .where { (PlansTable.id eq planId) and (PlansTable.active eq true) }
            .singleOrNull()

    private fun isBetaMember(accountId: UUID): Boolean =
        AccountsTable
            .select(AccountsTable.cloudBetaMember)
            .where { AccountsTable.id eq accountId }
            .singleOrNull()
            ?.get(AccountsTable.cloudBetaMember) == true

    /** Whether a subscription in [status] still gives its plan. An ended one does not. */
    private fun givesAccess(status: String): Boolean =
        when (status.lowercase()) {
            "cancelled", "canceled" -> false
            else -> true
        }

    private fun findAccountEntitlement(accountId: UUID) =
        AccountEntitlementsTable
            .selectAll()
            .where { AccountEntitlementsTable.accountId eq accountId }
            .singleOrNull()

    private fun parseTier(raw: String): EntitlementTier =
        when (raw.lowercase()) {
            "free" -> EntitlementTier.FREE
            "standard" -> EntitlementTier.STANDARD
            "pro" -> EntitlementTier.PRO
            "unlimited" -> EntitlementTier.UNLIMITED
            else -> EntitlementTier.FREE
        }

    private fun parseStatus(raw: String): EntitlementStatus =
        when (raw.lowercase()) {
            "active" -> EntitlementStatus.ACTIVE
            "past_due" -> EntitlementStatus.PAST_DUE
            "grace" -> EntitlementStatus.GRACE
            "cancelled", "canceled" -> EntitlementStatus.CANCELLED
            else -> EntitlementStatus.ACTIVE
        }

    companion object {
        /** The plan a beta member has at no charge. */
        const val BASE_PLAN_ID: String = "cloud"

        /** `account_entitlements.source` for a subscription Stripe bills. */
        const val SOURCE_STRIPE: String = "stripe"

        /** `account_entitlements.source` for the base plan a beta member has at no charge. */
        const val SOURCE_BETA: String = "beta"
        internal val FEATURE_MAP_SERIALIZER = MapSerializer(String.serializer(), Boolean.serializer())
        internal val FEATURES_JSON =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }
    }
}

/**
 * Parses the JSONB `plans.features` column into a typed `Map<String, Boolean>`. Falls
 * open on malformed JSON so a single bad row never crashes the resolve path.
 */
private fun parseFeatures(raw: String): Map<String, Boolean> {
    if (raw.isBlank()) return emptyMap()
    return try {
        StoredEntitlementService.FEATURES_JSON.decodeFromString(
            StoredEntitlementService.FEATURE_MAP_SERIALIZER,
            raw,
        )
    } catch (e: SerializationException) {
        Napier.w("Failed to parse plans.features JSON: '$raw'", e)
        emptyMap()
    }
}

internal object PlansTable : Table("plans") {
    val id: Column<String> = text("id")
    val name = text("name")
    val tier = text("tier")
    val monthlyBytesLimit: Column<Long?> = long("monthly_bytes_limit").nullable()
    val backupCountLimit: Column<Int?> = integer("backup_count_limit").nullable()
    val features: Column<String> = text("features").default("{}")
    val stripePriceId = text("stripe_price_id").nullable()
    val playProductId = text("play_product_id").nullable()
    val extraStorageStepBytes: Column<Long?> = long("extra_storage_step_bytes").nullable()
    val active = bool("active")

    override val primaryKey = PrimaryKey(id)
}

internal object AccountEntitlementsTable : Table("account_entitlements") {
    val accountId: Column<UUID> = javaUUID("account_id")
    val planId: Column<String> = text("plan_id")

    // `entitlementSource` rather than `source` because Exposed's [Table] already exposes a `source`
    // member; shadowing it changes subquery-building semantics in subtle ways.
    val entitlementSource: Column<String> = text("source")
    val externalSubscriptionId = text("external_subscription_id").nullable()
    val status = text("status")
    val currentPeriodEnd: Column<Instant?> = timestamp("current_period_end").nullable()
    val cancelAt: Column<Instant?> = timestamp("cancel_at").nullable()
    val billingInterval: Column<String?> = text("billing_interval").nullable()
    val updatedAt: Column<Instant> = timestamp("updated_at")

    override val primaryKey = PrimaryKey(accountId)
}
