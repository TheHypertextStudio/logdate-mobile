package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.migration.SpaceMigrationConfig
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase
import java.util.UUID
import kotlin.time.Instant

/** The original account stays locked through the caller's legacy collection mutation. */
internal fun requireLegacyCollectionWrite(
    accountId: UUID,
    config: SpaceMigrationConfig,
    now: Instant,
) {
    requireAccountIdentityWrite(accountId, 0)
    val connection = accountWriteConnection()
    connection.prepareStatement("SELECT storage_mode FROM accounts WHERE id = ?").use {
        it.setObject(1, accountId)
        it.executeQuery().use { rows ->
            if (!rows.next() || rows.getString(1) != AccountStorageMode.LEGACY.wireValue) {
                throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
            }
        }
    }
    // Migration changes take the same account lock first, so this row cannot change before commit.
    connection.prepareStatement("SELECT identity_epoch, phase, hold_started_at FROM space_migrations WHERE account_id = ?").use {
        it.setObject(1, accountId)
        it.executeQuery().use { rows ->
            if (!rows.next()) return
            val phase = SpaceMigrationPhase.fromWireValue(rows.getString("phase"))
            if (rows.getLong("identity_epoch") != 0L ||
                phase !in setOf(SpaceMigrationPhase.NONE, SpaceMigrationPhase.UPLOADING, SpaceMigrationPhase.REVERTED)
            ) {
                throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
            }
            val heldAt =
                rows.getTimestamp("hold_started_at")?.toInstant()?.let {
                    Instant.fromEpochSeconds(it.epochSecond, it.nano)
                }
            if (phase == SpaceMigrationPhase.UPLOADING && heldAt != null && now < heldAt + config.holdLifetime) {
                throw IdentityWriteRefusedException(IdentityFenceOutcome.HELD)
            }
        }
    }
}
