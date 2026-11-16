package app.logdate.client.device.restore

import android.app.backup.BackupAgent
import java.io.IOException

internal fun requireNativeIosExport(
    transportFlags: Int,
    enabled: Boolean,
) {
    val required = BackupAgent.FLAG_CROSS_PLATFORM_DATA_TRANSFER_IOS or BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED
    if (!enabled || transportFlags and required != required) {
        throw IOException("Native iOS transfer is unavailable")
    }
}
