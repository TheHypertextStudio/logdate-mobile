package app.logdate.server.di

import app.logdate.server.auth.AccountConnectionCleanup
import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.database.AccountCredentialMutationRuntime
import app.logdate.server.database.DatabaseConfig
import app.logdate.server.database.PostgreSQLAtprotoAccountCleanup
import app.logdate.server.database.PostgreSQLBlueskyOAuthSessionLock
import org.koin.dsl.module

internal fun accountCredentialModule(
    credentials: AccountCredentialMutationRuntime,
    databaseAvailable: Boolean,
    bluesky: BlueskyOAuthRuntime?,
) = module {
    single { credentials.lock }
    if (databaseAvailable) {
        val source = checkNotNull(DatabaseConfig.initializedDataSource)
        single<AccountConnectionCleanup> {
            PostgreSQLAtprotoAccountCleanup(get(), source, get(), PostgreSQLBlueskyOAuthSessionLock(source), bluesky?.connection?.provider)
        }
    }
}
