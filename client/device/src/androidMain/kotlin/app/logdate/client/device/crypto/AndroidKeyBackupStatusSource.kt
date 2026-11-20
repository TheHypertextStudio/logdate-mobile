package app.logdate.client.device.crypto

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reports whether Android can carry the journal key off this device end to end encrypted.
 *
 * Auto Backup and Block Store share the same condition (Android 9+ with a screen lock), and Block
 * Store is the only one of the two that can report it, so its answer stands for both.
 */
class AndroidKeyBackupStatusSource(
    private val bytes: BlockStoreBytes,
    private val mirrorEnabled: suspend () -> Boolean,
) : KeyBackupStatusSource {
    override fun observe(): Flow<KeyBackupStatus> = flow { emit(status()) }

    private suspend fun status(): KeyBackupStatus {
        val encrypted =
            try {
                bytes.isEndToEndEncryptionAvailable()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Napier.w("Could not ask Google Play services about end-to-end encrypted backup", error)
                return KeyBackupStatus.DeviceOnly(DeviceOnlyReason.UNSUPPORTED)
            }
        if (!encrypted) return KeyBackupStatus.DeviceOnly(DeviceOnlyReason.NO_SCREEN_LOCK)
        val transports =
            buildSet {
                add(KeyBackupTransport.GOOGLE_AUTO_BACKUP)
                if (mirrorEnabled()) add(KeyBackupTransport.GOOGLE_BLOCK_STORE)
            }
        return KeyBackupStatus.PlatformBackup(transports)
    }
}
