package app.logdate.server.database

import app.logdate.server.identity.HostedPlcOperationConflictException
import app.logdate.server.identity.HostedPlcOperationRepository
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.StoredHostedPlcOperation
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.identity.canonicalHostedGenesis
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSQLHostedPlcOperationRepository : HostedPlcOperationRepository {
    override suspend fun save(operation: StoredHostedPlcOperation): StoredHostedPlcOperation =
        try {
            insert(operation)
        } catch (error: ExposedSQLException) {
            if (error.sqlState != UNIQUE_VIOLATION) throw error
            throw HostedPlcOperationConflictException(operation.did, operation.prevCid, error)
        }

    private fun insert(operation: StoredHostedPlcOperation): StoredHostedPlcOperation =
        transaction {
            requireHostedPlcWrite(operation.accountId, operation.did, operation.identityEpoch)
            insertOperation(operation)
        }

    private fun insertOperation(operation: StoredHostedPlcOperation): StoredHostedPlcOperation {
        HostedPlcOperationsTable.insert {
            it[id] = operation.id.toJavaUUID()
            it[accountId] = operation.accountId.toJavaUUID()
            it[did] = operation.did
            it[cid] = operation.cid
            it[prevCid] = operation.prevCid
            it[operationType] = operation.operationType
            it[operationJson] = operation.operationJson
            it[createdAt] = operation.createdAt
            it[publicationAttempted] = operation.publicationAttempted
            it[identityEpoch] = operation.identityEpoch
        }
        if (operation.publicationAttempted == true) recordAttempt(operation.did)
        return operation
    }

    override suspend fun claimGenesis(candidate: StoredHostedPlcOperation): StoredHostedPlcOperation =
        transaction {
            require(candidate.prevCid == null && candidate.publicationAttempted == false)
            lockAccountDidClaims(candidate.did)
            requireHostedIdentityWrite(candidate.accountId, candidate.identityEpoch)
            val history =
                HostedPlcOperationsTable
                    .selectAll()
                    .where {
                        HostedPlcOperationsTable.accountId eq candidate.accountId.toJavaUUID()
                    }.map { it.toStoredHostedPlcOperation() }
            val selected = canonicalHostedGenesis(history, candidate.identityEpoch)
            if (selected != null) {
                requireHostedDidClaim(candidate.accountId, selected.did)
                selected
            } else {
                requireHostedDidClaim(candidate.accountId, candidate.did)
                insertOperation(candidate)
            }
        }

    override suspend fun listByAccountId(accountId: Uuid): List<StoredHostedPlcOperation> =
        transaction {
            HostedPlcOperationsTable
                .selectAll()
                .where { HostedPlcOperationsTable.accountId eq accountId.toJavaUUID() }
                .orderBy(HostedPlcOperationsTable.createdAt, SortOrder.ASC)
                .map { row -> row.toStoredHostedPlcOperation() }
        }

    override suspend fun listByDid(did: String): List<StoredHostedPlcOperation> =
        transaction {
            HostedPlcOperationsTable
                .selectAll()
                .where { HostedPlcOperationsTable.did eq did }
                .orderBy(HostedPlcOperationsTable.createdAt, SortOrder.ASC)
                .map { row -> row.toStoredHostedPlcOperation() }
        }

    override suspend fun recordPublishedCid(
        operationId: Uuid,
        cid: String,
        identityEpoch: Long,
    ): Boolean =
        transaction {
            guardStoredOperation(operationId, identityEpoch) ?: return@transaction false
            val changed =
                HostedPlcOperationsTable.update({ HostedPlcOperationsTable.id eq operationId.toJavaUUID() }) {
                    it[HostedPlcOperationsTable.cid] = cid
                    it[HostedPlcOperationsTable.publicationAttempted] = true
                } > 0
            if (changed) recordOperationAttempt(operationId)
            changed
        }

    override suspend fun markPublicationAttempted(
        operationId: Uuid,
        identityEpoch: Long,
    ): Boolean =
        transaction {
            guardStoredOperation(operationId, identityEpoch) ?: return@transaction false
            val changed =
                HostedPlcOperationsTable.update({ HostedPlcOperationsTable.id eq operationId.toJavaUUID() }) {
                    it[HostedPlcOperationsTable.publicationAttempted] = true
                } > 0
            if (changed) recordOperationAttempt(operationId)
            changed
        }

    override suspend fun beginPublicationAttempt(
        accountId: Uuid,
        did: String,
        identityEpoch: Long,
    ): Boolean {
        if (TransactionManager.currentOrNull() != null) {
            throw IdentityLifecycleConflictException("Publication cannot run inside another database transaction")
        }
        return transaction {
            requireHostedPlcWrite(accountId, did, identityEpoch)
            recordAttempt(did)
            true
        }
    }

    override suspend fun recordPublicationAttempt(did: String): Boolean =
        transaction {
            recordAttempt(did)
            true
        }

    override suspend fun hasPublicationAttempt(did: String): Boolean =
        transaction { IdentityPublicationAttemptsTable.selectAll().where { IdentityPublicationAttemptsTable.did eq did }.any() }

    private fun guardStoredOperation(
        operationId: Uuid,
        identityEpoch: Long,
    ): ResultRow? {
        // Resolve ownership without locking history before its account.
        val operation =
            HostedPlcOperationsTable
                .selectAll()
                .where { HostedPlcOperationsTable.id eq operationId.toJavaUUID() }
                .singleOrNull() ?: return null
        if (operation[HostedPlcOperationsTable.identityEpoch] != identityEpoch) {
            throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
        }
        requireHostedPlcWrite(
            operation[HostedPlcOperationsTable.accountId].toKotlinUuid(),
            operation[HostedPlcOperationsTable.did],
            identityEpoch,
        )
        return operation
    }

    private fun recordOperationAttempt(operationId: Uuid) {
        val row = HostedPlcOperationsTable.selectAll().where { HostedPlcOperationsTable.id eq operationId.toJavaUUID() }.single()
        recordAttempt(row[HostedPlcOperationsTable.did])
    }

    private fun recordAttempt(did: String) {
        IdentityPublicationAttemptsTable.insertIgnore {
            it[IdentityPublicationAttemptsTable.did] = did
            it[attemptedAt] = Clock.System.now()
        }
    }

    private fun ResultRow.toStoredHostedPlcOperation(): StoredHostedPlcOperation =
        StoredHostedPlcOperation(
            id = this[HostedPlcOperationsTable.id].toKotlinUuid(),
            accountId = this[HostedPlcOperationsTable.accountId].toKotlinUuid(),
            did = this[HostedPlcOperationsTable.did],
            cid = this[HostedPlcOperationsTable.cid],
            prevCid = this[HostedPlcOperationsTable.prevCid],
            operationType = this[HostedPlcOperationsTable.operationType],
            operationJson = this[HostedPlcOperationsTable.operationJson],
            createdAt = this[HostedPlcOperationsTable.createdAt],
            publicationAttempted = this[HostedPlcOperationsTable.publicationAttempted],
            identityEpoch = this[HostedPlcOperationsTable.identityEpoch],
        )

    private companion object {
        /** PostgreSQL's SQLSTATE for a unique index violation; H2 reports the same code. */
        private const val UNIQUE_VIOLATION = "23505"
    }
}
