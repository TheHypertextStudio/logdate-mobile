package app.logdate.server.entitlements

import app.logdate.server.database.dbQuery
import io.github.aakira.napier.Napier
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Instant

/**
 * Reads the `account_entitlements` + `plans` tables populated by webhook handlers.
 *
 * Missing rows fall through to the `free` plan: an account that never ran a billing flow gets a
 * functional-but-limited experience rather than a 500. If the `free` plan itself is missing or
 * disabled (operator misconfiguration), we return a hard-zero entitlement and log loudly — the
 * sync endpoints will correctly reject writes.
 *
 * The [database] parameter is explicit rather than relying on Exposed's global default — we want
 * tests and multi-database configurations to be able to swap the binding without touching
 * thread-local state.
 */
class StoredEntitlementService(
    private val database: Database,
) : EntitlementService {
    override suspend fun resolve(accountId: UUID): Entitlement =
        dbQuery(database) {
            val row = findAccountEntitlement(accountId)
            val planId = row?.get(AccountEntitlementsTable.planId) ?: FREE_PLAN_ID
            val statusRaw = row?.get(AccountEntitlementsTable.status)
            val plan =
                PlansTable
                    .selectAll()
                    .where { (PlansTable.id eq planId) and (PlansTable.active eq true) }
                    .singleOrNull()
                    ?: run {
                        Napier.w("Entitlement lookup: plan '$planId' missing or inactive; denying by default")
                        return@dbQuery Entitlement(
                            planId = planId,
                            tier = EntitlementTier.FREE,
                            status = EntitlementStatus.CANCELLED,
                            limits = EntitlementLimits(storageBytes = 0L, backupCount = 0),
                        )
                    }

            Entitlement(
                planId = planId,
                tier = parseTier(plan[PlansTable.tier]),
                status = parseStatus(statusRaw),
                limits =
                    EntitlementLimits(
                        storageBytes = plan[PlansTable.monthlyBytesLimit],
                        backupCount = plan[PlansTable.backupCountLimit],
                    ),
                features = parseFeatures(plan[PlansTable.features]),
            )
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

    private fun parseStatus(raw: String?): EntitlementStatus =
        when (raw?.lowercase()) {
            "active" -> EntitlementStatus.ACTIVE
            "past_due" -> EntitlementStatus.PAST_DUE
            "grace" -> EntitlementStatus.GRACE
            "cancelled", "canceled" -> EntitlementStatus.CANCELLED
            else -> EntitlementStatus.ACTIVE // No row → assume free tier, active state.
        }

    companion object {
        const val FREE_PLAN_ID: String = "free"
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
    val active = bool("active")

    override val primaryKey = PrimaryKey(id)
}

internal object AccountEntitlementsTable : Table("account_entitlements") {
    val accountId: Column<UUID> = javaUUID("account_id")
    val planId: Column<String> = text("plan_id")

    // `entitlementSource` rather than `source` because Exposed's [Table] already exposes a `source`
    // member; shadowing it changes subquery-building semantics in subtle ways.
    @Suppress("unused") // Kept: declaring the column puts it in the table Exposed creates; billing PR #240 reads it
    val entitlementSource: Column<String> = text("source")

    @Suppress("unused") // Kept: declaring the column puts it in the table Exposed creates; billing PR #240 reads it
    val externalSubscriptionId = text("external_subscription_id").nullable()
    val status = text("status")

    @Suppress("unused") // Kept: declaring the column puts it in the table Exposed creates; billing PR #240 reads it
    val currentPeriodEnd: Column<Instant?> = timestamp("current_period_end").nullable()
    val updatedAt: Column<Instant> = timestamp("updated_at")

    override val primaryKey = PrimaryKey(accountId)
}
