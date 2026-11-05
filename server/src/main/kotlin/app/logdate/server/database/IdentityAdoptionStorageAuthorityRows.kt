@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionStorageAuthority
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Instant

internal fun Connection.identityAdoptionStorageAuthority(
    source: DataSource,
    operation: IdentityAdoptionOperation,
    now: Instant,
): IdentityAdoptionStorageAuthority {
    val item =
        PostgreSQLIdentityAdoptionSourceInventory(source).itemLocked(
            this,
            operation,
            IdentityAdoptionSourceKind.STORAGE_AUTHORITY,
            listOf(operation.binding.accountId.toString()),
            now,
        )
    stagingCheck(item != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
    return IdentityAdoptionStorageAuthority.read(requireNotNull(item), operation.binding)
}
