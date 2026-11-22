package app.logdate.client.device.crypto

import kotlinx.coroutines.flow.flowOf
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformCryptoModule(): Module =
    module {
        single<CryptoManager> { IosCryptoManager() }
        single<PlcRecoveryKeyManager> { DeterministicPlcRecoveryKeyManager(get(), platformPlcRecoveryKeySupport()) }
        single<IdentityKeyBackupStore> { ItemIdentityKeyBackupStore(ICloudKeychainIdentityBackupItems()) }
        single<KeyBackupStatusSource> {
            object : KeyBackupStatusSource {
                override fun observe() = flowOf(KeyBackupStatus.PlatformBackup(setOf(KeyBackupTransport.ICLOUD_KEYCHAIN)))
            }
        }
    }
