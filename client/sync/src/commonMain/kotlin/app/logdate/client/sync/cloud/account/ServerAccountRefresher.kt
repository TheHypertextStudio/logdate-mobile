package app.logdate.client.sync.cloud.account

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.sync.SyncTokenRefresher
import app.logdate.client.sync.cloud.CloudApiClient
import kotlin.coroutines.cancellation.CancellationException

/** Why the signed-in account couldn't be read from its server. */
class ServerAccountUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Reads the signed-in account from `/auth/me` with this device's own session, which a linked watch's
 * device token may also do, and saves it in [accounts] for the server and account it came from.
 */
internal class ServerAccountRefresher(
    private val api: CloudApiClient,
    private val tokens: SyncTokenRefresher,
    private val sessions: SessionStorage,
    private val accounts: ServerAccountStore,
) {
    suspend fun refresh(): Result<ServerReportedAccount> {
        val bound = sessions.getOriginBoundSession() ?: return Result.failure(ServerAccountUnavailableException("No one is signed in"))
        val account =
            tokens.withFreshToken({ token -> api.getAccountInfo(token) }, "read the account").getOrElse { error ->
                if (error is CancellationException) throw error
                return Result.failure(ServerAccountUnavailableException("The account could not be read from the server", error))
            }
        if (account.id.toString() != bound.session.accountId) {
            return Result.failure(ServerAccountUnavailableException("The server described a different account than the one signed in"))
        }
        val now = sessions.getOriginBoundSession()
        if (now?.origin != bound.origin || now.session.accountId != bound.session.accountId) {
            return Result.failure(ServerAccountUnavailableException("The signed-in account changed while it was read"))
        }
        val reported = ServerReportedAccount.of(bound.origin, account)
        accounts.write(reported)
        return Result.success(reported)
    }
}
