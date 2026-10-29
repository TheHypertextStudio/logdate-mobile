package app.logdate.client.networking.atproto

import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoDisconnectChallenge
import app.logdate.shared.model.AtprotoDisconnectResponse
import app.logdate.shared.model.BlueskyStartResponse

/** Account authorization only; none of these operations installs a LogDate session or journal keys. */
interface AtprotoConnectionApi {
    val backendOrigin: String

    suspend fun snapshot(accessToken: String): Result<AtprotoConnectionSnapshot>

    suspend fun beginDisconnect(
        accessToken: String,
        expectedRevision: Long,
    ): Result<AtprotoDisconnectChallenge>

    suspend fun disconnect(
        accessToken: String,
        encodedProof: String,
    ): Result<AtprotoDisconnectResponse>

    suspend fun startReconnect(
        accessToken: String,
        challenge: String,
    ): Result<BlueskyStartResponse>

    suspend fun completeReconnect(
        accessToken: String,
        attemptId: String,
        verifier: String,
    ): Result<AtprotoConnectionSnapshot>

    fun close() = Unit
}
