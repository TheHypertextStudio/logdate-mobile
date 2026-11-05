package app.logdate.server.logdate

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import java.util.UUID

enum class ResourceKind(
    val pathSegment: String,
) {
    JOURNAL("journal"),
    NOTE("note"),
    REWIND("rewind"),
}

data class ResourceRoute(
    val resourceId: String,
    val accountId: UUID,
    val kind: ResourceKind,
)

interface ResourceRouteRepository {
    suspend fun find(resourceId: String): ResourceRoute?

    suspend fun upsert(
        route: ResourceRoute,
        identityEpoch: Long = 0,
    ): ResourceRoute

    suspend fun delete(
        resourceId: String,
        accountId: UUID,
        identityEpoch: Long = 0,
    )
}

class InMemoryResourceRouteRepository : ResourceRouteRepository {
    private val routes = java.util.concurrent.ConcurrentHashMap<String, ResourceRoute>()

    override suspend fun find(resourceId: String): ResourceRoute? = routes[resourceId]

    override suspend fun upsert(
        route: ResourceRoute,
        identityEpoch: Long,
    ): ResourceRoute {
        requireLegacyIdentityEpoch(identityEpoch)
        routes.compute(route.resourceId) { _, existing ->
            if (existing != null && existing.accountId != route.accountId) {
                throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
            }
            route
        }
        return route
    }

    override suspend fun delete(
        resourceId: String,
        accountId: UUID,
        identityEpoch: Long,
    ) {
        requireLegacyIdentityEpoch(identityEpoch)
        routes.computeIfPresent(resourceId) { _, existing -> if (existing.accountId == accountId) null else existing }
    }
}
