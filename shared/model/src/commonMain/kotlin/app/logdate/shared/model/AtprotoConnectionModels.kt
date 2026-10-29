package app.logdate.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class AtprotoReconnectStartRequest(
    val challenge: String,
) {
    override fun toString(): String = "AtprotoReconnectStartRequest()"
}

@Serializable
data class AtprotoReconnectCompleteRequest(
    val attemptId: String,
    val verifier: String,
) {
    override fun toString(): String = "AtprotoReconnectCompleteRequest()"
}

@Serializable
data class AtprotoConnectionSnapshot(
    val state: AtprotoConnectionState,
    val revision: Long,
    val observedAt: String,
    val eligible: Boolean,
    val canDisconnect: Boolean,
)

@Serializable
data class AtprotoDisconnectBeginRequest(
    val expectedRevision: Long,
)

@Serializable
data class AtprotoDisconnectChallenge(
    val decisionId: String,
    val expectedRevision: Long,
    val authenticationOptions: PasskeyAuthenticationOptions,
    val expiresAt: String,
) {
    override fun toString(): String = "AtprotoDisconnectChallenge(expectedRevision=$expectedRevision)"
}

@Serializable
data class AtprotoDisconnectRequest(
    val decisionId: String,
    val challenge: String,
    val credential: PasskeyAssertionResponse,
) {
    override fun toString(): String = "AtprotoDisconnectRequest"
}

@Serializable
data class AtprotoDisconnectReceipt(
    val decisionId: String,
    val disconnectedRevision: Long,
    val completedAt: String,
    val remoteConfirmed: Boolean,
) {
    override fun toString(): String = "AtprotoDisconnectReceipt(revision=$disconnectedRevision, remoteConfirmed=$remoteConfirmed)"
}

@Serializable
data class AtprotoDisconnectResponse(
    val receipt: AtprotoDisconnectReceipt,
    val current: AtprotoConnectionSnapshot,
)
