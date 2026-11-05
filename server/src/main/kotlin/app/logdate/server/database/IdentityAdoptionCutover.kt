@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.shared.model.AccountStorageMode
import java.sql.Connection
import kotlin.time.Instant

/** Keeps app account, device sessions and root envelopes; retires only old hosted AT authority. */
internal class IdentityAdoptionCutover(
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
) {
    private val binding = operation.binding
    private val account = binding.accountId.toJavaUUID()

    fun apply(now: Instant) {
        connection
            .prepareStatement(
                "UPDATE accounts SET did = ?,identity_kind = 'external',storage_mode = ?,identity_epoch = ?," +
                    "handle = NULL,signing_key_public = NULL,plc_recovery_did_key = NULL,public_identity_did = NULL " +
                    "WHERE id = ? AND did = ? AND identity_epoch = ? AND identity_kind = 'hosted'",
            ).use {
                it.setString(1, binding.targetDid)
                it.setString(2, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                it.setLong(3, binding.sourceEpoch + 1)
                it.setObject(4, account)
                it.setString(5, binding.sourceDid)
                it.setLong(6, binding.sourceEpoch)
                stagingCheck(it.executeUpdate() == 1, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            }
        revokeHostedAuthority(now)
        connection
            .prepareStatement(
                "INSERT INTO retired_identity_dids(source_did,account_id,operation_id,identity_epoch,retired_at) VALUES (?,?,?,?,?)",
            ).use {
                it.setString(1, binding.sourceDid)
                it.setObject(2, account)
                it.setString(3, operation.id)
                it.setLong(4, binding.sourceEpoch + 1)
                it.setTimestamp(5, now.timestamp())
                check(it.executeUpdate() == 1)
            }
        finishOperation(now)
    }

    private fun revokeHostedAuthority(now: Instant) {
        connection
            .prepareStatement(
                "UPDATE signing_keys SET revoked_at = ? WHERE account_id = ? AND purpose = 'atproto' AND revoked_at IS NULL",
            ).use {
                it.setTimestamp(1, now.timestamp())
                it.setObject(2, account)
                it.executeUpdate()
            }
        connection
            .prepareStatement(
                "INSERT INTO oauth_subject_revocations(subject_did,revoked_at) VALUES (?,CURRENT_TIMESTAMP) " +
                    "ON CONFLICT(subject_did) DO UPDATE SET revoked_at = " +
                    "GREATEST(oauth_subject_revocations.revoked_at,EXCLUDED.revoked_at)",
            ).use {
                it.setString(1, binding.sourceDid)
                it.executeUpdate()
            }
        connection.prepareStatement("DELETE FROM oauth_authorization_codes WHERE subject_did = ?").use {
            it.setString(1, binding.sourceDid)
            it.executeUpdate()
        }
        connection
            .prepareStatement(
                "UPDATE oauth_refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE subject_did = ? AND revoked_at IS NULL",
            ).use {
                it.setString(1, binding.sourceDid)
                it.executeUpdate()
            }
    }

    private fun finishOperation(now: Instant) {
        connection
            .prepareStatement(
                "UPDATE identity_adoption_operations SET phase = 'COMMITTED',revision = revision + 1,updated_at = ? " +
                    "WHERE id = ? AND account_id = ? AND revision = ? AND phase = 'VERIFIED' AND lease_expires_at > ?",
            ).use {
                it.setTimestamp(1, now.timestamp())
                it.setString(2, operation.id)
                it.setObject(3, account)
                it.setLong(4, operation.revision)
                it.setTimestamp(5, now.timestamp())
                stagingCheck(it.executeUpdate() == 1, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            }
        connection
            .prepareStatement(
                "DELETE FROM identity_adoption_reservations WHERE operation_id = ? AND account_id = ? AND target_did = ?",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, account)
                it.setString(3, binding.targetDid)
                stagingCheck(it.executeUpdate() == 1, IdentityAdoptionStagingFailure.CONFLICT)
            }
    }
}
