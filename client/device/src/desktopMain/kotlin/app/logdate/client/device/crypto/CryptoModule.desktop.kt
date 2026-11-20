package app.logdate.client.device.crypto

import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformCryptoModule(): Module =
    module {
        single<CryptoManager> { DesktopCryptoManager() }
        single<PlcRecoveryKeyManager> { DeterministicPlcRecoveryKeyManager(get(), platformPlcRecoveryKeySupport()) }
        // No cloud backup or device-transfer mechanism exists for desktop today, so there is
        // nothing to mirror the recovery phrase into.
        single<IdentityKeyBackupStore> { NoOpIdentityKeyBackupStore }
        single<KeyBackupStatusSource> { UnsupportedKeyBackupStatusSource }
    }
