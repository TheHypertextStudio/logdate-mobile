@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionBrowserCancellation
import app.logdate.server.identity.adoption.IdentityAdoptionBrowserCancellations
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import java.security.MessageDigest
import java.sql.Connection
import java.util.Base64
import javax.sql.DataSource
import kotlin.time.Instant

internal class PostgreSQLIdentityAdoptionBrowserCancellation(
    private val source: DataSource,
) : IdentityAdoptionBrowserCancellations {
    override suspend fun cancel(
        binding: IdentityAdoptionOAuthBinding,
        attemptId: String,
        verifier: String,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionBrowserCancellation> {
        if (!attemptId.matches(Regex("[A-Za-z0-9_-]{43}")) || !verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}"))) return refused()
        val attemptKey = identityAdoptionBrowserHash(attemptId)
        val challenge =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            )
        val proofHash = identityAdoptionBrowserChallengeHash(challenge)
        return source.blueskyTransaction {
            lockIdentityAdoptionBrowserProposal(binding.proposalId)
            val account =
                prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE").use {
                    it.setObject(1, binding.accountId.toJavaUUID())
                    it.executeQuery().use { rows -> rows.next() }
                }
            if (!account || !identityAdoptionSourceEligible(binding.accountId, binding.sourceDid, binding.sourceEpoch)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)
            }
            val saved = browser(binding, attemptKey, proofHash) ?: return@blueskyTransaction refused()
            if (saved.canceledAt != null) {
                return@blueskyTransaction IdentityAdoptionOutcome.Accepted(
                    IdentityAdoptionBrowserCancellation(binding, saved.canceledAt),
                )
            }
            if (!cancelOperation(binding, now)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.INVALID_TRANSITION)
            }
            val canceledAt =
                prepareStatement(
                    "UPDATE identity_adoption_browser_proposals SET canceled_at=? WHERE proposal_id=? " +
                        "AND canceled_at IS NULL RETURNING canceled_at",
                ).use {
                    it.setTimestamp(1, now.timestamp())
                    it.setObject(2, binding.proposalId.toJavaUUID())
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getTimestamp(1).connectionInstant()
                    }
                }
            prepareStatement("DELETE FROM bluesky_sign_in_attempts WHERE attempt_key = ?").use {
                it.setString(1, attemptKey)
                it.executeUpdate()
            }
            IdentityAdoptionOutcome.Accepted(IdentityAdoptionBrowserCancellation(binding, canceledAt))
        }
    }

    private fun Connection.browser(
        binding: IdentityAdoptionOAuthBinding,
        attemptKey: String,
        proofHash: String,
    ): Browser? =
        prepareStatement("SELECT * FROM identity_adoption_browser_proposals WHERE proposal_id = ? FOR UPDATE").use {
            it.setObject(1, binding.proposalId.toJavaUUID())
            it.executeQuery().use { rows ->
                if (!rows.next() ||
                    rows.getObject("account_id").toString() != binding.accountId.toString() ||
                    rows.getString("source_did") != binding.sourceDid ||
                    rows.getLong("source_epoch") != binding.sourceEpoch ||
                    rows.getString("device_id") != binding.deviceId ||
                    rows.getString("attempt_key") != attemptKey ||
                    !MessageDigest.isEqual(rows.getString("challenge_hash").toByteArray(), proofHash.toByteArray())
                ) {
                    return@use null
                }
                Browser(rows.getTimestamp("canceled_at")?.connectionInstant())
            }
        }

    private fun Connection.cancelOperation(
        binding: IdentityAdoptionOAuthBinding,
        now: Instant,
    ): Boolean {
        val canCancel =
            prepareStatement(
                "SELECT o.*, EXISTS(SELECT 1 FROM identity_adoption_authorizations a WHERE a.operation_id=o.id) AS authorized " +
                    "FROM identity_adoption_operations o WHERE o.id=? AND o.account_id=? FOR UPDATE",
            ).use {
                it.setString(1, binding.proposalId.toString())
                it.setObject(2, binding.accountId.toJavaUUID())
                it.executeQuery().use { rows ->
                    if (!rows.next()) return true
                    if (rows.getString("source_did") != binding.sourceDid ||
                        rows.getLong("source_epoch") != binding.sourceEpoch ||
                        rows.getString("device_id") != binding.deviceId
                    ) {
                        return false
                    }
                    if (rows.getString("phase") == "CANCELED") return true
                    rows.getString("phase") == "PREPARING" && !rows.getBoolean("authorized")
                }
            }
        if (!canCancel) return false
        prepareStatement(
            "UPDATE identity_adoption_operations SET phase='CANCELED',revision=revision+1,updated_at=? WHERE id=? AND account_id=?",
        ).use {
            it.setTimestamp(1, now.timestamp())
            it.setString(2, binding.proposalId.toString())
            it.setObject(3, binding.accountId.toJavaUUID())
            check(it.executeUpdate() == 1)
        }
        prepareStatement("DELETE FROM identity_adoption_reservations WHERE operation_id=? AND account_id=?").use {
            it.setString(1, binding.proposalId.toString())
            it.setObject(2, binding.accountId.toJavaUUID())
            it.executeUpdate()
        }
        return true
    }

    private data class Browser(
        val canceledAt: Instant?,
    )

    private fun refused() = IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
}
