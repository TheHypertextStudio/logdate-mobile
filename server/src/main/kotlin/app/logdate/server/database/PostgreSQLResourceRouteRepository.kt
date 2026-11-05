package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.ResourceKind
import app.logdate.server.logdate.ResourceRoute
import app.logdate.server.logdate.ResourceRouteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.time.Clock

class PostgreSQLResourceRouteRepository : ResourceRouteRepository {
    override suspend fun find(resourceId: String): ResourceRoute? =
        transaction {
            ResourceRoutesTable
                .selectAll()
                .where { ResourceRoutesTable.resourceId eq resourceId }
                .singleOrNull()
                ?.let { row ->
                    ResourceRoute(
                        resourceId = row[ResourceRoutesTable.resourceId],
                        accountId = row[ResourceRoutesTable.accountId],
                        kind = ResourceKind.valueOf(row[ResourceRoutesTable.kind].uppercase()),
                    )
                }
        }

    override suspend fun upsert(
        route: ResourceRoute,
        identityEpoch: Long,
    ): ResourceRoute =
        withContext(Dispatchers.IO) {
            transaction {
                requireAccountIdentityWrite(route.accountId, identityEpoch)
                val now = Clock.System.now().timestamp()
                val changed =
                    accountWriteConnection()
                        .prepareStatement(
                            "INSERT INTO resource_routes(resource_id, account_id, kind, created_at, updated_at) VALUES (?, ?, ?, ?, ?) " +
                                "ON CONFLICT(resource_id) DO UPDATE SET kind = EXCLUDED.kind, updated_at = EXCLUDED.updated_at " +
                                "WHERE resource_routes.account_id = EXCLUDED.account_id",
                        ).use {
                            it.setString(1, route.resourceId)
                            it.setObject(2, route.accountId)
                            it.setString(3, route.kind.pathSegment)
                            it.setTimestamp(4, now)
                            it.setTimestamp(5, now)
                            it.executeUpdate()
                        }
                if (changed != 1) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                route
            }
        }

    override suspend fun delete(
        resourceId: String,
        accountId: UUID,
        identityEpoch: Long,
    ) = withContext(Dispatchers.IO) {
        transaction {
            requireAccountIdentityWrite(accountId, identityEpoch)
            ResourceRoutesTable.deleteWhere {
                (ResourceRoutesTable.resourceId eq resourceId) and (ResourceRoutesTable.accountId eq accountId)
            }
        }
        Unit
    }
}

object ResourceRoutesTable : Table("resource_routes") {
    val resourceId = text("resource_id")
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val kind = varchar("kind", 32)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(resourceId)
}
