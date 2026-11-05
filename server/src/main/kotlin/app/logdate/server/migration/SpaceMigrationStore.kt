package app.logdate.server.migration

import app.logdate.server.auth.AccountRepository
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

/**
 * One account's move from legacy storage into its personal space.
 *
 * @property version increases with every change, so a change made from a stale copy is refused.
 * @property identityEpoch binds this move and its eventual cleanup to the original account authority.
 * @property holdStartedAt when the moving device asked the server to hold writes to the store it is
 *   leaving: legacy writes before the switch, space writes before switching back. Null when nothing
 *   is held.
 * @property switchedAtSeq the personal space's last sequence number when the account switched.
 */
data class SpaceMigration(
    val accountId: UUID,
    val migrationId: String,
    val phase: SpaceMigrationPhase,
    val startedAt: Instant,
    val updatedAt: Instant,
    val version: Long = 1,
    val holdStartedAt: Instant? = null,
    val switchedAt: Instant? = null,
    val switchedAtSeq: Long? = null,
    val revertedAt: Instant? = null,
    val cleanedAt: Instant? = null,
    val identityEpoch: Long = 0,
) {
    /** This move with [changes] applied and its version advanced, as the next value to store. */
    fun next(
        at: Instant,
        changes: SpaceMigration.() -> SpaceMigration,
    ): SpaceMigration = changes().copy(updatedAt = at, version = version + 1)
}

/**
 * The clock a move's timestamps come from. PostgreSQL keeps timestamps to the microsecond and rounds
 * anything finer, while the JVM clock on Linux reads nanoseconds, so a move stamped with the raw clock
 * would read back from the database different from what was written. Truncating here makes every
 * store hold exactly the move it was given.
 */
internal class StoredPrecisionClock(
    private val clock: Clock = Clock.System,
) : Clock {
    override fun now(): Instant = clock.now().atStoredPrecision()
}

/** This instant truncated to the microsecond, the finest precision PostgreSQL stores. */
internal fun Instant.atStoredPrecision(): Instant = Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond / 1_000 * 1_000)

/**
 * Where each account's move into its personal space stands. Every change is a compare-and-set on
 * [SpaceMigration.version], so two devices, or a device and the cleanup job, can't both act on the
 * same state.
 */
interface SpaceMigrationStore {
    suspend fun get(accountId: UUID): SpaceMigration?

    /**
     * Stores [updated] if the account's move is still [expected], where null means there is none.
     * Returns whether it did.
     */
    suspend fun replace(
        expected: SpaceMigration?,
        updated: SpaceMigration,
    ): Boolean

    /**
     * Stores [updated] and moves the account's storage mode from [from] to [to] in one step, if the
     * move is still [expected] and the mode is still [from]. Returns whether it did; when it didn't,
     * neither changed.
     */
    suspend fun replaceAndSwitch(
        expected: SpaceMigration,
        updated: SpaceMigration,
        from: AccountStorageMode,
        to: AccountStorageMode,
    ): Boolean

    /**
     * Moves whose legacy copy is due for deletion, oldest first, at most [limit]: those in
     * [SpaceMigrationPhase.SWITCHED] that switched before [cutoff], and those already marked
     * [SpaceMigrationPhase.CLEANED] whose copy isn't deleted yet.
     */
    suspend fun cleanupDue(
        cutoff: Instant,
        limit: Int,
    ): List<SpaceMigration>
}

/** A [SpaceMigrationStore] for servers running without a database; storage modes change in [accounts]. */
@OptIn(ExperimentalUuidApi::class)
class InMemorySpaceMigrationStore(
    private val accounts: AccountRepository,
) : SpaceMigrationStore {
    private val mutex = Mutex()
    private val rows = mutableMapOf<UUID, SpaceMigration>()

    override suspend fun get(accountId: UUID): SpaceMigration? = mutex.withLock { rows[accountId] }

    override suspend fun replace(
        expected: SpaceMigration?,
        updated: SpaceMigration,
    ): Boolean =
        mutex.withLock {
            if (!matches(expected, updated) || !currentIdentity(updated)) return@withLock false
            rows[updated.accountId] = updated
            true
        }

    override suspend fun replaceAndSwitch(
        expected: SpaceMigration,
        updated: SpaceMigration,
        from: AccountStorageMode,
        to: AccountStorageMode,
    ): Boolean =
        mutex.withLock {
            if (!matches(expected, updated) || !currentIdentity(updated)) return@withLock false
            if (!accounts.updateStorageModeIfUnchanged(updated.accountId.toKotlinUuid(), from, to)) return@withLock false
            rows[updated.accountId] = updated
            true
        }

    override suspend fun cleanupDue(
        cutoff: Instant,
        limit: Int,
    ): List<SpaceMigration> =
        mutex.withLock {
            rows.values
                .filter {
                    it.isCleanupDue(cutoff) &&
                        currentIdentity(it) &&
                        accounts.findById(it.accountId.toKotlinUuid())?.storageMode == AccountStorageMode.PERSONAL_SPACE_V1
                }.sortedBy { it.switchedAt }
                .take(limit)
        }

    private fun matches(
        expected: SpaceMigration?,
        updated: SpaceMigration,
    ): Boolean {
        val stored = rows[updated.accountId]
        return updated.version > 0 &&
            updated.version == (expected?.version ?: 0) + 1 &&
            stored?.version == expected?.version &&
            stored?.migrationId == expected?.migrationId &&
            stored?.identityEpoch == expected?.identityEpoch &&
            (expected == null || (expected.accountId == updated.accountId && expected.identityEpoch == updated.identityEpoch))
    }

    private suspend fun currentIdentity(migration: SpaceMigration): Boolean {
        val account = accounts.findById(migration.accountId.toKotlinUuid()) ?: return false
        return account.identityEpoch == migration.identityEpoch && account.isActive && account.moderation == null
    }
}

private fun SpaceMigration.isCleanupDue(cutoff: Instant): Boolean =
    when (phase) {
        SpaceMigrationPhase.SWITCHED -> switchedAt != null && switchedAt < cutoff
        SpaceMigrationPhase.CLEANED -> cleanedAt == null
        else -> false
    }
