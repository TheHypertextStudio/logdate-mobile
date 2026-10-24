package app.logdate.server.migration

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.logging.SpaceMigrationNotices
import app.logdate.server.space.SpaceStore
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.LegacyInventory
import app.logdate.shared.model.sync.LegacyRecordKinds
import app.logdate.shared.model.sync.SpaceMigrationErrorCodes
import app.logdate.shared.model.sync.SpaceMigrationPhase
import app.logdate.shared.model.sync.SpaceMigrationStatus
import app.logdate.shared.model.sync.SpaceMigrationSwitchBackRequest
import app.logdate.shared.model.sync.SpaceMigrationSwitchRequest
import app.logdate.space.SpaceRef
import io.github.aakira.napier.Napier
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

private const val MAX_MIGRATION_ID_LENGTH = 64
private val MIGRATION_ID = Regex("^[A-Za-z0-9-]{1,$MAX_MIGRATION_ID_LENGTH}$")

/**
 * Moves an account's journal from legacy storage into its personal space, one step per call from the
 * device doing the work. The server can't read the journal, so it never converts anything itself; it
 * only makes sure no write is lost on either side of the switch:
 *
 * 1. **Begin.** A device claims the move. Only one device moves an account at a time.
 * 2. **Hold.** Before checking its copy, the device asks the server to hold legacy writes. Older apps
 *    get a retryable `503` and keep their changes queued. Once every request that started before the
 *    hold has finished ([SpaceMigrationConfig.holdGrace]), legacy storage can't change under the check.
 * 3. **Switch.** The device proves its check: the legacy watermark and counts it compared, and a
 *    digest of exactly what the personal space holds. The server recomputes both and moves the
 *    storage mode with a compare-and-set, or refuses and leaves the account on legacy storage.
 *
 * Switching back mirrors this: hold space writes, let the device download everything, then move
 * the mode back while the legacy copy is still kept.
 */
@OptIn(ExperimentalUuidApi::class)
internal class SpaceMigrationService(
    private val store: SpaceMigrationStore,
    private val accounts: AccountRepository,
    private val spaces: SpaceStore,
    private val inventory: LegacyInventoryReader,
    private val config: SpaceMigrationConfig,
    private val clock: Clock = StoredPrecisionClock(),
) {
    suspend fun status(accountId: UUID): SpaceMigrationStatus = store.get(accountId).toStatus()

    suspend fun begin(
        accountId: UUID,
        migrationId: String,
    ): SpaceMigrationResult<SpaceMigrationStatus> {
        if (!MIGRATION_ID.matches(migrationId)) return refused(SpaceMigrationErrorCodes.NOT_ACTIVE, "The move needs a short id")
        val account = accounts.findById(accountId.toKotlinUuid()) ?: return noAccount()
        account.did ?: return refused(SpaceMigrationErrorCodes.NO_IDENTITY, "This account has no DID, so it has no personal space")
        if (account.storageMode != AccountStorageMode.LEGACY) return wrongMode(account)
        val now = clock.now()
        val current = store.get(accountId)
        val updated =
            when {
                current == null || current.phase == SpaceMigrationPhase.REVERTED -> {
                    SpaceMigration(accountId, migrationId, SpaceMigrationPhase.UPLOADING, now, now, version = (current?.version ?: 0) + 1)
                }

                current.phase != SpaceMigrationPhase.UPLOADING -> {
                    return wrongMode(account)
                }

                current.migrationId == migrationId -> {
                    current.next(now) { this }
                }

                !current.leaseLapsed(
                    now,
                ) -> {
                    return refused(SpaceMigrationErrorCodes.HELD_ELSEWHERE, "Another device is moving this journal")
                }

                else -> {
                    current.next(now) { copy(migrationId = migrationId, startedAt = now, holdStartedAt = null) }
                }
            }
        if (!store.replace(current, updated)) return raced()
        return SpaceMigrationResult.Done(updated.toStatus())
    }

    /** Holds writes to the store the account is leaving: legacy storage before the switch, the space before switching back. */
    suspend fun hold(
        accountId: UUID,
        migrationId: String,
    ): SpaceMigrationResult<SpaceMigrationStatus> {
        val current = active(accountId, migrationId, SpaceMigrationPhase.UPLOADING, SpaceMigrationPhase.SWITCHED) ?: return notActive()
        val now = clock.now()
        val updated = current.next(now) { copy(holdStartedAt = holdStartedAt?.takeIf { holds(now) } ?: now) }
        if (!store.replace(current, updated)) return raced()
        return SpaceMigrationResult.Done(updated.toStatus())
    }

    suspend fun release(
        accountId: UUID,
        migrationId: String,
    ): SpaceMigrationResult<SpaceMigrationStatus> {
        val current = active(accountId, migrationId, SpaceMigrationPhase.UPLOADING, SpaceMigrationPhase.SWITCHED) ?: return notActive()
        val updated = current.next(clock.now()) { copy(holdStartedAt = null) }
        if (!store.replace(current, updated)) return raced()
        return SpaceMigrationResult.Done(updated.toStatus())
    }

    suspend fun legacyInventory(
        accountId: UUID,
        idsOf: String?,
    ): LegacyInventory = inventory.read(accountId, idsOf?.takeIf { it in LegacyRecordKinds.ALL })

    suspend fun switch(
        accountId: UUID,
        request: SpaceMigrationSwitchRequest,
    ): SpaceMigrationResult<SpaceMigrationStatus> {
        val current = active(accountId, request.migrationId, SpaceMigrationPhase.UPLOADING) ?: return notActive()
        val account = accounts.findById(accountId.toKotlinUuid()) ?: return noAccount()
        if (account.storageMode != AccountStorageMode.LEGACY) return wrongMode(account)
        val did = account.did ?: return refused(SpaceMigrationErrorCodes.NO_IDENTITY, "This account has no DID")
        val now = clock.now()
        holdNotReady(current, now)?.let { return it }
        val legacy = inventory.read(accountId)
        val counts = legacy.kinds.associate { it.kind to it.count }
        if (legacy.watermark != request.legacyWatermark || counts.any { (kind, count) -> (request.legacyCounts[kind] ?: 0) != count }) {
            return refused(SpaceMigrationErrorCodes.LEGACY_CHANGED, "Legacy storage changed after this device checked it")
        }
        val space = SpaceRef.personal(did)
        val index = spaces.index(space.uri, did)
        if (index.lastSeq != request.spaceSeq || index.live.size != request.spaceRecordCount || index.digest != request.spaceDigest) {
            return refused(SpaceMigrationErrorCodes.SPACE_CHANGED, "The personal space doesn't hold what this device checked")
        }
        val updated =
            current.next(now) {
                copy(phase = SpaceMigrationPhase.SWITCHED, holdStartedAt = null, switchedAt = now, switchedAtSeq = index.lastSeq)
            }
        if (!store.replaceAndSwitch(current, updated, AccountStorageMode.LEGACY, AccountStorageMode.PERSONAL_SPACE_V1)) return raced()
        Napier.i(SpaceMigrationNotices.SWITCHED)
        return SpaceMigrationResult.Done(updated.toStatus())
    }

    suspend fun switchBack(
        accountId: UUID,
        request: SpaceMigrationSwitchBackRequest,
    ): SpaceMigrationResult<SpaceMigrationStatus> {
        val current = active(accountId, request.migrationId, SpaceMigrationPhase.SWITCHED) ?: return notActive()
        val now = clock.now()
        if (now >= current.legacyKeptUntil()) {
            return refused(SpaceMigrationErrorCodes.WINDOW_CLOSED, "The previous copy of this journal is no longer kept")
        }
        val account = accounts.findById(accountId.toKotlinUuid()) ?: return noAccount()
        if (account.storageMode != AccountStorageMode.PERSONAL_SPACE_V1) return wrongMode(account)
        val did = account.did ?: return refused(SpaceMigrationErrorCodes.NO_IDENTITY, "This account has no DID")
        holdNotReady(current, now)?.let { return it }
        val index = spaces.index(SpaceRef.personal(did).uri, did)
        if (index.lastSeq != request.spaceSeq) {
            return refused(SpaceMigrationErrorCodes.SPACE_CHANGED, "The personal space changed after this device downloaded it")
        }
        val updated = current.next(now) { copy(phase = SpaceMigrationPhase.REVERTED, holdStartedAt = null, revertedAt = now) }
        if (!store.replaceAndSwitch(current, updated, AccountStorageMode.PERSONAL_SPACE_V1, AccountStorageMode.LEGACY)) return raced()
        Napier.i(SpaceMigrationNotices.SWITCHED_BACK)
        return SpaceMigrationResult.Done(updated.toStatus())
    }

    /** The account's move named [migrationId] when it is in one of [phases]. */
    internal suspend fun active(
        accountId: UUID,
        migrationId: String,
        vararg phases: SpaceMigrationPhase,
    ): SpaceMigration? = store.get(accountId)?.takeIf { it.migrationId == migrationId && it.phase in phases }

    private fun holdNotReady(
        migration: SpaceMigration,
        now: Instant,
    ): SpaceMigrationResult.Refused? {
        val started = migration.holdStartedAt?.takeIf { migration.holds(now) }
        return when {
            started == null -> {
                refused(SpaceMigrationErrorCodes.HOLD_NOT_READY, "Writes aren't held; hold them and check again")
            }

            now < started + config.holdGrace -> {
                refused(SpaceMigrationErrorCodes.HOLD_NOT_READY, "Writes are held from ${started + config.holdGrace}")
            }

            else -> {
                null
            }
        }
    }

    /** Whether [this] move still holds writes at [now]. */
    internal fun SpaceMigration.holds(now: Instant): Boolean = holdStartedAt != null && now < holdStartedAt + config.holdLifetime

    private fun SpaceMigration.leaseLapsed(now: Instant): Boolean = !holds(now) && now >= updatedAt + config.uploadLease

    internal fun SpaceMigration.legacyKeptUntil(): Instant = (switchedAt ?: startedAt) + config.legacyRetention

    private fun SpaceMigration?.toStatus(): SpaceMigrationStatus {
        if (this == null) return SpaceMigrationStatus(SpaceMigrationPhase.NONE)
        val now = clock.now()
        val hold = holdStartedAt?.takeIf { holds(now) }
        return SpaceMigrationStatus(
            phase = phase,
            migrationId = migrationId,
            startedAt = startedAt.toEpochMilliseconds(),
            holdReadyAt = hold?.let { (it + config.holdGrace).toEpochMilliseconds() },
            holdExpiresAt = hold?.let { (it + config.holdLifetime).toEpochMilliseconds() },
            switchedAt = switchedAt?.toEpochMilliseconds(),
            switchedAtSeq = switchedAtSeq,
            legacyKeptUntil = switchedAt?.let { legacyKeptUntil().toEpochMilliseconds() },
            revertedAt = revertedAt?.toEpochMilliseconds(),
            cleanedAt = cleanedAt?.toEpochMilliseconds(),
        )
    }

    private fun wrongMode(account: Account) =
        refused(SpaceMigrationErrorCodes.WRONG_STORAGE_MODE, "This account's storage is ${account.storageMode.wireValue}")

    private fun notActive() = refused(SpaceMigrationErrorCodes.NOT_ACTIVE, "No move with this id is in the step this call needs")

    private fun raced() = refused(SpaceMigrationErrorCodes.NOT_ACTIVE, "The move changed while this call ran; read its status again")

    private fun noAccount() = refused(SpaceMigrationErrorCodes.NOT_ACTIVE, "The account doesn't exist")
}
