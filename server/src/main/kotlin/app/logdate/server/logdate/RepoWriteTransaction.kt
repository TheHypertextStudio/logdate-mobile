package app.logdate.server.logdate

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

/** Commits a repo mutation and its sync index as one durable write. */
@OptIn(ExperimentalUuidApi::class)
internal interface RepoWriteTransaction {
    suspend fun <T> write(
        userId: UUID,
        identityEpoch: Long = 0,
        action: suspend () -> T,
    ): T = write(action)

    suspend fun <T> write(action: suspend () -> T): T

    data object Direct : RepoWriteTransaction {
        override suspend fun <T> write(action: suspend () -> T): T = action()
    }
}
