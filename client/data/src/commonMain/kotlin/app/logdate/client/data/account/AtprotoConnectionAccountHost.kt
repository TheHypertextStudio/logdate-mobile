package app.logdate.client.data.account

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionAuthenticationSnapshot
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.auth.AtprotoConnectionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Uses the existing guarded first-party renewal path without opening or replacing journal keys. */
internal class AtprotoConnectionAccountHost(
    private val sessions: SessionStorage,
    private val origin: () -> String,
    private val renew: suspend (OriginBoundSession) -> Result<Unit>,
) {
    suspend fun binding(): AtprotoConnectionBinding? = capture()?.first

    suspend fun accessToken(
        binding: AtprotoConnectionBinding,
        rejectedToken: String?,
    ): String {
        val captured = owned(binding)
        val session = requireNotNull(captured.session)
        if (rejectedToken == null || session.session.accessToken != rejectedToken) return session.session.accessToken
        renew(session).getOrThrow()
        val renewed = owned(binding)
        return requireNotNull(renewed.session).session.accessToken
    }

    private suspend fun owned(expected: AtprotoConnectionBinding): SessionAuthenticationSnapshot {
        currentCoroutineContext().ensureActive()
        val captured = capture()
        if (captured == null || captured.first != expected) throw CancellationException("Account authorization changed")
        return captured.second
    }

    private suspend fun capture(): Pair<AtprotoConnectionBinding, SessionAuthenticationSnapshot>? {
        if (!sessions.supportsGuardedAuthentication) return null
        val snapshot = sessions.captureAuthenticationSnapshot() ?: return null
        val scoped = snapshot.session ?: return null
        val backend = origin()
        if (scoped.origin != backend ||
            snapshot.guard.origin != backend ||
            !sessions.isAuthenticationGuardCurrent(snapshot.guard)
        ) {
            return null
        }
        val binding =
            runCatching {
                AtprotoConnectionBinding.fromGrant(backend, scoped.session.accountId, scoped.session.refreshToken)
            }.getOrNull() ?: return null
        return binding to snapshot
    }
}
