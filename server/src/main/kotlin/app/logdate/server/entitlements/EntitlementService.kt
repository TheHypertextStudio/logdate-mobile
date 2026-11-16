package app.logdate.server.entitlements

import app.logdate.shared.model.EntitlementResponse
import java.util.UUID

/**
 * The one interface the rest of the server talks to when it needs to decide "can this account do X?".
 *
 * Deliberately provider-agnostic: sync/backup endpoints call [resolve] and then check the returned
 * [Entitlement.limits] or [Entitlement.features]. How the entitlement got populated — Stripe,
 * Google Play, a manual grant, or the permissive self-host default — is the billing module's
 * problem, not theirs.
 *
 * Self-hosters get [UnlimitedEntitlementService] by default so the backend works with no billing
 * code reachable. Production binds [StoredEntitlementService], which reads the
 * `account_entitlements` table populated by webhook handlers.
 */
interface EntitlementService {
    /**
     * Resolve the effective entitlement for [accountId]. Always returns a value: an account with
     * neither a subscription nor a beta membership gets [Entitlement.noPlan], so callers don't
     * branch on null.
     */
    suspend fun resolve(accountId: UUID): Entitlement
}

/**
 * What [accountId] is allowed to do right now.
 *
 * [limits] is the numeric cap (null = unlimited). [status] is the subscription-state lens — a
 * past_due or grace user still has access but is on borrowed time; the UI should nudge them to
 * fix billing. [features] is a JSON bag for plan-level flags the product team can ship without
 * requiring a schema migration.
 */
data class Entitlement(
    val planId: String,
    val tier: EntitlementTier,
    val status: EntitlementStatus,
    val limits: EntitlementLimits,
    val features: Map<String, Boolean> = emptyMap(),
    /** The account has this plan at no charge because it joined during the LogDate Cloud beta. */
    val includedWithBeta: Boolean = false,
) {
    fun hasFeature(feature: EntitlementFeature): Boolean = features[feature.key] == true

    companion object {
        /** The plan identifier reported for an account that has no plan. */
        const val NO_PLAN_ID: String = EntitlementResponse.NO_PLAN_ID

        /**
         * What an account has when it has no subscription and did not join during the beta: nothing
         * may be stored. Reads still work, so whatever the account stored earlier stays reachable.
         */
        fun noPlan(): Entitlement =
            Entitlement(
                planId = NO_PLAN_ID,
                tier = EntitlementTier.FREE,
                status = EntitlementStatus.CANCELLED,
                limits = EntitlementLimits(storageBytes = 0L, backupCount = 0),
            )
    }
}

enum class EntitlementFeature(
    val key: String,
) {
    CLOUD_TRANSCRIPTION_REALTIME("cloud_transcription_realtime"),
    CLOUD_TRANSCRIPT_REFINEMENT("cloud_transcript_refinement"),

    /**
     * Gates the Android Digital Credentials email-verification flow
     * (`/api/v1/auth/me/email/verify/{begin,complete}` plus the onboarding
     * sub-step and Settings entry point). Currently on for self-host /
     * unlimited installs; paid plans default off until we promote the
     * underlying alpha credentials artifact.
     */
    EMAIL_VERIFICATION("email_verification"),
}

enum class EntitlementTier { FREE, STANDARD, PRO, UNLIMITED }

enum class EntitlementStatus {
    /** Paid and current. Full access. */
    ACTIVE,

    /** Payment failed; within grace window. Treat as active but warn the user. */
    PAST_DUE,

    /** Provider-declared grace (e.g. Play hold). Treat as active but warn. */
    GRACE,

    /** Subscription ended. Reads work, writes rejected over quota. */
    CANCELLED,

    /**
     * Unbillable deployment (self-host, BILLING_PROVIDER=disabled). Unlimited access with no
     * provider-backed contract. The UI should not show billing-related UI for these accounts.
     */
    SELF_HOST,
}

/**
 * Quota caps. `null` means unlimited for that dimension. Enforcement happens at the point of
 * write — see `SyncRoutes` where media uploads consult `storageBytes`.
 */
data class EntitlementLimits(
    /** The storage the plan includes. With [extraStorage], an account may store more than this. */
    val storageBytes: Long?,
    val backupCount: Int?,
    val transcriptionSecondsPerMonth: Long? = null,
    /** Storage the plan sells past [storageBytes], or null when the plan stops there. */
    val extraStorage: ExtraStorage? = null,
)

/**
 * Storage a plan sells past what it includes, in steps of [stepBytes], charged by use.
 *
 * [billable] says whether this account's use can be charged right now: it has a subscription with
 * the billing provider and its payments are current. An account whose payment is failing, or that
 * was granted the plan without a subscription, keeps what it stores but cannot grow past the
 * included storage, because nothing would pay for the growth.
 */
data class ExtraStorage(
    val stepBytes: Long,
    val billable: Boolean,
)
