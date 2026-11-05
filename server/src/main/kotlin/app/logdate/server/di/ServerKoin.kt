package app.logdate.server.di

import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.bluesky.createBlueskyOAuthRuntime
import app.logdate.server.database.createAccountCredentialMutationRuntime
import app.logdate.server.devices.accountDeviceModule
import app.logdate.server.entitlements.entitlementsModule
import app.logdate.server.identity.adoption.IdentityAdoptionRuntime
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.koin.core.Koin as KoinContainer

/**
 * Installs the Koin container with every server module, and tears it down when the application
 * stops so the next test application starts from a clean state.
 */
internal fun Application.installServerKoin(
    isDatabaseAvailable: Boolean,
    adoptionRuntimeFactory: (Boolean, BlueskyOAuthRuntime?, KoinContainer) -> IdentityAdoptionRuntime? =
        ::createServerIdentityAdoptionRuntime,
    blueskyRuntimeFactory: (Boolean) -> BlueskyOAuthRuntime? = { createBlueskyOAuthRuntime(it) },
) {
    // Stop any existing Koin instance to ensure clean state for tests
    runCatching { stopKoin() }

    val credentials = createAccountCredentialMutationRuntime(isDatabaseAvailable)
    val resources = mutableListOf<AutoCloseable>(credentials)
    try {
        val bluesky = blueskyRuntimeFactory(isDatabaseAvailable)?.also(resources::add)
        install(Koin) {
            slf4jLogger()
            modules(
                serverModule(isDatabaseAvailable),
                accountDeviceModule(isDatabaseAvailable),
                entitlementsModule(databaseAvailable = isDatabaseAvailable),
                blueskyModule(bluesky),
                accountCredentialModule(credentials, isDatabaseAvailable, bluesky),
                identityAdoptionRecoveryModule(isDatabaseAvailable),
            )
        }
        val adoption =
            adoptionRuntimeFactory(isDatabaseAvailable, bluesky, getKoin())?.also { runtime ->
                resources.add(runtime)
                getKoin().loadModules(listOf(module { single { runtime } }))
            }
        monitor.subscribe(ApplicationStopped) {
            closeServerResources(resources)?.let { throw it }
        }
        bluesky?.connection?.maintenance?.start()
        adoption?.start()
    } catch (failure: Throwable) {
        throw requireNotNull(closeServerResources(resources, failure))
    }
}

private fun closeServerResources(
    resources: List<AutoCloseable>,
    failure: Throwable? = null,
): Throwable? {
    var first = failure
    for (resource in resources.asReversed()) {
        try {
            resource.close()
        } catch (cleanup: Throwable) {
            first?.addSuppressed(cleanup) ?: run { first = cleanup }
        }
    }
    runCatching { stopKoin() }
    return first
}
