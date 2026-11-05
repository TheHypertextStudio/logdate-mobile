@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.migration.SpaceMigrationConfig
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase
import app.logdate.space.SpaceRef
import app.logdate.space.SpaceTypes
import kotlin.time.Clock
import kotlin.time.Instant

/** Runs only in the mutation transaction, after taking the original account lock. */
internal fun requireSpaceUploadAuthority(
    origin: IdentityWriteExpectation,
    uri: String?,
    config: SpaceMigrationConfig,
) {
    requireAccountIdentityWrite(origin.accountId.toJavaUUID(), origin.identityEpoch)
    val space = uri?.let(SpaceRef::parse)
    if (space == null || space.type != SpaceTypes.PERSONAL || space.skey != "self") refuseSpaceUpload()
    accountWriteConnection()
        .prepareStatement(
            "SELECT a.did, a.storage_mode, m.phase, m.hold_started_at FROM accounts a " +
                "LEFT JOIN space_migrations m ON m.account_id = a.id WHERE a.id = ?",
        ).use {
            it.setObject(1, origin.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                if (!rows.next() || rows.getString("did") != space.authority) refuseSpaceUpload()
                val phase = rows.getString("phase") ?: return
                val heldAt =
                    rows.getTimestamp("hold_started_at")?.toInstant()?.let { time ->
                        Instant.fromEpochSeconds(time.epochSecond, time.nano)
                    }
                if (phase == SpaceMigrationPhase.SWITCHED.wireValue &&
                    heldAt != null &&
                    Clock.System.now() < heldAt + config.holdLifetime
                ) {
                    throw IdentityWriteRefusedException(IdentityFenceOutcome.HELD)
                }
                if (phase == SpaceMigrationPhase.REVERTED.wireValue &&
                    rows.getString("storage_mode") == AccountStorageMode.LEGACY.wireValue
                ) {
                    refuseSpaceUpload()
                }
            }
        }
}

private fun refuseSpaceUpload(): Nothing = throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
