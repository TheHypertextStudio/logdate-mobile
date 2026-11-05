@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionReceipt
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.identityAdoptionChallengeDigest
import app.logdate.shared.model.AccountStorageMode
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.Instant

internal class IdentityAdoptionCommitRows(
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
) {
    private val binding = operation.binding
    private val account = binding.accountId.toJavaUUID()

    fun lockAccount(): Boolean =
        connection.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE").use {
            it.setObject(1, account)
            it.executeQuery().use { rows -> rows.next() }
        }

    fun targetAvailable(): Boolean =
        connection
            .prepareStatement(
                "SELECT NOT EXISTS(SELECT 1 FROM accounts WHERE did = ?) " +
                    "AND NOT EXISTS(SELECT 1 FROM hosted_plc_operations WHERE did = ?) " +
                    "AND NOT EXISTS(SELECT 1 FROM retired_identity_dids WHERE source_did = ?) " +
                    "AND NOT EXISTS(SELECT 1 FROM identity_adoption_reservations WHERE target_did = ? AND operation_id <> ?)",
            ).use {
                for (i in 1..4) it.setString(i, binding.targetDid)
                it.setString(5, operation.id)
                it.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getBoolean(1)
                }
            }

    fun replay(
        challenge: IdentityAdoptionActionChallenge,
        prepared: PreparedIdentityAdoptionCommit,
    ): IdentityAdoptionReceipt? =
        connection
            .prepareStatement(
                "SELECT r.*,o.device_id,o.source_epoch,o.revision,o.phase FROM identity_adoption_receipts r " +
                    "JOIN identity_adoption_operations o ON o.id = r.operation_id AND o.account_id = r.account_id " +
                    "JOIN accounts a ON a.id = r.account_id AND a.did = r.target_did AND a.identity_epoch = r.identity_epoch " +
                    "AND a.identity_kind = 'external' AND a.storage_mode = ? " +
                    "WHERE r.operation_id = ? AND r.account_id = ?",
            ).use {
                it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                it.setString(2, operation.id)
                it.setObject(3, account)
                it.executeQuery().use { rows ->
                    if (!rows.next() || !rows.matches(challenge, prepared)) null else rows.receipt()
                }
            }

    private fun ResultSet.matches(
        challenge: IdentityAdoptionActionChallenge,
        prepared: PreparedIdentityAdoptionCommit,
    ): Boolean =
        getString("source_did") == binding.sourceDid &&
            getString("target_did") == binding.targetDid &&
            getString("device_id") == binding.deviceId &&
            getLong("source_epoch") == binding.sourceEpoch &&
            binding.sourceEpoch < Long.MAX_VALUE &&
            getLong("identity_epoch") == binding.sourceEpoch + 1 &&
            getString("phase") == "COMMITTED" &&
            operation.revision < Long.MAX_VALUE &&
            getLong("revision") == operation.revision + 1 &&
            getLong("verified_revision") == operation.revision &&
            getString("manifest_digest") == prepared.request.manifestDigest &&
            getString("coverage_digest") == prepared.request.coverageDigest &&
            getString("commit_body_digest") == prepared.bodyDigest &&
            getObject("commit_challenge_id") == challenge.id.toJavaUUID() &&
            getString("commit_nonce_hash") == identityAdoptionChallengeDigest(challenge.nonce) &&
            getString("commit_signature_digest") == prepared.signatureDigest

    fun finish(
        challenge: IdentityAdoptionActionChallenge,
        prepared: PreparedIdentityAdoptionCommit,
        now: Instant,
    ): IdentityAdoptionReceipt {
        stagingCheck(binding.sourceEpoch < Long.MAX_VALUE && operation.revision < Long.MAX_VALUE, IdentityAdoptionStagingFailure.CONFLICT)
        IdentityAdoptionCutover(connection, operation).apply(now)
        return connection
            .prepareStatement(
                "INSERT INTO identity_adoption_receipts(operation_id,account_id,source_did,target_did,identity_epoch,committed_at," +
                    "verified_revision,manifest_digest,coverage_digest,commit_body_digest," +
                    "commit_challenge_id,commit_nonce_hash,commit_signature_digest) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, account)
                it.setString(3, binding.sourceDid)
                it.setString(4, binding.targetDid)
                it.setLong(5, binding.sourceEpoch + 1)
                it.setTimestamp(6, now.timestamp())
                it.setLong(7, operation.revision)
                it.setString(8, prepared.request.manifestDigest)
                it.setString(9, prepared.request.coverageDigest)
                it.setString(10, prepared.bodyDigest)
                it.setObject(11, challenge.id.toJavaUUID())
                it.setString(12, identityAdoptionChallengeDigest(challenge.nonce))
                it.setString(13, prepared.signatureDigest)
                it.executeQuery().use { rows ->
                    check(rows.next())
                    rows.receipt()
                }
            }
    }

    private fun ResultSet.receipt() =
        IdentityAdoptionReceipt(
            operation.id,
            binding.accountId,
            getString("source_did"),
            getString("target_did"),
            getLong("identity_epoch"),
            getTimestamp("committed_at").connectionInstant(),
        )
}
