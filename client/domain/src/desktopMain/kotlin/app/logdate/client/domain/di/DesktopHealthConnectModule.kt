package app.logdate.client.domain.di

import app.logdate.client.health.di.jvmHealthModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Desktop-specific Koin module for health-related dependencies.
 *
 * @deprecated Use the new healthDomainModule and jvmHealthModule for better architecture.
 */
@Deprecated("Use healthDomainModule and jvmHealthModule", ReplaceWith("healthDomainModule"))
val desktopHealthConnectModule: Module =
    module {
        // Include the new modules that provide the updated implementation
        includes(healthDomainModule)
        includes(jvmHealthModule)
    }
