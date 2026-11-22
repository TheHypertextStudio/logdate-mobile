package app.logdate.client.device.crypto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart

/**
 * Applies the "Keep my key on this device only" choice to both Android backup paths: the key files
 * move out of Auto Backup's directory, and the Block Store entry is written again without cloud
 * backup. The choice lives with the files, so a device-to-device transfer keeps it.
 */
class AndroidKeyBackupPreference(
    private val files: AndroidIdentityKeyBackupStore,
    private val mirror: BlockStoreIdentityKeyBackupStore,
) : KeyBackupPreference {
    private val allowed = MutableStateFlow<Boolean?>(null)

    override val cloudBackupAllowed: Flow<Boolean> =
        allowed
            .onStart { if (allowed.value == null) allowed.compareAndSet(null, !files.isKeptOnDevice()) }
            .filterNotNull()

    override suspend fun setCloudBackupAllowed(allowed: Boolean) {
        files.keepOnDevice(!allowed)
        this.allowed.value = allowed
        // Throws when Play services fails, so the caller can report it and the person can retry.
        mirror.reapplyCloudChoice()
    }
}
