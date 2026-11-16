package app.logdate.shared.model

import kotlinx.serialization.Serializable

/**
 * A plan a server offers, as returned by `GET /api/v1/plans`.
 *
 * This is the catalog of what someone *could* have, which is different from
 * [EntitlementResponse] — that describes what a signed-in account actually has. The catalog is
 * readable without an account so it can be shown while someone is deciding whether to create one.
 *
 * [prices] are read from the billing provider when the catalog is served, in the provider's own
 * amounts and currency, so what is shown is what will be charged. The app formats them; it holds
 * no prices itself.
 */
@Serializable
data class PlanOption(
    /** Stable public identifier, such as `cloud` or `cloud_plus`. */
    val id: String,
    /** Display name for the plan. */
    val name: String,
    /** Coarse tier used for UI copy and capability decisions. */
    val tier: EntitlementTierWire,
    /** Storage the plan includes, in bytes, or null when the plan is unlimited. */
    val storageBytesLimit: Long?,
    /** Maximum retained backup snapshots, or null when the plan is unlimited. */
    val backupCountLimit: Int?,
    /** Client-safe feature flags this plan unlocks. */
    val features: Map<String, Boolean> = emptyMap(),
    /** Google Play subscription product id, or null when the plan is not sold there. */
    val playProductId: String? = null,
    /** Unused: prices are read by lookup key and reported in [prices]. Kept so older clients decode. */
    val stripePriceId: String? = null,
    /** What the plan costs for each interval it can be billed at. Empty when the provider gave none. */
    val prices: List<PlanPrice> = emptyList(),
    /** Storage sold past [storageBytesLimit], or null when the plan stops there. */
    val extraStorage: ExtraStorageOffer? = null,
    /** Whether the server will sell this plan right now. Plans can be listed before they go on sale. */
    val onSale: Boolean = false,
) {
    /** Whether this plan can be bought here and now: it is on sale and has a price to charge. */
    val isPurchasable: Boolean
        get() = onSale && prices.isNotEmpty()
}

/**
 * Envelope for `GET /api/v1/plans`.
 *
 * An empty [data] list is a valid answer, not an error: it is what a self-hosted deployment with
 * billing switched off returns, and clients hide plan selection entirely in that case.
 */
@Serializable
data class PlanCatalogResponse(
    val success: Boolean,
    val data: List<PlanOption>,
)
