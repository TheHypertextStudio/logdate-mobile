package app.logdate.server.atproto.firehose

import app.logdate.server.logdate.RepoWriteTransaction
import app.logdate.server.logging.RepoEventNotices
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.RepoBlockStore
import studio.hypertext.atproto.repo.SignedRepoCommit
import java.util.UUID

/**
 * Passes every call to [delegate] and reports each commit it stores to [sequencer].
 *
 * Every writer of the repository goes through one block store, so a commit made by the sync
 * routes, by `com.atproto.repo.createRecord` or by a background job reaches the event log the
 * same way, and nothing needs to remember to announce a write. The engine records a commit after it
 * has moved the repository's head, so the sequencer finds the head already pointing at it.
 *
 * Failing to announce never fails the write that triggered it: the records are saved, and the next
 * commit finds that its predecessor was never announced and announces the repository's state
 * instead. The failure is logged.
 */
internal class SequencingRepoBlockStore(
    private val delegate: RepoBlockStore,
    private val sequencer: Lazy<RepoEventSequencer>,
) : RepoBlockStore by delegate,
    RepoWriteTransaction {
    override suspend fun <T> write(
        userId: UUID,
        identityEpoch: Long,
        action: suspend () -> T,
    ): T =
        (delegate as? RepoWriteTransaction)?.write(userId, identityEpoch, action)
            ?: RepoWriteTransaction.Direct.write(userId, identityEpoch, action)

    override suspend fun <T> write(action: suspend () -> T): T = (delegate as? RepoWriteTransaction)?.write(action) ?: action()

    override suspend fun appendCommit(
        repo: AtprotoDid,
        commit: SignedRepoCommit,
    ): Result<Unit> =
        delegate.appendCommit(repo, commit).onSuccess {
            try {
                sequencer.value.commitWritten(commit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Napier.e(RepoEventNotices.EVENT_NOT_RECORDED, failure)
            }
        }
}
