package app.logdate.server.di

import app.logdate.server.bluesky.BlueskyAccountContinuationService
import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.bluesky.BlueskyPasskeyAccountService
import app.logdate.server.bluesky.connection.AccountSignInMethods
import app.logdate.server.bluesky.connection.AtprotoConnectionService
import app.logdate.server.bluesky.connection.AtprotoDisconnectPasskeys
import app.logdate.server.bluesky.connection.AtprotoReconnectService
import org.koin.core.module.Module
import org.koin.dsl.module

internal fun blueskyModule(runtime: BlueskyOAuthRuntime?): Module =
    module {
        if (runtime != null) {
            single { runtime }
            single { runtime.signIn }
            single { BlueskyAccountContinuationService(runtime.signIn, get(), runtime.handoffs) }
            single { BlueskyPasskeyAccountService(get(), runtime.handoffs, runtime.enrollments, get(), claimsLock = runtime.sessionLock) }
            runtime.connection?.let { connection ->
                single { AtprotoReconnectService(get(), connection.attempts, connection.authorization, get()) }
                single {
                    AtprotoConnectionService(
                        get(),
                        connection.repository,
                        AtprotoDisconnectPasskeys(get()),
                        AccountSignInMethods(get(), get(), get()),
                        get(),
                        connection.provider,
                    )
                }
            }
        }
    }
