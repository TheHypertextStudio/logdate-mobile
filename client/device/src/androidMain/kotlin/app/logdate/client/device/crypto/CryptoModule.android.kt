package app.logdate.client.device.crypto

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformCryptoModule(): Module =
    module {
        single<CryptoManager> { AndroidCryptoManager() }
        single<PlcRecoveryKeyManager> { DeterministicPlcRecoveryKeyManager(get(), platformPlcRecoveryKeySupport()) }
        single<BlockStoreBytes> { PlayServicesBlockStoreBytes(androidContext()) }
        single { AndroidIdentityKeyBackupStore(androidContext()) }
        single {
            val files = get<AndroidIdentityKeyBackupStore>()
            BlockStoreIdentityKeyBackupStore(get()) { !files.isKeptOnDevice() }
        }
        single { AndroidKeyBackupPreference(get(), get()) }
        single<KeyBackupPreference> { get<AndroidKeyBackupPreference>() }
        single<IdentityKeyBackupStore> {
            val flags = getOrNull<FeatureFlagStore>()
            LayeredIdentityKeyBackupStore(
                primary = get<AndroidIdentityKeyBackupStore>(),
                mirror = get<BlockStoreIdentityKeyBackupStore>(),
                mirrorEnabled = { flags?.isEnabled(FeatureFlag.PLATFORM_KEY_BACKUP) == true },
            )
        }
        single<KeyBackupStatusSource> {
            val flags = getOrNull<FeatureFlagStore>()
            AndroidKeyBackupStatusSource(get(), get<KeyBackupPreference>().cloudBackupAllowed) {
                flags?.isEnabled(FeatureFlag.PLATFORM_KEY_BACKUP) == true
            }
        }
    }
