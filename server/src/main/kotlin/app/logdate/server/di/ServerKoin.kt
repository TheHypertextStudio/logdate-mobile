package app.logdate.server.di

import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.bluesky.createBlueskyOAuthRuntime
import app.logdate.server.database.createAccountCredentialMutationRuntime
import app.logdate.server.devices.accountDeviceModule
import app.logdate.server.entitlements.entitlementsModule
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import org.koin.core.context.stopKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

/**
 * Installs the Koin container with every server module, and tears it down when the application
 * stops so the next test application starts from a clean state.
 */
internal fun Application.installServerKoin(
    isDatabaseAvailable: Boolean,
    blueskyRuntimeFactory: (Boolean) -> BlueskyOAuthRuntime? = { createBlueskyOAuthRuntime(it) },
) {
    // Stop any existing Koin instance to ensure clean state for tests
    runCatching { stopKoin() }

    val credentials = createAccountCredentialMutationRuntime(isDatabaseAvailable)
    var bluesky: BlueskyOAuthRuntime? = null
    try {
        bluesky = blueskyRuntimeFactory(isDatabaseAvailable)
        install(Koin) {
            slf4jLogger()
            modules(
                serverModule(isDatabaseAvailable),
                accountDeviceModule(isDatabaseAvailable),
                entitlementsModule(databaseAvailable = isDatabaseAvailable),
                blueskyModule(bluesky),
                accountCredentialModule(credentials, isDatabaseAvailable, bluesky),
            )
        }

        monitor.subscribe(ApplicationStopped) {
            try {
                bluesky?.close()
            } finally {
                try {
                    credentials.close()
                } finally {
                    runCatching { stopKoin() }
                }
            }
        }
        bluesky?.connection?.maintenance?.start()
    } catch (failure: Throwable) {
        try {
            bluesky?.close()
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
        try {
            credentials.close()
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
        runCatching { stopKoin() }
        throw failure
    }
}
