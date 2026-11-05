@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionActionProof
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceKey
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.identityAdoptionChallengeDigest
import app.logdate.server.identity.adoption.identityAdoptionDecisionBindingDigest
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Only private operation SQL belongs in consume; provider I/O is admitted and finalized separately. */
internal class PostgreSQLIdentityAdoptionActions(
    private val source: DataSource,
    private val clock: Clock? = null,
) {
    suspend fun issue(
        operation: IdentityAdoptionOperation,
        action: IdentityAdoptionAction,
        bodyDigest: String,
        now: Instant,
    ): IdentityAdoptionActionChallenge? =
        source.blueskyTransaction {
            val key = currentKey(operation, now, expireHold = true) ?: return@blueskyTransaction null
            val lockedAt = clock?.now() ?: now
            val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { RANDOM.nextBytes(it) })
            val challenge = IdentityAdoptionActionChallenge(Uuid.random(), nonce, action, bodyDigest)
            if (IdentityAdoptionActionProof.message(operation, challenge, key) == null || !hasChallengeCapacity(operation, lockedAt)) {
                return@blueskyTransaction null
            }
            val expiry =
                if (operation.phase == IdentityAdoptionPhase.PREPARING) {
                    lockedAt + 30.seconds
                } else {
                    minOf(lockedAt + 30.seconds, currentLease(operation))
                }
            if (expiry <= lockedAt) return@blueskyTransaction null
            prepareStatement(
                "INSERT INTO identity_adoption_action_challenges(id, operation_id, operation_revision, operation_phase, " +
                    "action, body_digest, nonce_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setObject(1, challenge.id.toJavaUUID())
                it.setString(2, operation.id)
                it.setLong(3, operation.revision)
                it.setString(4, operation.phase.name)
                it.setString(5, action.name)
                it.setString(6, bodyDigest)
                it.setString(7, identityAdoptionChallengeDigest(nonce))
                it.setTimestamp(8, lockedAt.timestamp())
                it.setTimestamp(9, expiry.timestamp())
                check(it.executeUpdate() == 1)
            }
            challenge
        }

    /** COMMIT needs a separate DID-claim-first transaction and cannot use this account-first path. */
    suspend fun <T> consume(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        now: Instant,
        mutation: Connection.() -> T,
    ): T? =
        withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction {
                if (challenge.action == IdentityAdoptionAction.COMMIT) return@blueskyTransaction null
                val key = currentKey(operation, now) ?: return@blueskyTransaction null
                val lockedAt = clock?.now() ?: now
                if (!IdentityAdoptionActionProof.verify(operation, challenge, key, signature)) return@blueskyTransaction null
                if (!consumeChallenge(operation, challenge, lockedAt)) return@blueskyTransaction null
                val result = mutation()
                prepareStatement(
                    "UPDATE identity_adoption_operations SET updated_at=GREATEST(updated_at,?) WHERE id=? AND account_id=?",
                ).use {
                    it.setTimestamp(1, lockedAt.timestamp())
                    it.setString(2, operation.id)
                    it.setObject(3, operation.binding.accountId.toJavaUUID())
                    check(it.executeUpdate() == 1)
                }
                result
            }
        }

    internal fun Connection.currentKey(
        operation: IdentityAdoptionOperation,
        now: Instant,
        expireHold: Boolean = false,
    ): IdentityAdoptionDeviceKey? {
        val b = operation.binding
        val owner =
            prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE").use {
                it.setObject(1, b.accountId.toJavaUUID())
                it.executeQuery().use { rows -> rows.next() }
            }
        if (!owner || !identityAdoptionEligible(b)) return null
        return prepareStatement(
            "SELECT o.*, a.operation_revision AS authorized_revision, a.binding_hash, a.device_origin, a.device_public_key " +
                "FROM identity_adoption_operations o JOIN identity_adoption_reservations r " +
                "ON r.operation_id = o.id AND r.account_id = o.account_id AND r.target_did = o.target_did " +
                "JOIN identity_adoption_authorizations a ON a.operation_id = o.id " +
                "WHERE o.id = ? AND o.account_id = ? FOR UPDATE OF o",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, b.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                if (!rows.next() ||
                    rows.getLong("revision") != operation.revision ||
                    rows.getString("phase") != operation.phase.name ||
                    rows.getString("source_did") != b.sourceDid ||
                    rows.getString("target_did") != b.targetDid ||
                    rows.getLong("source_epoch") != b.sourceEpoch ||
                    rows.getString("device_id") != b.deviceId
                ) {
                    return null
                }
                val revision = rows.getLong("authorized_revision")
                if (revision <= 0 || revision > operation.revision) return null
                val key = IdentityAdoptionDeviceKey(rows.getString("device_origin"), rows.getString("device_public_key"))
                if (rows.getString("binding_hash") != identityAdoptionDecisionBindingDigest(operation.copy(revision = revision), key)) {
                    return null
                }
                val lockedAt = clock?.now() ?: now
                if (operation.phase != IdentityAdoptionPhase.PREPARING &&
                    rows.getTimestamp("lease_expires_at").connectionInstant() <= lockedAt
                ) {
                    if (expireHold) invalidateExpiredHold(operation, lockedAt)
                    return null
                }
                key
            }
        }
    }

    private fun Connection.invalidateExpiredHold(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ) {
        check(operation.phase == IdentityAdoptionPhase.FENCED || operation.phase == IdentityAdoptionPhase.VERIFIED)
        prepareStatement(
            "UPDATE identity_adoption_operations SET phase = 'PREPARING', revision = revision + 1, updated_at = ? " +
                "WHERE id = ? AND account_id = ? AND revision = ? AND phase = ? AND lease_expires_at <= ?",
        ).use {
            it.setTimestamp(1, now.timestamp())
            it.setString(2, operation.id)
            it.setObject(3, operation.binding.accountId.toJavaUUID())
            it.setLong(4, operation.revision)
            it.setString(5, operation.phase.name)
            it.setTimestamp(6, now.timestamp())
            check(it.executeUpdate() == 1)
        }
    }

    private fun Connection.currentLease(operation: IdentityAdoptionOperation): Instant =
        prepareStatement("SELECT lease_expires_at FROM identity_adoption_operations WHERE id = ?").use {
            it.setString(1, operation.id)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getTimestamp(1).connectionInstant()
            }
        }

    private fun Connection.hasChallengeCapacity(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): Boolean =
        prepareStatement(
            "SELECT count(*) FROM identity_adoption_action_challenges " +
                "WHERE operation_id = ? AND expires_at > ? AND consumed_at IS NULL",
        ).use {
            it.setString(1, operation.id)
            it.setTimestamp(2, now.timestamp())
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1) < 32
            }
        }

    internal fun Connection.consumeChallenge(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        now: Instant,
    ): Boolean =
        prepareStatement(
            "UPDATE identity_adoption_action_challenges SET consumed_at = ? WHERE id = ? AND operation_id = ? " +
                "AND operation_revision = ? AND operation_phase = ? AND action = ? AND body_digest = ? AND nonce_hash = ? " +
                "AND created_at <= ? AND expires_at > ? AND consumed_at IS NULL",
        ).use {
            it.setTimestamp(1, now.timestamp())
            it.setObject(2, challenge.id.toJavaUUID())
            it.setString(3, operation.id)
            it.setLong(4, operation.revision)
            it.setString(5, operation.phase.name)
            it.setString(6, challenge.action.name)
            it.setString(7, challenge.bodyDigest)
            it.setString(8, identityAdoptionChallengeDigest(challenge.nonce))
            it.setTimestamp(9, now.timestamp())
            it.setTimestamp(10, now.timestamp())
            it.executeUpdate() == 1
        }

    private companion object {
        val RANDOM = SecureRandom()
    }
}
