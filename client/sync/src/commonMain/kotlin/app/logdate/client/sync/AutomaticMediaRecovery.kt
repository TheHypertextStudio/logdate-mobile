package app.logdate.client.sync

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.media.ManagedMediaPublicationCustody
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.CloudMediaDataSource
import app.logdate.client.sync.metadata.MediaSyncRef
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.CloudAccountRepository
import app.logdate.shared.model.diagnostics.DiagnosticReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Recovers a known attachment using the same bound session and token refresh as ordinary sync. */
class AutomaticMediaRecovery(
    private val media: MediaManager,
    private val refs: MediaSyncRefStore,
    cloud: CloudMediaDataSource,
    sessions: SessionStorage,
    accounts: CloudAccountRepository,
    private val owner: CanonicalOwnerProvider,
    private val config: LogDateConfigRepository,
    references: StoredMediaReferences = StoredMediaReferences.Unchanged,
    private val dataUsagePolicy: app.logdate.client.networking.DataUsagePolicy? = null,
    publicationCustody: ManagedMediaPublicationCustody = ManagedMediaPublicationCustody.None,
) {
    private val transfer =
        SyncMediaTransfer(
            media,
            refs,
            cloud,
            references,
            { dataUsagePolicy?.let { mediaSyncAllowed(it.currentMode(), currentScope()) } ?: true },
            publicationCustody,
        )
    private val tokens = SyncTokenRefresher(sessions, accounts)

    suspend fun recover(
        entityId: Uuid,
        draftId: Uuid?,
        kind: String,
        sourceReference: String,
        unavailableReference: String,
        scope: UploadScope,
    ): AutomaticMediaRecoveryResult {
        val recoverySource =
            if (UnavailableMediaReferences.requiresNativeQuarantine(
                    sourceReference,
                )
            ) {
                UnavailableMediaReferences.nativeRestore(sourceReference)
            } else {
                sourceReference
            }
        if (currentScope() != scope) return AutomaticMediaRecoveryResult.Pending(DiagnosticReason.UNKNOWN)
        try {
            val cached = if (draftId == null) refs.getScoped(entityId, scope) else refs.getDraftAssetScoped(draftId, entityId, kind, scope)
            if (cached != null &&
                (
                    cached.remoteUrl == recoverySource ||
                        transfer.sameLocalFile(cached.localUri, recoverySource) ||
                        cached.localAliases.any { transfer.sameLocalFile(it, recoverySource) }
                ) &&
                transfer.originalAvailable(cached.localUri, recoverySource)
            ) {
                if (currentScope() != scope) return AutomaticMediaRecoveryResult.Pending(DiagnosticReason.UNKNOWN)
                retainMapping(cached, entityId, draftId, kind, unavailableReference, scope)
                return AutomaticMediaRecoveryResult.Available(cached.localUri)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return AutomaticMediaRecoveryResult.Pending(DiagnosticReason.LOCAL_STORAGE)
        }
        if (transfer.isRemoteRef(recoverySource) && !recoverySource.startsWith("${scope.serverOrigin.trimEnd('/')}/")) {
            return AutomaticMediaRecoveryResult.Pending(DiagnosticReason.MISSING_MEDIA)
        }
        val attempt =
            tokens.withFreshToken({ token ->
                runCatchingUnlessCancelled {
                    val candidate = download(token, entityId, draftId, kind, recoverySource)
                    candidate.second?.let { mapping -> retainMapping(mapping, entityId, draftId, kind, unavailableReference, scope) }
                    candidate.first
                }
            }, "automatic media recovery", scope)
        return attempt.getOrNull()?.let { AutomaticMediaRecoveryResult.Available(it) }
            ?: AutomaticMediaRecoveryResult.Pending(
                when (val error = attempt.exceptionOrNull()) {
                    is MediaRecoveryException -> error.reason
                    is CloudApiException -> if (error.statusCode == 401) DiagnosticReason.SIGN_IN_REQUIRED else DiagnosticReason.UNKNOWN
                    else -> DiagnosticReason.UNKNOWN
                },
            )
    }

    private suspend fun download(
        token: String,
        entityId: Uuid,
        draftId: Uuid?,
        kind: String,
        sourceReference: String,
    ): Pair<String, MediaSyncRef?> =
        if (draftId == null) {
            val note =
                JournalNote.Image(
                    uid = entityId,
                    creationTimestamp = Instant.fromEpochMilliseconds(0),
                    lastUpdated = Instant.fromEpochMilliseconds(0),
                    mediaRef = sourceReference,
                )
            when (val result = transfer.hydrate(token, note)) {
                is MediaHydrationResult.Pending -> {
                    if (result.reason ==
                        DiagnosticReason.SIGN_IN_REQUIRED
                    ) {
                        throw CloudApiException("SESSION_EXPIRED", "Session refresh required", 401)
                    }
                    throw MediaRecoveryException(result.reason)
                }

                is MediaHydrationResult.Available -> {
                    requireNotNull(result.note.mediaRefOrNull()) to result.mapping
                }
            }
        } else {
            val mappings = mutableListOf<DraftAssetMapping>()
            val reference =
                try {
                    transfer.hydrateDraftAsset(token, draftId, entityId, kind, sourceReference, mappings)
                } catch (error: DraftMediaFailure) {
                    if (error.reason ==
                        DiagnosticReason.SIGN_IN_REQUIRED
                    ) {
                        throw CloudApiException("SESSION_EXPIRED", "Session refresh required", 401)
                    }
                    throw MediaRecoveryException(error.reason)
                }
            requireNotNull(reference) to mappings.lastOrNull()?.ref
        }

    private suspend fun currentScope() = UploadScope(owner.getCanonicalOwnerId(), config.getCurrentBackendUrl().trimEnd('/'))

    private suspend fun retainMapping(
        mapping: MediaSyncRef,
        entityId: Uuid,
        draftId: Uuid?,
        kind: String,
        unavailableReference: String,
        scope: UploadScope,
    ) {
        val retained =
            mapping.copy(
                ownerId = scope.ownerId,
                serverOrigin = scope.serverOrigin,
                localAliases = (listOf(unavailableReference) + mapping.localAliases).distinct().take(8),
            )
        if (draftId == null) refs.upsert(retained) else refs.upsertDraftAsset(draftId, entityId, kind, retained)
    }
}

sealed interface AutomaticMediaRecoveryResult {
    data class Available(
        val reference: String,
    ) : AutomaticMediaRecoveryResult

    data class Pending(
        val reason: DiagnosticReason,
    ) : AutomaticMediaRecoveryResult
}
