package app.logdate.client.sync.space.migration

import app.logdate.client.device.crypto.IdentityKeyNotFoundException
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.space.SpaceAccess
import app.logdate.client.sync.space.SpaceRecordMappingException
import app.logdate.client.sync.space.SpaceRoute
import app.logdate.shared.model.sync.SpaceMigrationErrorCodes
import app.logdate.shared.model.sync.SpaceMigrationPhase
import app.logdate.shared.model.sync.SpaceMigrationStatus
import app.logdate.space.SealingKeys
import app.logdate.space.SpaceRef
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Moves the signed-in account's journal from legacy storage into its sealed personal space, from
 * this device. It runs after every full sync once this device is ready, resumes where an earlier
 * attempt stopped, and never changes anything for legacy sync until the server has switched the
 * account; see [SpaceMigrationRun] for the steps.
 *
 * A move that stops says why in [state] and tries again on its own later, or when the person asks.
 * After the person switches back, the journal stays where it is until they ask again.
 */
internal class SpaceJournalMigration(
    private val env: SpaceMigrationEnvironment,
) : JournalStorageMove {
    private val mutex = Mutex()
    private val current = MutableStateFlow<JournalMoveState>(JournalMoveState.NotOffered)
    override val state: StateFlow<JournalMoveState> = current.asStateFlow()

    /** Moves the journal if this device is ready and nothing asked it to wait. */
    suspend fun runIfDue() = mutex.withLock { attempt(manual = false) }

    override suspend fun retry() = mutex.withLock { attempt(manual = true) }

    /** Finishes handing the journal back to legacy sync if the app stopped part way through switching back. */
    suspend fun finishSwitchBackIfNeeded() =
        mutex.withLock {
            val scope = env.tokens.currentUploadScope() ?: return@withLock
            val saved =
                env.deps.checkpoints
                    .load(scope)
                    ?.takeIf { it.stage == SpaceMigrationStage.SWITCHING_BACK } ?: return@withLock
            guarded(scope, saved) { SpaceMigrationSwitchBack(env, scope) { current.value = it }.finish(saved) }
        }

    override suspend fun refresh() = mutex.withLock { describe() }

    override suspend fun switchBack() =
        mutex.withLock {
            val scope = env.tokens.currentUploadScope() ?: return@withLock
            guarded(scope, start = null) { SpaceMigrationSwitchBack(env, scope) { current.value = it }.run() }
        }

    private suspend fun attempt(manual: Boolean) {
        val scope = env.tokens.currentUploadScope() ?: return publish(JournalMoveState.NotOffered)
        if (!env.deps.enabledInThisBuild()) return publish(JournalMoveState.NotOffered)
        val saved = env.deps.checkpoints.load(scope)
        if (saved?.stage == SpaceMigrationStage.SWITCHING_BACK) {
            return guarded(scope, saved) { SpaceMigrationSwitchBack(env, scope) { current.value = it }.finish(saved) }
        }
        when (env.space.gate.route()) {
            SpaceRoute.Space -> {
                if (!env.deps.serverOffersMove()) return showMoved(scope, saved)
                return guarded(scope, saved) { settleIfNeeded(scope, saved) }
            }
            is SpaceRoute.Blocked -> return publish(JournalMoveState.NotOffered)
            SpaceRoute.Legacy -> Unit
        }
        // A server that stopped offering moves still lets a moved journal switch back, but starts no new move.
        if (!env.deps.serverOffersMove()) return publish(JournalMoveState.NotOffered)
        if (saved?.stage == SpaceMigrationStage.SWITCHED_BACK && !manual) return publish(JournalMoveState.SwitchedBack)
        val retryAt = saved?.retryAt
        val waiting =
            retryAt != null &&
                retryAt >
                env.deps.clock
                    .now()
                    .toEpochMilliseconds()
        if (!manual && saved?.problem != null && waiting) {
            return publish(JournalMoveState.Stopped(saved.problem, Instant.fromEpochMilliseconds(retryAt)))
        }
        if (!env.hasIdentityKey()) return stop(scope, saved ?: fresh(), JournalMoveProblem.IdentityKeyMissing)
        if (!ready(scope)) return publish(JournalMoveState.WaitingForSync)
        val start =
            when {
                saved == null || saved.stage == SpaceMigrationStage.SWITCHED_BACK || saved.stage == SpaceMigrationStage.SWITCHED -> fresh()
                else -> saved.copy(problem = null, retryAt = null)
            }
        guarded(scope, start) {
            env.deps.checkpoints.save(scope, start)
            server(scope, "begin") { env.deps.api.begin(it, start.migrationId) }
            val run = SpaceMigrationRun(env, scope, access(scope), start) { publish(it) }
            val status = run.execute()
            run.settle(status)
            publish(JournalMoveState.Moved(status.legacyKeptUntil?.let(Instant::fromEpochMilliseconds)))
        }
    }

    /**
     * Reports a journal that moved while the server no longer offers moves, so it can still be
     * switched back during its window. A server that never moved this account, or that has no move
     * calls at all, shows nothing rather than a problem.
     */
    private suspend fun showMoved(
        scope: UploadScope,
        saved: SpaceMigrationCheckpoint?,
    ) {
        val status = statusOrNull(scope)
        if (status == null || status.phase !in MOVED) return publish(JournalMoveState.NotOffered)
        guarded(scope, saved) { settleIfNeeded(scope, saved, status) }
    }

    /** Finishes settling on the personal space after a switch the app stopped during, then reports the move. */
    private suspend fun settleIfNeeded(
        scope: UploadScope,
        saved: SpaceMigrationCheckpoint?,
        known: SpaceMigrationStatus? = null,
    ) {
        val status = known ?: server(scope, "status") { env.deps.api.status(it) }
        if (status.phase !in MOVED) return publish(JournalMoveState.NotOffered)
        val seq = status.switchedAtSeq
        if (saved != null && saved.migrationId == status.migrationId && !saved.settled && seq != null) {
            val run = SpaceMigrationRun(env, scope, access(scope), saved) { publish(it) }
            run.settle(status)
        }
        publish(status.moved())
    }

    private suspend fun describe() {
        val scope = env.tokens.currentUploadScope() ?: return publish(JournalMoveState.NotOffered)
        if (!env.deps.enabledInThisBuild()) return publish(JournalMoveState.NotOffered)
        val saved = env.deps.checkpoints.load(scope)
        val status = statusOrNull(scope)
        publish(
            when {
                status != null && status.phase in MOVED -> status.moved()
                !env.deps.serverOffersMove() -> JournalMoveState.NotOffered
                saved?.stage == SpaceMigrationStage.SWITCHED_BACK -> JournalMoveState.SwitchedBack
                saved?.problem != null -> JournalMoveState.Stopped(saved.problem, saved.retryAt?.let(Instant::fromEpochMilliseconds))
                else -> JournalMoveState.WaitingForSync
            },
        )
    }

    /** The account's move as the server reports it, or null when the server can't say. */
    private suspend fun statusOrNull(scope: UploadScope): SpaceMigrationStatus? =
        try {
            server(scope, "status") { env.deps.api.status(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.w("Reading the journal move's status failed: ${error::class.simpleName}")
            null
        }

    /** Whether this device finished syncing everything with legacy storage, so its copy is complete. */
    private suspend fun ready(scope: UploadScope): Boolean =
        env.legacyDownloadFinished(scope) && env.metadata.getPendingCount() == 0 && env.inbox.count() == 0

    private suspend fun access(scope: UploadScope): SpaceAccess {
        val did = env.space.accountDid() ?: throw CloudApiException(SpaceMigrationErrorCodes.NO_IDENTITY, "This account has no DID")
        return SpaceAccess(
            scope,
            SpaceRef.personal(did),
            did,
            SealingKeys.personal(env.space.identityKey(scope.ownerId)),
            env.tokens.currentRequestScope(),
        )
    }

    private fun fresh() = SpaceMigrationCheckpoint(migrationId = Uuid.random().toString())

    /** Runs [block], reporting any failure as the move's problem and saving it with when to try again. */
    private suspend fun guarded(
        scope: UploadScope,
        start: SpaceMigrationCheckpoint?,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Moving the journal stopped: ${error::class.simpleName}", error)
            val checkpoint = env.deps.checkpoints.load(scope) ?: start ?: fresh()
            stop(scope, checkpoint, problemOf(error))
        }
    }

    private suspend fun stop(
        scope: UploadScope,
        checkpoint: SpaceMigrationCheckpoint,
        problem: JournalMoveProblem,
    ) {
        val retryAt = waitAfter(problem)?.let { env.deps.clock.now() + it }
        env.deps.checkpoints.save(scope, checkpoint.copy(problem = problem, retryAt = retryAt?.toEpochMilliseconds()))
        publish(JournalMoveState.Stopped(problem, retryAt))
    }

    private fun publish(state: JournalMoveState) {
        current.value = state
    }

    private suspend fun <T> server(
        scope: UploadScope,
        operation: String,
        block: suspend (String) -> T,
    ): T = env.server(scope, operation, block)

    private companion object {
        val MOVED = setOf(SpaceMigrationPhase.SWITCHED, SpaceMigrationPhase.CLEANED)
    }
}

/** What a failure means for the person. */
internal fun problemOf(error: Throwable): JournalMoveProblem =
    when (error) {
        is MigrationMismatchException -> error.problem
        is MigrationUnsupportedItemsException -> JournalMoveProblem.CopyDiffers(error.kind, error.count)
        is MigrationMediaUnavailableException -> JournalMoveProblem.MediaUnavailable(error.count)
        // The only mapping failure a retry can fix is a media file that didn't reach the personal space.
        is SpaceRecordMappingException ->
            if (error.retryable) JournalMoveProblem.MediaUnavailable(1) else JournalMoveProblem.CopyDiffers(JournalDataKind.ENTRIES, 1)
        is IdentityKeyNotFoundException -> JournalMoveProblem.IdentityKeyMissing
        is CloudApiException ->
            when {
                error.errorCode == SpaceMigrationErrorCodes.HELD_ELSEWHERE -> JournalMoveProblem.AnotherDeviceMoving
                error.errorCode == SpaceMigrationErrorCodes.WINDOW_CLOSED -> JournalMoveProblem.SwitchBackWindowClosed
                error.errorCode in QUOTA_CODES || error.statusCode == 402 || error.statusCode == 507 -> JournalMoveProblem.StorageFull
                error.errorCode == "NETWORK_ERROR" || error.statusCode in setOf(502, 503, 504) -> JournalMoveProblem.Offline
                else -> JournalMoveProblem.ServerRefused(error.errorCode)
            }
        else -> JournalMoveProblem.ServerRefused(error::class.simpleName ?: "UNKNOWN")
    }

/** How long the move waits on its own before trying again after [problem]; null to try on the next sync. */
internal fun waitAfter(problem: JournalMoveProblem): Duration? =
    when (problem) {
        JournalMoveProblem.Offline, is JournalMoveProblem.MissingOnThisDevice -> null
        JournalMoveProblem.AnotherDeviceMoving -> 1.hours
        JournalMoveProblem.StorageFull, JournalMoveProblem.SwitchBackWindowClosed -> 24.hours
        else -> 6.hours
    }

private val QUOTA_CODES = setOf("STORAGE_QUOTA_EXCEEDED", "QUOTA_EXCEEDED", "STORAGE_LIMIT_EXCEEDED")

/** The journal moved; while its legacy copy is still kept, the state says until when it can be switched back. */
private fun SpaceMigrationStatus.moved(): JournalMoveState.Moved =
    JournalMoveState.Moved(legacyKeptUntil?.takeIf { phase == SpaceMigrationPhase.SWITCHED }?.let(Instant::fromEpochMilliseconds))
