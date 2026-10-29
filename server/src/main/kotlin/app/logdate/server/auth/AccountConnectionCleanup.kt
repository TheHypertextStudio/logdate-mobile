package app.logdate.server.auth

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
interface AccountConnectionCleanup {
    suspend fun <T> withAccountRemoval(
        accountId: Uuid,
        removeAccount: suspend () -> T,
    ): T
}
