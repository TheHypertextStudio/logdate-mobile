package app.logdate.server.migration

import app.logdate.server.auth.AccountRepository
import app.logdate.server.logging.SpaceMigrationNotices
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

private const val CLEANUP_BATCH = 50

/**
 * Deletes everything legacy sync kept for one account: its sync index, its location history, its
 * media files and their records, its journal merge history, and its public repository, which is then
 * empty and starts a new history with its next write. That is safe only because no account's DID has
 * been published, so no relay or app holds a copy of the old history.
 *
 * The exact unfinished CLEANED claim and its identity epoch authorize deletion. A false result
 * means authority changed; the caller must not mark cleanup complete. Provider failures propagate
 * so the same durable claim can retry idempotently.
 */
fun interface LegacyJournalEraser {
    suspend fun erase(claim: SpaceMigration): Boolean
}

/** What one cleanup run did. */
data class LegacyCleanupResult(
    val erased: Int,
    val failed: Int,
)

/**
 * Deletes the legacy copy of every journal that moved into its personal space more than
 * [SpaceMigrationConfig.legacyRetention] ago.
 *
 * Each account is first marked [SpaceMigrationPhase.CLEANED] with a compare-and-set, which ends the
 * window for switching back before anything is deleted, so a switch back can never land on a
 * half-deleted copy. The time the copy was deleted is recorded only once [LegacyJournalEraser.erase]
 * succeeds; until then the next run tries again.
 */
@OptIn(ExperimentalUuidApi::class)
class LegacyCopyCleanup(
    private val store: SpaceMigrationStore,
    private val accounts: AccountRepository,
    private val eraser: LegacyJournalEraser,
    private val config: SpaceMigrationConfig,
    private val clock: Clock = StoredPrecisionClock(),
) {
    suspend fun runOnce(now: Instant = clock.now()): LegacyCleanupResult {
        var erased = 0
        var failed = 0
        for (due in store.cleanupDue(now - config.legacyRetention, CLEANUP_BATCH)) {
            val claimed = claim(due, now) ?: continue
            try {
                if (eraser.erase(claimed) && store.replace(claimed, claimed.next(clock.now()) { copy(cleanedAt = clock.now()) })) erased++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                Napier.e(SpaceMigrationNotices.CLEANUP_FAILED, error)
            }
        }
        return LegacyCleanupResult(erased, failed)
    }

    /** The move marked as cleaning, or null when it changed, was switched back, or isn't in its personal space. */
    private suspend fun claim(
        due: SpaceMigration,
        now: Instant,
    ): SpaceMigration? {
        val account = accounts.findById(due.accountId.toKotlinUuid())
        if (account == null ||
            account.identityEpoch != due.identityEpoch ||
            !account.isActive ||
            account.moderation != null ||
            account.storageMode != AccountStorageMode.PERSONAL_SPACE_V1
        ) {
            Napier.w(SpaceMigrationNotices.CLEANUP_SKIPPED)
            return null
        }
        if (due.phase == SpaceMigrationPhase.CLEANED) return due
        val marked = due.next(now) { copy(phase = SpaceMigrationPhase.CLEANED, holdStartedAt = null) }
        return marked.takeIf { store.replace(due, it) }
    }
}
