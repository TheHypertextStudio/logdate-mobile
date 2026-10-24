package app.logdate.client.sync.peer.delivery

import app.logdate.client.database.dao.sync.PeerDeliveryDao
import app.logdate.client.database.entities.sync.PeerDeliveryEntity
import app.logdate.client.sync.datalayer.v2.ReceiptState
import app.logdate.client.sync.metadata.EntityType
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Which way a record travels between the watch and the phone. */
enum class DeliveryDirection {
    TO_PHONE,
    FROM_WATCH,
}

/** The peer a ledger row is about: the watch has one phone; the phone has one ledger for all its watches. */
object PeerScopes {
    const val PHONE = "phone"
    const val WATCHES = "watches"
}

/** Where one version of a record stands on its way to being backed up. */
enum class DeliveryState {
    NEW,
    SENT,
    ON_PHONE,
    BACKED_UP,

    /** Released to the watch's own cloud engine. */
    CLOUD_PENDING,
    DELETED_ON_PHONE,

    /** The phone cannot read it; the watch still has it and may upload it itself. */
    REJECTED,
    ;

    companion object {
        fun of(receipt: ReceiptState): DeliveryState =
            when (receipt) {
                ReceiptState.ON_PHONE -> ON_PHONE
                ReceiptState.BACKED_UP -> BACKED_UP
                ReceiptState.DELETED_ON_PHONE -> DELETED_ON_PHONE
                ReceiptState.REJECTED_UNSUPPORTED -> REJECTED
            }
    }
}

/** Why a record is waiting. Each one names what is missing, so status can say what to fix. */
enum class HoldReason {
    PHONE_UNREACHABLE,
    AWAITING_RECEIPT,
    PHONE_BACKING_UP,
    NOT_LINKED,
    SERVER_TOO_OLD,
    NO_INTERNET,
    BLUETOOTH_ONLY,
    AUDIO_WAITS_FOR_WIFI,
    PHONE_CANNOT_READ,
    EDIT_NEEDS_PHONE,
    SEND_FAILED,
}

data class PeerDelivery(
    val scope: String,
    val direction: DeliveryDirection,
    val entityType: EntityType,
    val entityId: String,
    val contentVersion: Long,
    val state: DeliveryState,
    val attempts: Int,
    val lastAttemptAt: Long?,
    val nextAttemptAt: Long?,
    val stateChangedAt: Long,
    val holdReason: HoldReason?,
    val lastCloudAttemptVersion: Long? = null,
)

/**
 * The durable record of where each record stands between a watch and its phone, so a decision survives
 * the process being stopped. A row only ever moves forward, and a newer version of a record starts over.
 */
class PeerDeliveryLedger(
    private val dao: PeerDeliveryDao,
    private val clock: Clock = Clock.System,
) {
    private val mutationMutex = Mutex()

    suspend fun track(
        scope: String,
        direction: DeliveryDirection,
        entityType: EntityType,
        entityId: String,
        contentVersion: Long,
    ): PeerDelivery =
        mutationMutex.withLock {
            val existing = get(scope, direction, entityType, entityId)
            if (existing != null && existing.contentVersion >= contentVersion) return@withLock existing
            val fresh =
                PeerDelivery(
                    scope = scope,
                    direction = direction,
                    entityType = entityType,
                    entityId = entityId,
                    contentVersion = contentVersion,
                    state = DeliveryState.NEW,
                    attempts = 0,
                    lastAttemptAt = null,
                    nextAttemptAt = null,
                    stateChangedAt = now(),
                    holdReason = null,
                )
            dao.upsert(fresh.toEntity())
            fresh
        }

    suspend fun get(
        scope: String,
        direction: DeliveryDirection,
        entityType: EntityType,
        entityId: String,
    ): PeerDelivery? = dao.get(scope, direction.name, entityType.name, entityId)?.toDelivery()

    suspend fun moveTo(
        entry: PeerDelivery,
        state: DeliveryState,
        holdReason: HoldReason? = null,
        nextAttemptAt: Long? = null,
    ): PeerDelivery =
        mutationMutex.withLock {
            val current = get(entry.scope, entry.direction, entry.entityType, entry.entityId) ?: return@withLock entry
            if (current.contentVersion != entry.contentVersion || current.state != entry.state || !current.state.canMoveTo(state)) {
                return@withLock current
            }
            writeMove(current, state, holdReason, nextAttemptAt)
        }

    private suspend fun writeMove(
        entry: PeerDelivery,
        state: DeliveryState,
        holdReason: HoldReason? = null,
        nextAttemptAt: Long? = null,
    ): PeerDelivery {
        val changedAt = if (state == entry.state) entry.stateChangedAt else now()
        val changed = entry.copy(state = state, holdReason = holdReason, nextAttemptAt = nextAttemptAt, stateChangedAt = changedAt)
        dao.upsert(changed.toEntity())
        return changed
    }

    suspend fun recordAttempt(
        entry: PeerDelivery,
        nextAttemptAt: Long?,
    ): PeerDelivery =
        mutationMutex.withLock {
            val current = get(entry.scope, entry.direction, entry.entityType, entry.entityId) ?: return@withLock entry
            if (current.contentVersion != entry.contentVersion || current.state != entry.state || current.state.isTerminal()) {
                return@withLock current
            }
            val attempted = current.copy(attempts = current.attempts + 1, lastAttemptAt = now(), nextAttemptAt = nextAttemptAt)
            dao.upsert(attempted.toEntity())
            attempted
        }

    /** Applies a receipt for [receiptVersion] or later; returns null when it is older or would move the row back. */
    suspend fun applyReceipt(
        scope: String,
        direction: DeliveryDirection,
        entityType: EntityType,
        entityId: String,
        receiptVersion: Long,
        state: DeliveryState,
    ): PeerDelivery? =
        mutationMutex.withLock {
            val entry = get(scope, direction, entityType, entityId) ?: return@withLock null
            if (receiptVersion < entry.contentVersion || entry.state == state || !entry.state.canBecome(state)) return@withLock null
            writeMove(entry, state)
        }

    suspend fun forget(
        scope: String,
        direction: DeliveryDirection,
        entityType: EntityType,
        entityId: String,
    ) = mutationMutex.withLock { dao.delete(scope, direction.name, entityType.name, entityId) }

    /** Removes the rows of [entityType] whose record is no longer in [keep]; returns how many went. */
    suspend fun forgetAllExcept(
        scope: String,
        direction: DeliveryDirection,
        entityType: EntityType,
        keep: Set<String>,
    ): Int =
        mutationMutex.withLock {
            val stale = dao.all(scope, direction.name).filter { it.entityType == entityType.name && it.entityId !in keep }
            stale.forEach { dao.delete(it.peerScope, it.direction, it.entityType, it.entityId) }
            stale.size
        }

    suspend fun inState(
        scope: String,
        direction: DeliveryDirection,
        state: DeliveryState,
    ): List<PeerDelivery> = dao.all(scope, direction.name).mapNotNull { it.toDelivery() }.filter { it.state == state }

    fun observe(
        scope: String,
        direction: DeliveryDirection,
    ): Flow<List<PeerDelivery>> = dao.observeAll(scope, direction.name).map { rows -> rows.mapNotNull { it.toDelivery() } }

    suspend fun earliestDue(
        scope: String,
        direction: DeliveryDirection,
    ): Long? = dao.earliestNextAttempt(scope, direction.name)

    private fun DeliveryState.canMoveTo(next: DeliveryState): Boolean =
        when (this) {
            DeliveryState.BACKED_UP, DeliveryState.DELETED_ON_PHONE -> false
            DeliveryState.CLOUD_PENDING -> next == this || next == DeliveryState.BACKED_UP || next == DeliveryState.DELETED_ON_PHONE
            DeliveryState.ON_PHONE -> next == this || next == DeliveryState.CLOUD_PENDING || canBecome(next)
            DeliveryState.NEW -> true
            DeliveryState.SENT, DeliveryState.REJECTED -> next == this || (next != DeliveryState.NEW && next != DeliveryState.SENT)
        }

    private fun DeliveryState.isTerminal(): Boolean = this == DeliveryState.BACKED_UP || this == DeliveryState.DELETED_ON_PHONE

    private fun now(): Long = clock.now().toEpochMilliseconds()

    private fun DeliveryState.canBecome(next: DeliveryState): Boolean =
        when (this) {
            DeliveryState.BACKED_UP, DeliveryState.DELETED_ON_PHONE -> false
            DeliveryState.CLOUD_PENDING, DeliveryState.ON_PHONE -> next == DeliveryState.BACKED_UP || next == DeliveryState.DELETED_ON_PHONE
            DeliveryState.NEW, DeliveryState.SENT, DeliveryState.REJECTED -> next != DeliveryState.NEW && next != DeliveryState.SENT
        }

    private fun PeerDelivery.toEntity() =
        PeerDeliveryEntity(
            peerScope = scope,
            direction = direction.name,
            entityType = entityType.name,
            entityId = entityId,
            contentVersion = contentVersion,
            state = state.name,
            attempts = attempts,
            lastAttemptAt = lastAttemptAt,
            lastCloudAttemptVersion = lastCloudAttemptVersion,
            nextAttemptAt = nextAttemptAt,
            stateChangedAt = stateChangedAt,
            holdReason = holdReason?.name,
        )

    private fun PeerDeliveryEntity.toDelivery(): PeerDelivery? {
        val rowDirection = DeliveryDirection.entries.firstOrNull { it.name == direction }
        val rowType = EntityType.entries.firstOrNull { it.name == entityType }
        val rowState = DeliveryState.entries.firstOrNull { it.name == state }
        val rowHold = holdReason?.let { name -> HoldReason.entries.firstOrNull { it.name == name } }
        if (rowDirection == null || rowType == null || rowState == null || (holdReason != null && rowHold == null)) {
            Napier.w("Skipping a delivery row a newer version wrote ($direction $entityType $state $holdReason)")
            return null
        }
        return PeerDelivery(
            scope = peerScope,
            direction = rowDirection,
            entityType = rowType,
            entityId = entityId,
            contentVersion = contentVersion,
            state = rowState,
            attempts = attempts,
            lastAttemptAt = lastAttemptAt,
            lastCloudAttemptVersion = lastCloudAttemptVersion,
            nextAttemptAt = nextAttemptAt,
            stateChangedAt = stateChangedAt,
            holdReason = rowHold,
        )
    }
}
