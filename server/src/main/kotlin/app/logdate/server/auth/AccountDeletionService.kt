package app.logdate.server.auth

import app.logdate.server.audit.AuditCategory
import app.logdate.server.audit.AuditKey
import app.logdate.server.audit.AuditLogger
import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateMediaBlobRepository
import app.logdate.server.logging.RepoEventNotices
import app.logdate.server.oauth.OAuthGrantRevoker
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Told when an account becomes active or stops being active on this server, so relays that followed
 * its repository can keep or drop it.
 */
fun interface AccountStatusEvents {
    /** The account [did] is [active] or not; [status] is the reason the AT Protocol event stream reports when it is not. */
    suspend fun accountStatusChanged(
        did: String,
        active: Boolean,
        status: String?,
    )
}

/** Account removal commits durable external cleanup before returning success. */
@OptIn(ExperimentalUuidApi::class)
class AccountDeletionService(
    private val accountRepository: AccountRepository,
    private val mediaBlobRepository: LogDateMediaBlobRepository,
    private val backupRepository: LogDateBackupRepository,
    private val blobStorage: LogDateBlobStorage?,
    private val blobCleanup: AccountBlobCleanup? = null,
    private val oauthGrantRevoker: OAuthGrantRevoker? = null,
    private val statusEvents: AccountStatusEvents? = null,
    private val connectionCleanup: AccountConnectionCleanup? = null,
) {
    data class Summary(
        val mediaBlobsDeleted: Int,
        val backupBlobsDeleted: Int,
        val accountRowDeleted: Boolean,
    )

    suspend fun deleteAccount(accountId: Uuid): Summary =
        if (connectionCleanup != null) {
            connectionCleanup.withAccountRemoval(accountId) { deleteAccountRecords(accountId) }
        } else {
            deleteAccountRecords(accountId)
        }

    private suspend fun deleteAccountRecords(accountId: Uuid): Summary {
        val owner = accountId.toJavaUuid()
        // Apps the account approved lose access before the account itself goes.
        val did = accountRepository.findById(accountId)?.did
        did?.let { oauthGrantRevoker?.revokeAllForSubject(it) }
        // PostgreSQL's BEFORE DELETE trigger captures all references, including soft deletes.
        // Non-database development runs have no durable queue and must enumerate successfully.
        val mediaPaths = if (blobCleanup == null) mediaBlobRepository.listMedia(owner).mapNotNull { it.storagePath } else emptyList()
        val backupPaths = if (blobCleanup == null) backupRepository.listBackups(owner).map { it.storagePath } else emptyList()
        val deleted = accountRepository.deleteAccount(accountId)
        if (deleted && did != null) announceRemoval(did)
        var mediaDeleted = 0
        var backupDeleted = 0
        withContext(Dispatchers.IO) {
            if (blobCleanup != null) {
                try {
                    blobCleanup.runOnce(owner)
                } catch (_: Exception) {
                    Napier.w("Account removed; external cleanup remains in the durable queue")
                }
            } else {
                blobStorage?.let { storage ->
                    mediaPaths.forEach {
                        storage.deleteBlobIfExists(it)
                        mediaDeleted++
                    }
                    backupPaths.forEach {
                        storage.deleteBlobIfExists(it)
                        backupDeleted++
                    }
                }
            }
        }
        AuditLogger.emit(
            AuditCategory.ACCOUNT_DELETED,
            mapOf(AuditKey.ACCOUNT_ID to accountId.toString(), "accountRowDeleted" to deleted.toString()),
        )
        Napier.i("Account deletion accepted: accountRowDeleted=$deleted")
        return Summary(mediaDeleted, backupDeleted, deleted)
    }

    private suspend fun announceRemoval(did: String) {
        val events = statusEvents ?: return
        try {
            events.accountStatusChanged(did, active = false, status = DELETED_STATUS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Napier.e(RepoEventNotices.EVENT_NOT_RECORDED, failure)
        }
    }

    private companion object {
        const val DELETED_STATUS = "deleted"
    }
}
