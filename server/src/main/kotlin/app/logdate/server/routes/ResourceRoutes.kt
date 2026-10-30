package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.clientAddress
import app.logdate.server.database.toJavaUUID
import app.logdate.server.database.toKotlinUuid
import app.logdate.server.logdate.LogDateCollectionsRepository
import app.logdate.server.logdate.ResourceKind
import app.logdate.server.logdate.ResourceRouteRepository
import app.logdate.server.ratelimit.FixedWindowRateLimiter
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.routes.docs.CloudServiceDocs
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

@Serializable
data class ResourceResponse(
    val ownerHandle: String,
    val kind: String,
    val canonicalPath: String,
    val canonicalUrl: String,
)

/** The longest resource ID looked up. Journal and entry IDs are UUIDs. */
private const val MAX_RESOURCE_ID_LENGTH = 128

/**
 * Lookups one network address may make per minute that aren't in the resource route table. Each
 * one checks every account's journals and entries, so they are budgeted separately from the cheap
 * lookups that the table answers.
 */
private val RESOURCE_SEARCH_RATE_LIMIT = RateLimitPolicy(maxRequests = 30, windowSeconds = 60)

@OptIn(ExperimentalUuidApi::class)
fun Route.resourceRoutes(
    accountRepository: AccountRepository,
    collectionsRepository: LogDateCollectionsRepository,
    resourceRouteRepository: ResourceRouteRepository,
    searchRateLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(),
) {
    route("/resources") {
        get("/{resourceId}", CloudServiceDocs.resolveResource) {
            val resourceId = call.parameters["resourceId"]?.trim().orEmpty()
            if (resourceId.isBlank() || resourceId.length > MAX_RESOURCE_ID_LENGTH) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            val registeredRoute = resourceRouteRepository.find(resourceId)
            if (registeredRoute != null) {
                val account = accountRepository.findById(registeredRoute.accountId.toKotlinUuid())
                val ownerHandle = account?.handle?.trim()?.takeIf(String::isNotBlank)
                if (ownerHandle != null) {
                    call.respond(resourceResponse(ownerHandle, registeredRoute.kind, resourceId))
                    return@get
                }
            }

            val searchKey = "resource-search:${call.clientAddress()}"
            if (!searchRateLimiter.allow(searchKey, RESOURCE_SEARCH_RATE_LIMIT)) {
                call.response.header(HttpHeaders.RetryAfter, searchRateLimiter.retryAfterSeconds(searchKey).coerceAtLeast(1).toString())
                call.respond(HttpStatusCode.TooManyRequests)
                return@get
            }

            var resolved: ResourceResponse? = null
            for (account in accountRepository.getAllAccounts()) {
                val ownerHandle = account.handle?.trim()?.takeIf(String::isNotBlank) ?: continue
                val userId = account.id.toJavaUUID()

                resolved =
                    when {
                        collectionsRepository.getJournal(userId, resourceId) != null ->
                            resourceResponse(ownerHandle, ResourceKind.JOURNAL, resourceId)

                        collectionsRepository.getEntry(userId, resourceId) != null ->
                            resourceResponse(ownerHandle, ResourceKind.NOTE, resourceId)

                        else -> null
                    }
                if (resolved != null) break
            }

            if (resolved == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(resolved)
            }
        }
    }
}

private fun resourceResponse(
    ownerHandle: String,
    kind: ResourceKind,
    resourceId: String,
): ResourceResponse =
    ResourceResponse(
        ownerHandle = ownerHandle,
        kind = kind.pathSegment,
        canonicalPath = "/${kind.pathSegment}/$resourceId",
        canonicalUrl = "https://$ownerHandle/${kind.pathSegment}/$resourceId",
    )
