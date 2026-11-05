package app.logdate.server.atproto

import app.logdate.server.database.requireRepoWriteOrigin
import app.logdate.server.database.toJavaUUID
import app.logdate.server.logdate.RepoWriteTransaction
import kotlinx.coroutines.CancellationException
import studio.hypertext.atproto.pds.CreateRecordRequest
import studio.hypertext.atproto.pds.DeleteRecordRequest
import studio.hypertext.atproto.pds.PdsRepoService
import studio.hypertext.atproto.pds.PutRecordRequest
import studio.hypertext.atproto.pds.runtime.DefaultPdsRepoService
import studio.hypertext.atproto.repo.RepoBlockStore
import studio.hypertext.atproto.repo.RepoRecordStore
import studio.hypertext.atproto.repo.RepoWriteResult
import kotlin.uuid.ExperimentalUuidApi

internal fun accountBoundPdsRepoService(
    records: RepoRecordStore,
    blocks: RepoBlockStore,
): PdsRepoService =
    AccountBoundPdsRepoService(DefaultPdsRepoService(records), blocks as? RepoWriteTransaction ?: RepoWriteTransaction.Direct)

/** Carries authenticated account authority through the complete repository mutation. */
@OptIn(ExperimentalUuidApi::class)
internal class AccountBoundPdsRepoService(
    private val delegate: PdsRepoService,
    private val transaction: RepoWriteTransaction,
) : PdsRepoService by delegate {
    override suspend fun createRecord(request: CreateRecordRequest): Result<RepoWriteResult> = owned { delegate.createRecord(request) }

    override suspend fun putRecord(request: PutRecordRequest): Result<RepoWriteResult> = owned { delegate.putRecord(request) }

    override suspend fun deleteRecord(request: DeleteRecordRequest): Result<Boolean> = owned { delegate.deleteRecord(request) }

    private suspend fun <T> owned(action: suspend () -> Result<T>): Result<T> =
        try {
            val origin = requireRepoWriteOrigin()
            Result.success(transaction.write(origin.accountId.toJavaUUID(), origin.identityEpoch) { action().getOrThrow() })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
}
