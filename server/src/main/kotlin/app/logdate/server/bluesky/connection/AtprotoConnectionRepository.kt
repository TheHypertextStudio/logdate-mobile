@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky.connection

import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal interface AtprotoConnectionRepository {
    suspend fun snapshot(did: AtprotoDid): ConnectionSnapshot?

    suspend fun createDecision(decision: DisconnectDecision)

    suspend fun pendingDecision(
        accountId: Uuid,
        decisionId: String,
    ): DisconnectDecision?

    suspend fun authorizeDecision(
        accountId: Uuid,
        decisionId: String,
        challengeHash: String,
        now: Instant,
    ): DisconnectDecision?

    suspend fun authorizedDecision(
        accountId: Uuid,
        decisionId: String,
        now: Instant,
    ): DisconnectDecision?

    suspend fun completeDisconnect(decisionId: String): DisconnectReceipt

    suspend fun confirmRemote(decisionId: String): DisconnectReceipt

    suspend fun receipt(
        accountId: Uuid,
        decisionId: String,
    ): DisconnectReceipt?
}
