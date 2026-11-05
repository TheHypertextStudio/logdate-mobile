@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAuthorization
import app.logdate.server.identity.adoption.IdentityAdoptionAuthorizationRepository
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceKey
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.identityAdoptionDecisionBindingDigest
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class PostgreSQLIdentityAdoptionAuthorizations(
    private val source: DataSource,
) : IdentityAdoptionAuthorizationRepository {
    override suspend fun canAuthorize(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): Boolean =
        source.blueskyTransaction {
            current(operation, advanced = false) && read(operation) == null && hasIdentityAdoptionOAuthProof(operation, now)
        }

    override suspend fun get(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionAuthorization? =
        source.blueskyTransaction {
            if (!current(operation, advanced = true)) return@blueskyTransaction null
            read(operation)
        }

    override suspend fun authorize(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        now: Instant,
    ): IdentityAdoptionAuthorization? {
        get(operation, now)?.let { return it.takeIf { saved -> saved.decisionId == decisionId && saved.revision == operation.revision } }
        if (!canAuthorize(operation, now)) return existing(operation, decisionId, now)
        val decisions = PostgreSQLIdentityAdoptionDecisions(source)
        val decision = decisions.get(decisionId, operation, now) ?: return existing(operation, decisionId, now)
        val key = decision.deviceKey ?: return null
        if (decision.authorizedAt == null) return null
        if (decision.consumedAt != null) return existing(operation, decisionId, now)
        val saved =
            decisions.consume(decisionId, operation, now) { connection ->
                check(connection.hasIdentityAdoptionOAuthProof(operation, now))
                connection
                    .prepareStatement(
                        "INSERT INTO identity_adoption_authorizations(operation_id, decision_id, operation_revision, " +
                            "binding_hash, challenge_hash, device_origin, device_public_key, authorized_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *",
                    ).use {
                        it.setString(1, operation.id)
                        it.setObject(2, decisionId.toJavaUUID())
                        it.setLong(3, operation.revision)
                        it.setString(4, decision.bindingHash)
                        it.setString(5, decision.challengeHash)
                        it.setString(6, key.origin)
                        it.setString(7, key.publicKey)
                        it.setTimestamp(8, now.timestamp())
                        it.executeQuery().use { rows ->
                            check(rows.next())
                            rows.authorization(operation)
                        }
                    }
            }
        return saved ?: existing(operation, decisionId, now)
    }

    private suspend fun existing(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        now: Instant,
    ): IdentityAdoptionAuthorization? = get(operation, now)?.takeIf { it.decisionId == decisionId && it.revision == operation.revision }

    /** Metadata reads may observe this operation's hold; this never grants a mutation exemption. */
    private fun Connection.current(
        operation: IdentityAdoptionOperation,
        advanced: Boolean,
    ): Boolean {
        if (operation.revision <= 0 || operation.phase !in ACTIVE_PHASES) return false
        val b = operation.binding
        val exists =
            prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE").use {
                it.setObject(1, b.accountId.toJavaUUID())
                it.executeQuery().use { rows -> rows.next() }
            }
        if (!exists || !identityAdoptionEligible(b)) return false
        val comparison = if (advanced) ">=" else "="
        val phases = if (advanced) "('PREPARING','FENCED','VERIFIED')" else "('PREPARING')"
        return prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM identity_adoption_operations o JOIN identity_adoption_reservations r " +
                "ON r.operation_id = o.id AND r.account_id = o.account_id AND r.target_did = o.target_did " +
                "WHERE o.id = ? AND o.account_id = ? AND o.source_did = ? AND o.target_did = ? " +
                "AND o.source_epoch = ? AND o.device_id = ? AND o.revision $comparison ? AND o.phase IN $phases)",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, b.accountId.toJavaUUID())
            it.setString(3, b.sourceDid)
            it.setString(4, b.targetDid)
            it.setLong(5, b.sourceEpoch)
            it.setString(6, b.deviceId)
            it.setLong(7, operation.revision)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }
    }

    private fun Connection.read(operation: IdentityAdoptionOperation): IdentityAdoptionAuthorization? =
        prepareStatement(
            "SELECT * FROM identity_adoption_authorizations WHERE operation_id = ? AND operation_revision <= ?",
        ).use {
            it.setString(1, operation.id)
            it.setLong(2, operation.revision)
            it.executeQuery().use { rows ->
                if (rows.next()) {
                    val saved = rows.authorization(operation)
                    val bound = operation.copy(revision = saved.revision)
                    val expected = identityAdoptionDecisionBindingDigest(bound, saved.deviceKey)
                    saved.takeIf { it.bindingHash == expected }
                } else {
                    null
                }
            }
        }

    private fun ResultSet.authorization(operation: IdentityAdoptionOperation): IdentityAdoptionAuthorization =
        IdentityAdoptionAuthorization(
            operationId = getString("operation_id"),
            accountId = operation.binding.accountId,
            revision = getLong("operation_revision"),
            decisionId = Uuid.parse(getString("decision_id")),
            bindingHash = getString("binding_hash"),
            challengeHash = getString("challenge_hash"),
            deviceKey = IdentityAdoptionDeviceKey(getString("device_origin"), getString("device_public_key")),
            authorizedAt = getTimestamp("authorized_at").connectionInstant(),
        )

    private companion object {
        val ACTIVE_PHASES = setOf(IdentityAdoptionPhase.PREPARING, IdentityAdoptionPhase.FENCED, IdentityAdoptionPhase.VERIFIED)
    }
}
