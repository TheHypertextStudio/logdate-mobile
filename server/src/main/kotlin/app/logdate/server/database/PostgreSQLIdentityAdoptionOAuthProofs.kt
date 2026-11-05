@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.identityAdoptionDecisionBindingDigest
import java.sql.Connection
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Called with the proposal's target-claim and account locks held, after authenticating OAuth. */
internal fun Connection.recordIdentityAdoptionOAuthProof(
    operation: IdentityAdoptionOperation,
    expiresAt: Instant,
    now: Instant,
): Boolean {
    if (operation.phase != IdentityAdoptionPhase.PREPARING ||
        operation.revision != 1L ||
        expiresAt <= now ||
        expiresAt > now + 300.seconds
    ) {
        return false
    }
    val hash = identityAdoptionDecisionBindingDigest(operation, null)
    prepareStatement(
        "INSERT INTO identity_adoption_oauth_proofs(operation_id, binding_hash, created_at, expires_at) " +
            "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
    ).use {
        it.setString(1, operation.id)
        it.setString(2, hash)
        it.setTimestamp(3, now.timestamp())
        it.setTimestamp(4, expiresAt.timestamp())
        it.executeUpdate()
    }
    return prepareStatement(
        "SELECT EXISTS(SELECT 1 FROM identity_adoption_oauth_proofs " +
            "WHERE operation_id = ? AND binding_hash = ? AND expires_at = ?)",
    ).use {
        it.setString(1, operation.id)
        it.setString(2, hash)
        it.setTimestamp(3, expiresAt.timestamp())
        it.executeQuery().use { rows ->
            check(rows.next())
            rows.getBoolean(1)
        }
    }
}

internal fun Connection.hasIdentityAdoptionOAuthProof(
    operation: IdentityAdoptionOperation,
    now: Instant,
): Boolean =
    prepareStatement(
        "SELECT EXISTS(SELECT 1 FROM identity_adoption_oauth_proofs " +
            "WHERE operation_id = ? AND binding_hash = ? AND created_at <= ? AND expires_at > ?)",
    ).use {
        it.setString(1, operation.id)
        it.setString(2, identityAdoptionDecisionBindingDigest(operation, null))
        it.setTimestamp(3, now.timestamp())
        it.setTimestamp(4, now.timestamp())
        it.executeQuery().use { rows ->
            check(rows.next())
            rows.getBoolean(1)
        }
    }
