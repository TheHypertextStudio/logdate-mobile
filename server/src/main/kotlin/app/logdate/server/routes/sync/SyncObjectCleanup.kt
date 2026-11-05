package app.logdate.server.routes.sync

import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateMediaBlobRepository
import app.logdate.server.logdate.deleteLegacyObject
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

internal suspend fun cleanupMediaObject(
    userId: UUID,
    path: String,
    repository: LogDateMediaBlobRepository,
    storage: LogDateBlobStorage?,
) = cleanupCandidate {
    repository.cleanupUnreferencedObject(userId, path) { storage?.deleteLegacyObject(it) ?: false }
}

internal suspend fun cleanupBackupObject(
    userId: UUID,
    path: String,
    repository: LogDateBackupRepository,
    storage: LogDateBlobStorage,
) = cleanupCandidate {
    repository.cleanupUnreferencedObject(userId, path) { storage.deleteLegacyObject(it) }
}

private suspend fun cleanupCandidate(action: () -> Boolean) =
    withContext(Dispatchers.IO) {
        try {
            if (!action()) Napier.w("Upload object retained because cleanup was not confirmed")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Napier.w("Upload object retained because cleanup was not confirmed")
        }
    }
