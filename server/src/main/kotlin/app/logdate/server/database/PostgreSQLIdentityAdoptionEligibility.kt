@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.shared.model.AccountStorageMode
import java.sql.Connection
import kotlin.uuid.Uuid

/** Mutation authority requires the original account lock; callback observations alone grant nothing. */
internal fun Connection.identityAdoptionEligible(binding: IdentityAdoptionBinding): Boolean =
    identityAdoptionSourceEligible(binding.accountId, binding.sourceDid, binding.sourceEpoch)

internal fun Connection.identityAdoptionSourceEligible(
    accountId: Uuid,
    sourceDid: String,
    sourceEpoch: Long,
): Boolean =
    prepareStatement(
        "SELECT EXISTS(SELECT 1 FROM accounts WHERE id = ? AND did = ? AND identity_epoch = ? " +
            "AND identity_kind = 'hosted' AND storage_mode IN (?, ?) " +
            "AND is_active AND moderation IS NULL AND public_identity_did IS NULL) " +
            "AND (SELECT COUNT(DISTINCT (cid, operation_json)) FROM hosted_plc_operations WHERE account_id = ? AND did = ? " +
            "AND prev_cid IS NULL AND operation_type = 'plc_operation') = 1 " +
            "AND NOT EXISTS(SELECT 1 FROM hosted_plc_operations WHERE did = ? " +
            "AND (account_id <> ? OR publication_attempted IS DISTINCT FROM FALSE)) " +
            "AND NOT EXISTS(SELECT 1 FROM retired_identity_dids WHERE source_did = ?) " +
            "AND NOT EXISTS(SELECT 1 FROM identity_publication_attempts WHERE did = ?)",
    ).use {
        it.setObject(1, accountId.toJavaUUID())
        it.setString(2, sourceDid)
        it.setLong(3, sourceEpoch)
        it.setString(4, AccountStorageMode.LEGACY.wireValue)
        it.setString(5, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
        it.setObject(6, accountId.toJavaUUID())
        it.setString(7, sourceDid)
        it.setString(8, sourceDid)
        it.setObject(9, accountId.toJavaUUID())
        it.setString(10, sourceDid)
        it.setString(11, sourceDid)
        it.executeQuery().use { rows ->
            check(rows.next())
            rows.getBoolean(1)
        }
    }
