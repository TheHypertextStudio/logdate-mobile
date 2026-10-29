package app.logdate.client.sync.space

import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.SyncRetryCoordinator
import app.logdate.client.sync.SyncTokenRefresher
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.lexicon.LogDateLexicons
import app.logdate.space.DataHome
import app.logdate.space.SealingKeys
import app.logdate.space.SpaceRef
import studio.hypertext.atproto.lexicon.LexiconRecordValidator
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything the personal-space transport needs from outside the sync engine. Leave it out of
 * [app.logdate.client.sync.DefaultSyncManager] and every kind keeps syncing through its own
 * endpoints.
 *
 * @param accountDid the signed-in account's DID, which names its personal space.
 * @param identityKey reads an existing key bound to the captured upload scope's account.
 */
class SpaceSyncDependencies(
    val registry: SpaceRecordRegistry,
    val gate: SpaceTransportGate,
    val homes: SpaceDataHomes,
    val swapStore: SpaceSwapStore,
    val accountDid: suspend () -> String?,
    val identityKey: suspend (accountId: String) -> ByteArray,
    val validator: LexiconRecordValidator = LogDateLexicons.validator,
)

/** The signed-in account's personal space, ready to read and write for one sync pass. */
internal class SpaceAccess(
    val scope: UploadScope,
    val space: SpaceRef,
    val author: String,
    val keys: SealingKeys,
    /** Pins each request to [scope] where the account repository can bind requests to a server. */
    val requestScope: UploadScope?,
)

/** Sends personal-space calls with a fresh token, translated into the sync layer's failures. */
internal class SpaceClient(
    private val tokenRefresher: SyncTokenRefresher,
    private val homes: SpaceDataHomes,
    private val access: SpaceAccess,
) {
    suspend fun <T> call(
        operation: String,
        block: suspend DataHome.() -> T,
    ): Result<T> =
        tokenRefresher.withFreshToken(
            { token -> spaceCall { homes.open(access.scope.serverOrigin, token).block() } },
            operation,
            expectedScope = access.requestScope,
        )
}

/**
 * The personal-space transport inside one sync engine: uploads outbox entries of registered kinds as
 * sealed records, and stages the space's change feed in the download inbox for each kind's existing
 * download path to apply.
 */
internal class SpaceSync(
    private val dependencies: SpaceSyncDependencies,
    private val tokenRefresher: SyncTokenRefresher,
    syncMetadataService: SyncMetadataService,
    retryCoordinator: SyncRetryCoordinator,
    private val downloadInbox: DownloadInbox?,
    recordProgress: (Int) -> Unit,
) {
    private val rateLimit = SpaceRateLimit()

    private val uploader =
        SpaceUploader(dependencies.swapStore, syncMetadataService, retryCoordinator, dependencies.validator, rateLimit, recordProgress)

    private val downloader =
        downloadInbox?.let { SpaceDownloader(dependencies.registry, dependencies.swapStore, it, dependencies.validator, rateLimit) }

    /** The pull of the current download pass, shared by every kind that reads in it. */
    private var passPull: Result<Unit>? = null
    private var inPass = false

    /** The registered kind for [entityType], when this device syncs it through the personal space now. */
    suspend fun activeKind(entityType: EntityType): SpaceKind<*>? {
        val kind = dependencies.registry.forEntityType(entityType) ?: return null
        return kind.takeIf { dependencies.gate.route() == SpaceRoute.Space }
    }

    /** Where this device syncs the signed-in account now. */
    suspend fun route(): SpaceRoute = dependencies.gate.route()

    /** Why nothing may sync for the signed-in account, when its storage mode rules out both transports. */
    suspend fun blocked(): SpaceRoute.Blocked? = dependencies.gate.route() as? SpaceRoute.Blocked

    /**
     * Uploads [kind]'s outbox entries.
     *
     * @param begin starts each create or update: records the attempt and returns the item to send,
     *   or null to leave the entry for later. Notes upload their media here first.
     * @param afterDelete runs once a deletion reached the space.
     */
    suspend fun <T : Any> upload(
        kind: SpaceKind<T>,
        uploadsUpdates: Boolean,
        begin: (suspend (PendingUpload, T, MutableList<SyncError>) -> T?)?,
        afterDelete: suspend (PendingUpload) -> Unit = {},
    ): SyncResult {
        val access = access().getOrElse { return unavailable(it, "upload") }
        return uploader.upload(kind, access, SpaceClient(tokenRefresher, dependencies.homes, access), uploadsUpdates, begin, afterDelete)
    }

    /**
     * [upload] for a kind found by entity type. Every item it handles comes from that kind's own
     * [SpaceLocalRecords] and goes to its own record types, so the item type always agrees.
     */
    suspend fun uploadKind(
        kind: SpaceKind<*>,
        uploadsUpdates: Boolean,
    ): SyncResult {
        @Suppress("UNCHECKED_CAST")
        return upload(kind as SpaceKind<Any>, uploadsUpdates, begin = null)
    }

    /** Runs one download pass, in which the change feed is pulled once however many kinds read it. */
    suspend fun <R> downloadPass(block: suspend () -> R): R {
        inPass = true
        passPull = null
        try {
            return block()
        } finally {
            inPass = false
            passPull = null
        }
    }

    /**
     * Stages everything new in the space's change feed, once per download pass, then reads [kind]'s
     * staged records. A failed fetch still offers the records already staged, as the per-kind
     * downloads do, and reports the failure through the inbox; a refusal of the session is returned
     * so the token is refreshed.
     */
    suspend fun <T : Any> read(kind: SpaceKind<T>): Result<SpaceStagedRecords<T>> {
        val inbox = downloadInbox ?: return Result.failure(SpaceUnavailableException("The personal space needs the download inbox"))
        val pull = downloader ?: return Result.failure(SpaceUnavailableException("The personal space needs the download inbox"))
        val access = access().getOrElse { return Result.failure(it) }
        val selected = inbox.currentScope()
        if (selected != DownloadScope(access.scope.ownerId, access.scope.serverOrigin)) return Result.failure(SpaceScopeChangedException())
        val pulled =
            passPull?.takeIf { inPass } ?: pull.pull(access, SpaceClient(tokenRefresher, dependencies.homes, access)).also {
                if (inPass && !it.isSessionRefusal()) passPull = it
            }
        val staged = SpaceStagedRecords(kind, inbox, dependencies.swapStore, access, selected)
        val failure = pulled.exceptionOrNull() ?: return Result.success(staged)
        if (pulled.isSessionRefusal() || !staged.hasPending()) return Result.failure(failure)
        return Result.success(staged)
    }

    private suspend fun access(): Result<SpaceAccess> {
        val scope =
            tokenRefresher.currentUploadScope()
                ?: return Result.failure(SpaceUnavailableException("No account is signed in"))
        val requestScope = tokenRefresher.currentRequestScope()
        return try {
            val did =
                dependencies.accountDid()
                    ?: return Result.failure(SpaceUnavailableException("This account has no DID, so it has no personal space"))
            val keys = SealingKeys.personal(dependencies.identityKey(scope.ownerId))
            if (tokenRefresher.currentUploadScope() != scope) throw SpaceScopeChangedException()
            Result.success(SpaceAccess(scope, SpaceRef.personal(did), did, keys, requestScope))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun unavailable(
        error: Throwable,
        operation: String,
    ): SyncResult =
        SyncResult(
            success = false,
            errors = listOf(SyncError(error.spaceErrorType(), "Personal space $operation failed: ${error.message}", error)),
        )
}

private fun Result<Unit>.isSessionRefusal(): Boolean = (exceptionOrNull() as? CloudApiException)?.statusCode == 401
