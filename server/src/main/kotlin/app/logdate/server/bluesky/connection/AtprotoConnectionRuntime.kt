package app.logdate.server.bluesky.connection

import app.logdate.server.bluesky.BlueskySignInAttemptStore
import app.logdate.server.bluesky.BlueskySignInProvider

internal data class AtprotoConnectionRuntime(
    val repository: AtprotoConnectionRepository,
    val provider: AtprotoConnectionProvider,
    val attempts: BlueskySignInAttemptStore,
    val authorization: BlueskySignInProvider,
    val maintenance: AtprotoConnectionMaintenance? = null,
)
