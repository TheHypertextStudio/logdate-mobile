package app.logdate.server.migration

import app.logdate.server.auth.AccountRepository
import app.logdate.server.logdate.InMemoryJournalMergeStore
import app.logdate.server.logdate.InMemoryLogDateAtprotoBlobRepository
import app.logdate.server.logdate.InMemoryLogDateCollectionsMetadataStore
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.syntheticRepoDid
import app.logdate.server.sync.InMemoryLocationHistoryRepository
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.RepoBlockStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

/** A [LegacyJournalEraser] for servers running without a database, over their in-memory stores. */
@OptIn(ExperimentalUuidApi::class)
internal class InMemoryLegacyJournalEraser(
    private val accounts: AccountRepository,
    private val migrations: SpaceMigrationStore,
    private val repositories: RepoBlockStore,
    private val metadata: InMemoryLogDateCollectionsMetadataStore,
    private val locationHistory: InMemoryLocationHistoryRepository,
    private val media: InMemoryLogDateMediaRepository,
    private val atprotoBlobs: InMemoryLogDateAtprotoBlobRepository,
    private val merges: InMemoryJournalMergeStore,
    private val storage: LogDateBlobStorage?,
) : LegacyJournalEraser {
    /** Deletes the files first, so a failure leaves their records for the next run to find. */
    override suspend fun erase(claim: SpaceMigration): Boolean {
        val accountId = claim.accountId
        if (!authorized(claim)) return false
        val originalDid = accounts.findById(accountId.toKotlinUuid())?.did
        val paths = media.listAllForUser(accountId).mapNotNull { it.storagePath } + atprotoBlobs.storagePaths(accountId)
        check(paths.isEmpty() || storage != null) { "Object storage cleanup is unavailable" }
        storage?.let { objects -> withContext(Dispatchers.IO) { paths.distinct().forEach(objects::deleteBlobIfExists) } }
        if (!authorized(claim) || accounts.findById(accountId.toKotlinUuid())?.did != originalDid) return false
        media.forgetAccount(accountId)
        atprotoBlobs.forgetAccount(accountId)
        val did = accounts.findById(accountId.toKotlinUuid())?.did
        val stateRepo = metadata.forgetAccount(accountId)
        val repos = listOfNotNull(did, syntheticRepoDid(accountId), stateRepo?.toString()).distinct()
        repos.forEach { repositories.clearRepo(AtprotoDid.require(it)).getOrThrow() }
        locationHistory.forgetAccount(accountId)
        merges.forgetAccount(accountId)
        return true
    }

    private suspend fun authorized(claim: SpaceMigration): Boolean {
        if (claim.phase != SpaceMigrationPhase.CLEANED || claim.cleanedAt != null) return false
        val stored = migrations.get(claim.accountId) ?: return false
        if (stored.migrationId != claim.migrationId ||
            stored.version != claim.version ||
            stored.identityEpoch != claim.identityEpoch ||
            stored.phase != SpaceMigrationPhase.CLEANED ||
            stored.cleanedAt != null
        ) {
            return false
        }
        val account = accounts.findById(claim.accountId.toKotlinUuid()) ?: return false
        return account.identityEpoch == claim.identityEpoch &&
            account.isActive &&
            account.moderation == null &&
            account.storageMode == AccountStorageMode.PERSONAL_SPACE_V1
    }
}
