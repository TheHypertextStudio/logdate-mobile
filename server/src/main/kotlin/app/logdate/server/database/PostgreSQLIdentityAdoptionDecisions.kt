@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionDecision
import app.logdate.server.identity.adoption.IdentityAdoptionDecisionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceKey
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceProof
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.identityAdoptionChallengeDigest
import app.logdate.server.identity.adoption.identityAdoptionDecisionBindingDigest
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Short account-first transactions; proofs and provider work never execute while these locks are held. */
internal class PostgreSQLIdentityAdoptionDecisions(
    private val source: DataSource,
) : IdentityAdoptionDecisionRepository {
    override suspend fun create(
        id: Uuid,
        operation: IdentityAdoptionOperation,
        challenge: String,
        now: Instant,
        deviceKey: IdentityAdoptionDeviceKey?,
    ): IdentityAdoptionDecision? {
        if (deviceKey != null && IdentityAdoptionDeviceProof.message(operation, id, challenge, deviceKey) == null) return null
        return source.blueskyTransaction {
            if (challenge.isBlank() || challenge.length > 512 || !current(operation, now)) return@blueskyTransaction null
            prepareStatement(
                "INSERT INTO identity_adoption_decisions(id, operation_id, account_id, operation_revision, " +
                    "binding_hash, challenge_hash, created_at, expires_at, device_origin, device_public_key) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT DO NOTHING",
            ).use {
                it.setObject(1, id.toJavaUUID())
                it.setString(2, operation.id)
                it.setObject(3, operation.binding.accountId.toJavaUUID())
                it.setLong(4, operation.revision)
                it.setString(5, identityAdoptionDecisionBindingDigest(operation, deviceKey))
                it.setString(6, identityAdoptionChallengeDigest(challenge))
                it.setTimestamp(7, now.timestamp())
                it.setTimestamp(8, (now + 300.seconds).timestamp())
                it.setString(9, deviceKey?.origin)
                it.setString(10, deviceKey?.publicKey)
                it.executeUpdate()
            }
            decision(id, operation, now)?.takeIf { it.matchesChallenge(challenge) && it.deviceKey == deviceKey }
        }
    }

    override suspend fun get(
        id: Uuid,
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionDecision? =
        source.blueskyTransaction {
            if (!current(operation, now)) return@blueskyTransaction null
            decision(id, operation, now)
        }

    /** Called only after the matching decision's registered passkey assertion verifies. */
    override suspend fun authorize(
        id: Uuid,
        operation: IdentityAdoptionOperation,
        challenge: String,
        now: Instant,
    ): Boolean =
        source.blueskyTransaction {
            if (!current(operation, now)) return@blueskyTransaction false
            val record = decision(id, operation, now) ?: return@blueskyTransaction false
            if (!record.matchesChallenge(challenge) || record.consumedAt != null) return@blueskyTransaction false
            if (record.authorizedAt != null) return@blueskyTransaction true
            prepareStatement("UPDATE identity_adoption_decisions SET authorized_at = ? WHERE id = ? AND authorized_at IS NULL").use {
                it.setTimestamp(1, now.timestamp())
                it.setObject(2, id.toJavaUUID())
                it.executeUpdate() == 1
            }
        }

    /** The mutation must use this connection only. Never call a suspending repository or provider here. */
    suspend fun <T : Any> consume(
        id: Uuid,
        operation: IdentityAdoptionOperation,
        now: Instant,
        action: (Connection) -> T,
    ): T? =
        source.blueskyTransaction {
            // Matches identity-claim-before-account ordering used by account creation and adoption begin.
            lockIdentityClaim(operation.binding.targetDid)
            if (!current(operation, now)) return@blueskyTransaction null
            val record = decision(id, operation, now) ?: return@blueskyTransaction null
            if (record.authorizedAt == null || record.consumedAt != null) return@blueskyTransaction null
            val used =
                prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM identity_adoption_decisions " +
                        "WHERE operation_id = ? AND operation_revision = ? AND consumed_at IS NOT NULL)",
                ).use {
                    it.setString(1, operation.id)
                    it.setLong(2, operation.revision)
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getBoolean(1)
                    }
                }
            if (used) return@blueskyTransaction null
            prepareStatement("UPDATE identity_adoption_decisions SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL").use {
                it.setTimestamp(1, now.timestamp())
                it.setObject(2, id.toJavaUUID())
                check(it.executeUpdate() == 1)
            }
            action(this)
        }

    private fun Connection.current(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): Boolean {
        if (operation.phase != IdentityAdoptionPhase.PREPARING || operation.revision <= 0) return false
        val binding = operation.binding
        val expectation = IdentityWriteExpectation(binding.accountId, binding.sourceEpoch)
        if (PostgreSQLAccountIdentityFence(this).lockAndCheck(expectation, now) != IdentityFenceOutcome.ALLOWED) return false
        if (!identityAdoptionEligible(binding)) return false
        return prepareStatement(
            "SELECT o.source_did, o.target_did, o.device_id, o.source_epoch, o.revision, o.phase " +
                "FROM identity_adoption_operations o JOIN identity_adoption_reservations r " +
                "ON r.operation_id = o.id AND r.account_id = o.account_id AND r.target_did = o.target_did " +
                "WHERE o.id = ? AND o.account_id = ? FOR UPDATE OF o",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, binding.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                rows.next() &&
                    rows.getLong("revision") == operation.revision &&
                    rows.getString("phase") == IdentityAdoptionPhase.PREPARING.name &&
                    rows.getString("source_did") == binding.sourceDid &&
                    rows.getString("target_did") == binding.targetDid &&
                    rows.getString("device_id") == binding.deviceId &&
                    rows.getLong("source_epoch") == binding.sourceEpoch
            }
        }
    }

    private fun Connection.decision(
        id: Uuid,
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionDecision? =
        prepareStatement(
            "SELECT * FROM identity_adoption_decisions WHERE id = ? AND operation_id = ? AND account_id = ? " +
                "AND operation_revision = ? AND created_at <= ? AND expires_at > ? FOR UPDATE",
        ).use {
            it.setObject(1, id.toJavaUUID())
            it.setString(2, operation.id)
            it.setObject(3, operation.binding.accountId.toJavaUUID())
            it.setLong(4, operation.revision)
            it.setTimestamp(5, now.timestamp())
            it.setTimestamp(6, now.timestamp())
            it.executeQuery().use { rows ->
                if (rows.next()) {
                    rows.decision().takeIf { saved ->
                        saved.bindingHash == identityAdoptionDecisionBindingDigest(operation, saved.deviceKey)
                    }
                } else {
                    null
                }
            }
        }

    private fun ResultSet.decision() =
        IdentityAdoptionDecision(
            id = Uuid.parse(getString("id")),
            operationId = getString("operation_id"),
            accountId = Uuid.parse(getString("account_id")),
            revision = getLong("operation_revision"),
            bindingHash = getString("binding_hash"),
            challengeHash = getString("challenge_hash"),
            createdAt = getTimestamp("created_at").connectionInstant(),
            expiresAt = getTimestamp("expires_at").connectionInstant(),
            authorizedAt = getTimestamp("authorized_at")?.connectionInstant(),
            consumedAt = getTimestamp("consumed_at")?.connectionInstant(),
            deviceKey = getString("device_origin")?.let { IdentityAdoptionDeviceKey(it, getString("device_public_key")) },
        )
}
