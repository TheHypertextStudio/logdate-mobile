@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky.connection

import kotlin.uuid.Uuid

interface AccountCredentialMutationLock {
    suspend fun <T> withLock(
        accountId: Uuid,
        block: suspend () -> T,
    ): T
}
