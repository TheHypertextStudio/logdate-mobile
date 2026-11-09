package app.logdate.feature.core.di

import org.koin.core.module.Module

/**
 * Platform-specific dependencies for the core feature module.
 */
expect val corePlatformModule: Module

/**
 * Action invoked to trigger a cloud restore after sign-in or recovery.
 */
fun interface CloudRestoreAction {
    fun invoke()
}
