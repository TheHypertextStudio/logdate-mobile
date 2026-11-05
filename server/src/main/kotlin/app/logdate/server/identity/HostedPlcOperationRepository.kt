package app.logdate.server.identity

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class StoredHostedPlcOperation(
    val id: Uuid,
    val accountId: Uuid,
    val did: String,
    val cid: String? = null,
    val prevCid: String? = null,
    val operationType: String,
    val operationJson: String,
    val createdAt: Instant = Clock.System.now(),
    val publicationAttempted: Boolean? = null,
    val identityEpoch: Long = 0,
)

/**
 * Another operation for [did] already names [prevCid] as its previous operation. Appending this one
 * as well would fork the identity's history, so nothing was stored.
 */
class HostedPlcOperationConflictException(
    val did: String,
    val prevCid: String?,
    cause: Throwable? = null,
) : IllegalStateException("An operation for $did already follows $prevCid", cause)

@OptIn(ExperimentalUuidApi::class)
interface HostedPlcOperationRepository {
    /**
     * Stores [operation] with its originating account epoch.
     *
     * @throws HostedPlcOperationConflictException when another operation for the same DID already
     *   names the same previous operation, so the log stays one chain.
     */
    suspend fun save(operation: StoredHostedPlcOperation): StoredHostedPlcOperation

    /** Selects one durable initial genesis per account before any provider call. */
    suspend fun claimGenesis(candidate: StoredHostedPlcOperation): StoredHostedPlcOperation =
        throw IdentityLifecycleConflictException("Hosted identity provisioning storage is unavailable")

    suspend fun listByAccountId(accountId: Uuid): List<StoredHostedPlcOperation>

    suspend fun listByDid(did: String): List<StoredHostedPlcOperation>

    /** Marks ambiguity before a directory request; unsupported stores fail closed. */
    suspend fun markPublicationAttempted(
        operationId: Uuid,
        identityEpoch: Long = 0,
    ): Boolean = false

    /** Lifecycle barrier only: does not establish the person's publication consent. */
    suspend fun beginPublicationAttempt(
        accountId: Uuid,
        did: String,
        identityEpoch: Long = 0,
    ): Boolean = false

    suspend fun recordPublicationAttempt(did: String): Boolean = false

    /** Null means this store cannot establish whether an attempt was recorded. */
    suspend fun hasPublicationAttempt(did: String): Boolean? = null

    /**
     * Records the CID the PLC directory assigned to a stored operation once the directory has it.
     * Returns false when no operation has [operationId].
     */
    suspend fun recordPublishedCid(
        operationId: Uuid,
        cid: String,
        identityEpoch: Long = 0,
    ): Boolean
}

@OptIn(ExperimentalUuidApi::class)
class InMemoryHostedPlcOperationRepository : HostedPlcOperationRepository {
    private val operationsById = linkedMapOf<Uuid, StoredHostedPlcOperation>()
    private val attemptedDids = mutableSetOf<String>()
    private val attemptedOwners = mutableMapOf<String, Uuid>()

    override suspend fun save(operation: StoredHostedPlcOperation): StoredHostedPlcOperation =
        synchronized(operationsById) {
            val forks =
                operation.prevCid != null &&
                    operationsById.values.any { it.id != operation.id && it.did == operation.did && it.prevCid == operation.prevCid }
            if (forks) throw HostedPlcOperationConflictException(operation.did, operation.prevCid)
            require(operationsById[operation.id]?.publicationAttempted != true || operation.publicationAttempted == true) {
                "Publication attempt history cannot be cleared"
            }
            operationsById[operation.id] = operation
            if (operation.publicationAttempted == true) attemptedDids += operation.did
            operation
        }

    override suspend fun claimGenesis(candidate: StoredHostedPlcOperation): StoredHostedPlcOperation =
        synchronized(operationsById) {
            require(candidate.prevCid == null && candidate.publicationAttempted == false)
            canonicalHostedGenesis(operationsById.values.filter { it.accountId == candidate.accountId }, candidate.identityEpoch)
                ?.let { return@synchronized it }
            if (operationsById.values.any { it.did == candidate.did && it.accountId != candidate.accountId }) {
                throw IdentityLifecycleConflictException("Hosted identity is unavailable")
            }
            operationsById[candidate.id] = candidate
            candidate
        }

    override suspend fun listByAccountId(accountId: Uuid): List<StoredHostedPlcOperation> =
        synchronized(operationsById) {
            operationsById.values
                .filter { it.accountId == accountId }
                .sortedBy(StoredHostedPlcOperation::createdAt)
        }

    override suspend fun listByDid(did: String): List<StoredHostedPlcOperation> =
        synchronized(operationsById) {
            operationsById.values
                .filter { it.did == did }
                .sortedBy(StoredHostedPlcOperation::createdAt)
        }

    override suspend fun recordPublishedCid(
        operationId: Uuid,
        cid: String,
        identityEpoch: Long,
    ): Boolean =
        synchronized(operationsById) {
            val existing = operationsById[operationId]?.takeIf { it.identityEpoch == identityEpoch } ?: return@synchronized false
            operationsById[operationId] = existing.copy(cid = cid, publicationAttempted = true)
            attemptedDids += existing.did
            true
        }

    override suspend fun markPublicationAttempted(
        operationId: Uuid,
        identityEpoch: Long,
    ): Boolean =
        synchronized(operationsById) {
            val existing = operationsById[operationId]?.takeIf { it.identityEpoch == identityEpoch } ?: return@synchronized false
            operationsById[operationId] = existing.copy(publicationAttempted = true)
            attemptedDids += existing.did
            true
        }

    // This adapter has no account database or adoption capability; preserve local claim binding only.
    override suspend fun beginPublicationAttempt(
        accountId: Uuid,
        did: String,
        identityEpoch: Long,
    ): Boolean =
        synchronized(operationsById) {
            if (identityEpoch != 0L ||
                attemptedOwners[did]?.let { it != accountId } == true ||
                operationsById.values.any { it.did == did && (it.accountId != accountId || it.identityEpoch != identityEpoch) }
            ) {
                return@synchronized false
            }
            attemptedOwners[did] = accountId
            attemptedDids += did
            true
        }

    override suspend fun recordPublicationAttempt(did: String): Boolean =
        synchronized(operationsById) {
            attemptedDids += did
            true
        }

    override suspend fun hasPublicationAttempt(did: String): Boolean = synchronized(operationsById) { did in attemptedDids }
}
