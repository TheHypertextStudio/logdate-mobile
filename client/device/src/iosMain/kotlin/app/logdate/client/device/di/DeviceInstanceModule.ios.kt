package app.logdate.client.device.di

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.OriginSessionVault
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.AppInfoProvider
import app.logdate.client.device.IosAccountManager
import app.logdate.client.device.IosAppInfoProvider
import app.logdate.client.device.PlatformAccountManager
import app.logdate.client.device.identity.DefaultDeviceIdProvider
import app.logdate.client.device.identity.KeychainWrapperImpl
import app.logdate.client.device.identity.di.deviceIdentityModule
import app.logdate.client.device.storage.IosSecureStorage
import app.logdate.client.device.storage.SecureSessionStorage
import app.logdate.client.device.storage.SecureStorage
import app.logdate.shared.config.LogDateConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * iOS-specific device instance module that complements the deviceIdentityModule.
 */
actual val deviceInstanceModule: Module =
    module {
        // Include the device identity module (which already provides DeviceIdProvider)
        includes(deviceIdentityModule)

        // Create a SupervisorJob that will be cancelled when the app is destroyed
        single<Job> { SupervisorJob() }

        // Create a CoroutineScope that uses the application dispatcher and supervisor job
        single {
            CoroutineScope(get<Job>() + Dispatchers.Default)
        }

        single<SecureStorage> { IosSecureStorage() }

        single {
            SecureSessionStorage(
                secureStorage = get(),
                configRepository = get<LogDateConfigRepository>(),
                privacyEpoch = get(),
                scope = get<CoroutineScope>(),
            )
        }
        single<SessionStorage> { get<SecureSessionStorage>() }
        single<OriginSessionVault> { get<SecureSessionStorage>() }

        // New device ID provider using KeyValueStorage
        single(named("modernDeviceIdProvider")) {
            DefaultDeviceIdProvider(get<KeyValueStorage>(named("deviceKeyValueStorage")))
        }

        // Provide iOS-specific app info provider
        single<AppInfoProvider> {
            IosAppInfoProvider()
        }

        // Provide iOS-specific account manager
        single<PlatformAccountManager> {
            IosAccountManager()
        }
    }
