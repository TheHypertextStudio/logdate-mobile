package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionAuthorizationStatus
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceKey
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceProof
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionReceipt
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionStatus
import app.logdate.server.identity.adoption.identityAdoptionDecisionBindingDigest
import studio.hypertext.atproto.identity.AtprotoDid
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class PostgreSQLIdentityAdoptionRepository(
    private val source: DataSource,
    private val clock: Clock? = null,
) : IdentityAdoptionRepository {
    override suspend fun beginForProposal(
        binding: IdentityAdoptionBinding,
        proposalId: Uuid,
        now: Instant,
        proofExpiresAt: Instant?,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> =
        source.blueskyTransaction {
            if (proofExpiresAt != null && (proofExpiresAt <= now || proofExpiresAt > now + 300.seconds)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_EXPIRED)
            }
            if (!validBinding(binding)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)
            }
            lockIdentityAdoptionBrowserProposal(proposalId)
            val browser =
                IdentityAdoptionOAuthBinding(binding.accountId, binding.sourceDid, binding.sourceEpoch, binding.deviceId, proposalId)
            if (!identityAdoptionBrowserAllows(browser, requireRecord = proofExpiresAt != null)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
            }
            lockIdentityClaim(binding.targetDid)
            if (!lockAccount(binding.accountId)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.NOT_FOUND)
            }
            findOperation(binding.accountId, "id = ?", proposalId.toString())?.let {
                return@blueskyTransaction replayProposal(it, binding, proofExpiresAt, now)
            }
            val exists =
                prepareStatement("SELECT EXISTS(SELECT 1 FROM identity_adoption_operations WHERE id = ?)").use {
                    it.setString(1, proposalId.toString())
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getBoolean(1)
                    }
                }
            if (exists || findOperation(binding.accountId, "phase IN ('PREPARING', 'FENCED', 'VERIFIED')") != null) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.ACTIVE_OPERATION)
            }
            if (!identityAdoptionEligible(binding)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)
            }
            if (!identityClaimAvailable(binding.targetDid)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.TARGET_CLAIMED)
            }
            val operation =
                IdentityAdoptionOperation(
                    proposalId.toString(),
                    binding,
                    1,
                    IdentityAdoptionPhase.PREPARING,
                    now + HOLD_LIFETIME,
                )
            insert(operation, now)
            if (proofExpiresAt != null) check(recordIdentityAdoptionOAuthProof(operation, proofExpiresAt, now))
            IdentityAdoptionOutcome.Accepted(operation)
        }

    override suspend fun begin(
        binding: IdentityAdoptionBinding,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> =
        source.blueskyTransaction {
            if (binding.sourceDid == binding.targetDid ||
                binding.deviceId.isBlank() ||
                binding.deviceId.length > 255 ||
                AtprotoDid.parse(binding.sourceDid).isFailure ||
                AtprotoDid.parse(binding.targetDid).isFailure
            ) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)
            }
            lockIdentityClaim(binding.targetDid)
            if (!lockAccount(
                    binding.accountId,
                )
            ) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.NOT_FOUND)
            }
            findOperation(binding.accountId, "target_did = ? AND phase = 'COMMITTED'", binding.targetDid)?.let {
                if (it.binding == binding) return@blueskyTransaction IdentityAdoptionOutcome.Accepted(it)
            }
            if (!identityAdoptionEligible(
                    binding,
                )
            ) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)
            }
            findOperation(binding.accountId, "phase IN ('PREPARING', 'FENCED', 'VERIFIED')")?.let {
                return@blueskyTransaction if (it.binding == binding) {
                    IdentityAdoptionOutcome.Accepted(it)
                } else {
                    IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.ACTIVE_OPERATION)
                }
            }
            if (!identityClaimAvailable(binding.targetDid)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.TARGET_CLAIMED)
            }
            val operation =
                IdentityAdoptionOperation(
                    Uuid.random().toString(),
                    binding,
                    1,
                    IdentityAdoptionPhase.PREPARING,
                    now + HOLD_LIFETIME,
                )
            insert(operation, now)
            IdentityAdoptionOutcome.Accepted(operation)
        }

    override suspend fun get(
        accountId: Uuid,
        operationId: String,
    ): IdentityAdoptionOperation? = source.blueskyTransaction { findOperation(accountId, "id = ?", operationId) }

    override suspend fun status(
        accountId: Uuid,
        operationId: String,
    ): IdentityAdoptionStatus? = source.blueskyTransaction { readStatus(accountId, operationId) }

    override suspend fun currentCommitted(accountId: Uuid): IdentityAdoptionStatus? =
        source.blueskyTransaction { readStatus(accountId, null) }

    private fun Connection.readStatus(
        accountId: Uuid,
        operationId: String?,
    ): IdentityAdoptionStatus? {
        return prepareStatement(
            "SELECT o.*, r.account_id AS receipt_account_id, r.source_did AS receipt_source_did, " +
                "r.target_did AS receipt_target_did, r.identity_epoch AS receipt_epoch, r.committed_at AS receipt_committed_at, " +
                "a.operation_revision AS authorization_revision, a.binding_hash AS authorization_hash, " +
                "a.device_origin AS authorization_origin, a.device_public_key AS authorization_key, " +
                "a.authorized_at AS authorization_at, a.decision_id AS authorization_decision_id " +
                "FROM identity_adoption_operations o LEFT JOIN identity_adoption_receipts r ON r.operation_id = o.id " +
                "LEFT JOIN identity_adoption_authorizations a ON a.operation_id = o.id " +
                "JOIN accounts c ON c.id = o.account_id WHERE o.account_id = ? AND " +
                if (operationId != null) {
                    "o.id = ?"
                } else {
                    "o.phase = 'COMMITTED' AND c.identity_kind = 'external' AND c.did = r.target_did " +
                        "AND c.identity_epoch = r.identity_epoch AND r.account_id = c.id"
                },
        ).use {
            it.setObject(1, accountId.toJavaUUID())
            if (operationId != null) it.setString(2, operationId)
            it.executeQuery().use { rows ->
                if (!rows.next()) return null
                val result = rows.adoptionStatus()
                check(!rows.next()) { "Identity change status is ambiguous" }
                result
            }
        }
    }

    private fun ResultSet.adoptionStatus(): IdentityAdoptionStatus {
        val operation = operation()
        val epoch = getLong("receipt_epoch").let { value -> if (wasNull()) null else value }
        val receipt =
            epoch?.let { receiptEpoch ->
                IdentityAdoptionReceipt(
                    operation.id,
                    Uuid.parse(getString("receipt_account_id")),
                    getString("receipt_source_did"),
                    getString("receipt_target_did"),
                    receiptEpoch,
                    instant("receipt_committed_at"),
                )
            }
        check(
            (operation.phase == IdentityAdoptionPhase.COMMITTED) == (receipt != null),
        ) { "Identity change status is inconsistent" }
        if (receipt != null) {
            check(
                receipt.accountId == operation.binding.accountId &&
                    receipt.sourceDid == operation.binding.sourceDid &&
                    receipt.targetDid == operation.binding.targetDid &&
                    operation.binding.sourceEpoch < Long.MAX_VALUE &&
                    receipt.identityEpoch == operation.binding.sourceEpoch + 1,
            ) { "Identity change receipt is inconsistent" }
        }
        return IdentityAdoptionStatus(operation, receipt, statusAuthorization(operation))
    }

    private fun ResultSet.statusAuthorization(operation: IdentityAdoptionOperation): IdentityAdoptionAuthorizationStatus? {
        if (operation.phase in TERMINAL_PHASES) return null
        val revision = getLong("authorization_revision")
        if (wasNull()) return null
        check(revision in 1..operation.revision) { "Identity authorization revision is inconsistent" }
        val key = IdentityAdoptionDeviceKey(getString("authorization_origin"), getString("authorization_key"))
        val original = operation.copy(revision = revision, phase = IdentityAdoptionPhase.PREPARING)
        check(getString("authorization_hash") == identityAdoptionDecisionBindingDigest(original, key)) {
            "Identity authorization binding is inconsistent"
        }
        check(
            IdentityAdoptionDeviceProof.message(original, Uuid.parse(getString("authorization_decision_id")), "A".repeat(43), key) != null,
        ) {
            "Identity authorization key is inconsistent"
        }
        return IdentityAdoptionAuthorizationStatus(revision, key, instant("authorization_at"))
    }

    override suspend fun transition(
        accountId: Uuid,
        operationId: String,
        expectedRevision: Long,
        nextPhase: IdentityAdoptionPhase,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> =
        source.blueskyTransaction {
            if (!lockAccount(accountId)) return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.NOT_FOUND)
            val operation =
                findOperation(accountId, "id = ?", operationId)
                    ?: return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.NOT_FOUND)
            transitionLocked(operation, expectedRevision, nextPhase, now)
        }

    suspend fun abandonUnconfirmed(
        expected: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> =
        source.blueskyTransaction {
            val accountId = expected.binding.accountId
            if (!lockAccount(accountId)) return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.NOT_FOUND)
            val current =
                findOperation(accountId, "id = ?", expected.id)
                    ?: return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.NOT_FOUND)
            val authorized =
                prepareStatement("SELECT EXISTS(SELECT 1 FROM identity_adoption_authorizations WHERE operation_id = ?)").use {
                    it.setString(1, current.id)
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getBoolean(1)
                    }
                }
            if (current != expected || authorized) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
            }
            if (current.phase == IdentityAdoptionPhase.CANCELED) return@blueskyTransaction IdentityAdoptionOutcome.Accepted(current)
            if (current.phase != IdentityAdoptionPhase.PREPARING || !identityAdoptionEligible(current.binding)) {
                return@blueskyTransaction IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)
            }
            transitionLocked(current, expected.revision, IdentityAdoptionPhase.CANCELED, now)
        }

    suspend fun transitionWithProof(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> {
        val phase =
            when (challenge.action) {
                IdentityAdoptionAction.HOLD, IdentityAdoptionAction.RENEW -> IdentityAdoptionPhase.FENCED
                IdentityAdoptionAction.CANCEL -> IdentityAdoptionPhase.CANCELED
                else -> return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
            }
        // These lifecycle actions carry no additional payload; revision and binding are already signed.
        if (challenge.bodyDigest != EMPTY_BODY_DIGEST) return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
        return PostgreSQLIdentityAdoptionActions(source, clock).consume(operation, challenge, signature, now) {
            val current =
                findOperation(operation.binding.accountId, "id = ?", operation.id)
                    ?: return@consume IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.NOT_FOUND)
            transitionLocked(current, operation.revision, phase, now)
        } ?: IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
    }

    private fun Connection.transitionLocked(
        operation: IdentityAdoptionOperation,
        expectedRevision: Long,
        nextPhase: IdentityAdoptionPhase,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> {
        val transitionAt = clock?.now() ?: now
        val accountId = operation.binding.accountId
        val operationId = operation.id
        if (operation.revision != expectedRevision) {
            return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.STALE_REVISION)
        }
        if (operation.phase in HELD_PHASES && operation.leaseExpiresAt <= transitionAt) {
            update(operation.copy(revision = operation.revision + 1, phase = IdentityAdoptionPhase.PREPARING), transitionAt)
            return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.LEASE_EXPIRED)
        }
        if (operation.phase in TERMINAL_PHASES || nextPhase !in setOf(IdentityAdoptionPhase.CANCELED, IdentityAdoptionPhase.FENCED)) {
            return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.INVALID_TRANSITION)
        }
        if (nextPhase == IdentityAdoptionPhase.FENCED && hasPendingIdentityAdoptionMutation(accountId)) {
            return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING)
        }
        if (nextPhase == IdentityAdoptionPhase.FENCED && !identityAdoptionEligible(operation.binding)) {
            return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)
        }
        val next =
            operation.copy(
                revision = operation.revision + 1,
                phase = nextPhase,
                leaseExpiresAt = if (nextPhase == IdentityAdoptionPhase.FENCED) transitionAt + HOLD_LIFETIME else operation.leaseExpiresAt,
            )
        update(next, transitionAt)
        if (nextPhase == IdentityAdoptionPhase.FENCED && operation.phase == IdentityAdoptionPhase.PREPARING) startHoldGeneration(next)
        if (nextPhase == IdentityAdoptionPhase.CANCELED) {
            prepareStatement("DELETE FROM identity_adoption_reservations WHERE operation_id = ? AND account_id = ?").use {
                it.setString(1, operationId)
                it.setObject(2, accountId.toJavaUUID())
                it.executeUpdate()
            }
        }
        return IdentityAdoptionOutcome.Accepted(next)
    }

    private fun Connection.startHoldGeneration(operation: IdentityAdoptionOperation) {
        prepareStatement("UPDATE identity_adoption_operations SET hold_generation = ? WHERE id = ? AND account_id = ?").use {
            it.setLong(1, operation.revision)
            it.setString(2, operation.id)
            it.setObject(3, operation.binding.accountId.toJavaUUID())
            check(it.executeUpdate() == 1)
        }
    }

    override suspend fun receipt(
        accountId: Uuid,
        operationId: String,
    ): IdentityAdoptionReceipt? =
        source.blueskyTransaction {
            prepareStatement("SELECT * FROM identity_adoption_receipts WHERE operation_id = ? AND account_id = ?").use {
                it.setString(1, operationId)
                it.setObject(2, accountId.toJavaUUID())
                it.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        IdentityAdoptionReceipt(
                            operationId,
                            accountId,
                            rows.getString("source_did"),
                            rows.getString("target_did"),
                            rows.getLong("identity_epoch"),
                            rows.instant("committed_at"),
                        )
                    }
                }
            }
        }

    private fun Connection.lockAccount(accountId: Uuid): Boolean =
        prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE").use {
            it.setObject(1, accountId.toJavaUUID())
            it.executeQuery().use { rows -> rows.next() }
        }

    private fun validBinding(binding: IdentityAdoptionBinding): Boolean =
        binding.sourceDid != binding.targetDid &&
            binding.deviceId.isNotBlank() &&
            binding.deviceId.length <= 255 &&
            AtprotoDid.parse(binding.sourceDid).isSuccess &&
            AtprotoDid.parse(binding.targetDid).isSuccess

    private fun Connection.replayProposal(
        operation: IdentityAdoptionOperation,
        binding: IdentityAdoptionBinding,
        proofExpiresAt: Instant?,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> =
        when {
            operation.binding != binding -> IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)
            operation.phase == IdentityAdoptionPhase.CANCELED -> IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.INVALID_TRANSITION)
            operation.phase != IdentityAdoptionPhase.COMMITTED && !identityAdoptionEligible(binding) ->
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)
            proofExpiresAt != null && !recordIdentityAdoptionOAuthProof(operation, proofExpiresAt, now) ->
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
            else -> IdentityAdoptionOutcome.Accepted(operation)
        }

    private fun Connection.findOperation(
        accountId: Uuid,
        condition: String,
        value: String? = null,
    ): IdentityAdoptionOperation? =
        prepareStatement("SELECT * FROM identity_adoption_operations WHERE account_id = ? AND $condition").use {
            it.setObject(1, accountId.toJavaUUID())
            if (value != null) it.setString(2, value)
            it.executeQuery().use { rows -> if (rows.next()) rows.operation() else null }
        }

    private fun Connection.insert(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ) {
        prepareStatement(
            "INSERT INTO identity_adoption_operations(id, account_id, source_did, target_did, device_id, source_epoch, " +
                "revision, phase, lease_expires_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.setString(3, operation.binding.sourceDid)
            it.setString(4, operation.binding.targetDid)
            it.setString(5, operation.binding.deviceId)
            it.setLong(6, operation.binding.sourceEpoch)
            it.setLong(7, operation.revision)
            it.setString(8, operation.phase.name)
            it.setTimestamp(9, operation.leaseExpiresAt.timestamp())
            it.setTimestamp(10, now.timestamp())
            it.setTimestamp(11, now.timestamp())
            check(it.executeUpdate() == 1)
        }
        prepareStatement("INSERT INTO identity_adoption_reservations(target_did, operation_id, account_id) VALUES (?, ?, ?)").use {
            it.setString(1, operation.binding.targetDid)
            it.setString(2, operation.id)
            it.setObject(3, operation.binding.accountId.toJavaUUID())
            check(it.executeUpdate() == 1)
        }
    }

    private fun Connection.update(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ) {
        prepareStatement(
            "UPDATE identity_adoption_operations SET phase = ?, revision = ?, lease_expires_at = ?, updated_at = ? WHERE id = ? AND account_id = ?",
        ).use {
            it.setString(1, operation.phase.name)
            it.setLong(2, operation.revision)
            it.setTimestamp(3, operation.leaseExpiresAt.timestamp())
            it.setTimestamp(4, now.timestamp())
            it.setString(5, operation.id)
            it.setObject(6, operation.binding.accountId.toJavaUUID())
            check(it.executeUpdate() == 1)
        }
    }

    private fun ResultSet.operation() =
        IdentityAdoptionOperation(
            getString("id"),
            IdentityAdoptionBinding(
                getObject("account_id", java.util.UUID::class.java).toKotlinUuid(),
                getString("source_did"),
                getString("target_did"),
                getString("device_id"),
                getLong("source_epoch"),
            ),
            getLong("revision"),
            IdentityAdoptionPhase.valueOf(getString("phase")),
            instant("lease_expires_at"),
        )

    private fun ResultSet.instant(column: String): Instant =
        getTimestamp(column).toInstant().let {
            Instant.fromEpochSeconds(it.epochSecond, it.nano)
        }

    private companion object {
        const val EMPTY_BODY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val HOLD_LIFETIME = 120.seconds
        val HELD_PHASES = setOf(IdentityAdoptionPhase.FENCED, IdentityAdoptionPhase.VERIFIED)
        val TERMINAL_PHASES = setOf(IdentityAdoptionPhase.COMMITTED, IdentityAdoptionPhase.CANCELED)
    }
}
