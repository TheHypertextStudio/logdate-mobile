@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.entitlements.EntitlementService
import app.logdate.server.entitlements.EntitlementStatus
import app.logdate.server.entitlements.EntitlementTier
import app.logdate.server.routes.docs.AccountDocs
import app.logdate.shared.model.EntitlementResponse
import app.logdate.shared.model.EntitlementStatusWire
import app.logdate.shared.model.EntitlementTierWire
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

internal fun Route.registerEntitlementRoute(
    accountRepository: AccountRepository,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
    entitlementService: EntitlementService?,
) {
    get("/me/entitlement", AccountDocs.getEntitlement) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@get
            val service =
                entitlementService ?: return@get call.respond(
                    HttpStatusCode.OK,
                    // When billing isn't wired we expose the unlimited-self-host shape directly
                    // rather than 404-ing — the UI needs a value to render.
                    EntitlementResponse(
                        planId = "self_host_unlimited",
                        tier = EntitlementTierWire.UNLIMITED,
                        status = EntitlementStatusWire.SELF_HOST,
                        storageBytesLimit = null,
                        backupCountLimit = null,
                        transcriptionSecondsPerMonthLimit = null,
                        features =
                            mapOf(
                                "cloud_transcription_realtime" to true,
                                "cloud_transcript_refinement" to true,
                                "email_verification" to true,
                            ),
                    ),
                )
            val resolved =
                service.resolve(java.util.UUID.fromString(account.id.toString()))
            call.respond(
                HttpStatusCode.OK,
                EntitlementResponse(
                    planId = resolved.planId,
                    tier = resolved.tier.toWire(),
                    status = resolved.status.toWire(),
                    storageBytesLimit = resolved.limits.storageBytes,
                    backupCountLimit = resolved.limits.backupCount,
                    transcriptionSecondsPerMonthLimit = resolved.limits.transcriptionSecondsPerMonth,
                    features = resolved.features,
                ),
            )
        } catch (e: Exception) {
            Napier.e("Failed to resolve entitlement", e)
            call.respondForRequestException(e, "Failed to resolve entitlement", metrics)
        }
    }
}

private fun EntitlementTier.toWire(): EntitlementTierWire =
    when (this) {
        EntitlementTier.FREE -> EntitlementTierWire.FREE
        EntitlementTier.STANDARD -> EntitlementTierWire.STANDARD
        EntitlementTier.PRO -> EntitlementTierWire.PRO
        EntitlementTier.UNLIMITED -> EntitlementTierWire.UNLIMITED
    }

private fun EntitlementStatus.toWire(): EntitlementStatusWire =
    when (this) {
        EntitlementStatus.ACTIVE -> EntitlementStatusWire.ACTIVE
        EntitlementStatus.PAST_DUE -> EntitlementStatusWire.PAST_DUE
        EntitlementStatus.GRACE -> EntitlementStatusWire.GRACE
        EntitlementStatus.CANCELLED -> EntitlementStatusWire.CANCELLED
        EntitlementStatus.SELF_HOST -> EntitlementStatusWire.SELF_HOST
    }
