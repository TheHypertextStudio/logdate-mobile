@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky.connection

import app.logdate.shared.model.AtprotoConnectionState
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal data class ConnectionSnapshot(
    val did: AtprotoDid,
    val state: AtprotoConnectionState,
    val revision: Long,
    val observedAt: Instant,
) {
    override fun toString(): String = "ConnectionSnapshot(state=$state, revision=$revision)"
}

internal data class DisconnectDecision(
    val id: String,
    val accountId: Uuid,
    val did: AtprotoDid,
    val expectedRevision: Long,
    val challengeHash: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "DisconnectDecision(expectedRevision=$expectedRevision)"
}

internal data class DisconnectReceipt(
    val decisionId: String,
    val disconnectedRevision: Long,
    val completedAt: Instant,
    val remoteConfirmed: Boolean,
) {
    override fun toString(): String = "DisconnectReceipt(revision=$disconnectedRevision, remoteConfirmed=$remoteConfirmed)"
}

internal class ConnectionChanged : IllegalStateException("The connection changed")
