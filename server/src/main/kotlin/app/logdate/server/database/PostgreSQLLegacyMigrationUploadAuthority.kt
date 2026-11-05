@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase

/** The caller already owns the account lock; bind the copy to its original migration snapshot. */
internal fun requireLegacyMigrationUploadAuthority(
    origin: IdentityWriteExpectation,
    migrationId: String?,
    migrationVersion: Long?,
) {
    if (migrationId == null && migrationVersion == null) return
    if (migrationId.isNullOrEmpty() || migrationVersion == null || migrationVersion <= 0) refuseMigrationUpload()
    accountWriteConnection()
        .prepareStatement(
            "SELECT m.migration_id, m.version, m.identity_epoch, m.phase, a.storage_mode " +
                "FROM space_migrations m JOIN accounts a ON a.id = m.account_id " +
                "WHERE m.account_id = ? FOR UPDATE OF m",
        ).use {
            it.setObject(1, origin.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                if (!rows.next() ||
                    rows.getString(1) != migrationId ||
                    rows.getLong(2) != migrationVersion ||
                    rows.getLong(3) != origin.identityEpoch ||
                    rows.getString(4) != SpaceMigrationPhase.UPLOADING.wireValue ||
                    rows.getString(5) != AccountStorageMode.LEGACY.wireValue
                ) {
                    refuseMigrationUpload()
                }
            }
        }
}

private fun refuseMigrationUpload(): Nothing = throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
