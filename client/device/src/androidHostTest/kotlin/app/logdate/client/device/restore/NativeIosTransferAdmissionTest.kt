package app.logdate.client.device.restore

import android.app.backup.BackupAgent
import app.logdate.client.datastore.featureflags.FeatureFlag
import kotlin.test.Test
import kotlin.test.assertFailsWith

class NativeIosTransferAdmissionTest {
    @Test
    fun acceptsOnlyEncryptedCanonicalIosTransfer() {
        requireNativeIosExport(
            BackupAgent.FLAG_CROSS_PLATFORM_DATA_TRANSFER_IOS or BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED,
            enabled = true,
        )
        assertFailsWith<Exception> {
            requireNativeIosExport(BackupAgent.FLAG_CROSS_PLATFORM_DATA_TRANSFER_IOS, enabled = true)
        }
        assertFailsWith<Exception> {
            requireNativeIosExport(BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED, enabled = true)
        }
    }

    @Test
    fun defaultBuildRefusesIosTransferEvenWhenTransportIsEncrypted() {
        val flag = FeatureFlag.IOS_SYSTEM_TRANSFER
        kotlin.test.assertFalse(flag.defaultEnabled)
        kotlin.test.assertFalse(flag.availableForLaunch)
        assertFailsWith<Exception> {
            requireNativeIosExport(
                BackupAgent.FLAG_CROSS_PLATFORM_DATA_TRANSFER_IOS or BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED,
                enabled = false,
            )
        }
    }
}
