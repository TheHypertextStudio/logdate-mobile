package app.logdate.client.device.crypto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A platform service that can carry the journal key off this device, end-to-end encrypted. */
enum class KeyBackupTransport {
    ICLOUD_KEYCHAIN,
    GOOGLE_BLOCK_STORE,
    GOOGLE_AUTO_BACKUP,
}

/** Why the journal key stays on this device only. */
enum class DeviceOnlyReason {
    /** Android can only encrypt the backup end to end once a screen lock is set. */
    NO_SCREEN_LOCK,

    /** The person chose to keep the key on this device. */
    USER_OPTED_OUT,

    /** This platform has no cloud store an app can put a secret in. */
    UNSUPPORTED,
}

/**
 * Whether the journal key can survive the loss of this device without a LogDate account.
 *
 * Neither Apple nor Google lets an app see whether the person's own backup switch is on, so
 * [PlatformBackup] means the key is eligible and handed to the platform, not that a copy is
 * confirmed in the cloud. Copy must say "when backup is on", never "backed up".
 */
sealed interface KeyBackupStatus {
    data class PlatformBackup(
        val transports: Set<KeyBackupTransport>,
    ) : KeyBackupStatus

    data class DeviceOnly(
        val reason: DeviceOnlyReason,
    ) : KeyBackupStatus
}

interface KeyBackupStatusSource {
    fun observe(): Flow<KeyBackupStatus>
}

/** For platforms with no cloud store for secrets: Wear OS, Compose desktop, Windows and Linux. */
object UnsupportedKeyBackupStatusSource : KeyBackupStatusSource {
    override fun observe(): Flow<KeyBackupStatus> = flowOf(KeyBackupStatus.DeviceOnly(DeviceOnlyReason.UNSUPPORTED))
}

/**
 * The person's choice to keep the journal key out of cloud backup ("Keep my key on this device
 * only"). Cloud backup is allowed by default. Turning it off removes the cloud copy at the
 * platform's next backup and keeps the copy on this device, plus device-to-device transfer.
 *
 * Only platforms that can honor the choice provide one.
 */
interface KeyBackupPreference {
    val cloudBackupAllowed: Flow<Boolean>

    suspend fun setCloudBackupAllowed(allowed: Boolean)
}
