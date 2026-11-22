package app.logdate.client.device.crypto

import android.content.Context
import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Stores a recoverable backup of the identity recovery phrase in a file within
 * [Context.getFilesDir]. Mirrors [app.logdate.client.database.encryption.PassphraseBackupStore]'s
 * trade-off for the database passphrase: [app.logdate.client.device.storage.SecureStorage]
 * (EncryptedSharedPreferences backed by the Android KeyStore) is lost whenever the device's
 * KeyStore doesn't survive a device-to-device transfer or cloud restore, while this file does --
 * it is included in both, via `data_extraction_rules.xml` and `backup_rules.xml`.
 *
 * Phrase-derived identities store their phrase. Account identities installed without a
 * phrase store a versioned account-bound key instead. Both representations require the
 * encrypted cloud transport configured by the app's native backup rules.
 *
 * When the person chooses to keep the key on this device, the files move to a directory that
 * only device-to-device transfer carries (see [keepOnDevice]).
 *
 * ## Security model
 *
 * Protected the same way as the database passphrase backup:
 * - Linux file permissions (readable only by the app process)
 * - Android file-based encryption (FBE) at rest
 * - Google's client-side encryption in cloud backups
 *
 * It is NOT protected by a hardware-backed KeyStore key, which is a deliberate trade-off: losing
 * the hardware-backed defense-in-depth is preferable to irrecoverable data loss when the KeyStore
 * doesn't survive a device transfer.
 */
class AndroidIdentityKeyBackupStore(
    private val context: Context,
) : IdentityKeyBackupStore {
    override suspend fun hasMediaBackup(): Boolean = locked { mediaFile().exists() }

    override suspend fun readMediaIdentity(identityBinding: String): MediaIdentityBackup? =
        locked { readMediaIdentities().firstOrNull { it.identityBinding == identityBinding } }

    private fun readMediaIdentities(): List<MediaIdentityBackup> {
        val file = mediaFile()
        if (!file.exists()) return emptyList()
        val encoded =
            try {
                check(file.length() <= RetainedIdentityRecords.MAX_MEDIA_BYTES)
                file.readBytes()
            } catch (_: Exception) {
                throw IllegalStateException("The retained media recovery backup could not be read")
            }
        return RetainedIdentityRecords.decodeMedia(encoded)
    }

    override suspend fun writeMediaIdentity(identity: MediaIdentityBackup) {
        locked {
            val updated = RetainedIdentityRecords.addMedia(readMediaIdentities(), identity) ?: return@locked
            writeAtomically(mediaFile(), RetainedIdentityRecords.encodeMedia(updated))
        }
    }

    override suspend fun hasRootBackup(): Boolean = locked { backupFile().exists() || accountFile().exists() }

    override suspend fun hasBackup(): Boolean = locked { backupFile().exists() || accountFile().exists() || mediaFile().exists() }

    override suspend fun readAccountIdentity(): AccountIdentityBackup? =
        locked {
            val file = accountFile()
            if (!file.exists()) return@locked null
            try {
                check(file.length() <= RetainedIdentityRecords.MAX_ACCOUNT_BYTES) { "Invalid account identity backup size" }
                RetainedIdentityRecords.decodeAccount(file.readBytes())
            } catch (_: Exception) {
                Napier.w("Native account identity backup needs recovery")
                throw IllegalStateException("The retained account identity backup could not be read")
            }
        }

    override suspend fun writeAccountIdentity(identity: AccountIdentityBackup) {
        locked {
            writeAtomically(accountFile(), RetainedIdentityRecords.encodeAccount(identity))
            deleteIfPresent(backupFile())
        }
    }

    override suspend fun readPhrase(): String? =
        locked {
            val file = backupFile()
            if (!file.exists()) return@locked null
            runCatching {
                val phrase = file.readText().trim()
                phrase.ifEmpty { null }
            }.getOrElse {
                Napier.w("Failed to read identity recovery phrase backup")
                null
            }
        }

    override suspend fun writePhrase(phrase: String) {
        locked {
            writeAtomically(backupFile(), phrase.encodeToByteArray())
            deleteIfPresent(accountFile())
        }
    }

    override suspend fun clear() {
        locked {
            deleteIfPresent(backupFile())
            deleteIfPresent(accountFile())
            deleteIfPresent(mediaFile())
        }
    }

    /** Whether the person chose to keep these files out of cloud backup. */
    suspend fun isKeptOnDevice(): Boolean = locked { keptOnDevice() }

    /**
     * Moves the backup files between the directory that cloud backup and device transfer carry and
     * the one only device transfer carries. Cloud backup drops the files from its copy on its next
     * run, because each run replaces the app's whole backup set.
     */
    suspend fun keepOnDevice(onDevice: Boolean) {
        locked {
            if (onDevice) {
                writeAtomically(markerFile(), byteArrayOf())
            } else {
                deleteIfPresent(markerFile())
            }
            settle()
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T =
        backupFileMutex.withLock {
            settle()
            block()
        }

    /**
     * Brings every file into the directory the current choice names. A move interrupted by a crash
     * finishes here on the next call. A file already present at the target is the newer one, since
     * every write goes to the target, so the leftover is removed.
     */
    private fun settle() {
        val target = activeDirectory()
        val other = if (target == DEVICE_ONLY_DIRECTORY) SHARED_DIRECTORY else DEVICE_ONLY_DIRECTORY
        FILE_NAMES.forEach { name ->
            val source = File(context.filesDir, "$other/$name")
            if (!source.exists()) return@forEach
            val destination = File(context.filesDir, "$target/$name")
            if (destination.exists()) {
                deleteIfPresent(source)
            } else {
                destination.parentFile?.mkdirs()
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    private fun keptOnDevice(): Boolean = markerFile().exists()

    private fun activeDirectory(): String = if (keptOnDevice()) DEVICE_ONLY_DIRECTORY else SHARED_DIRECTORY

    private fun markerFile(): File = File(context.filesDir, "$DEVICE_ONLY_DIRECTORY/$MARKER")

    private fun backupFile(): File = File(context.filesDir, "${activeDirectory()}/$PHRASE")

    private fun mediaFile(): File = File(context.filesDir, "${activeDirectory()}/$MEDIA")

    private fun accountFile(): File = File(context.filesDir, "${activeDirectory()}/$ACCOUNT")

    private fun writeAtomically(
        file: File,
        contents: ByteArray,
    ) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.part")
        FileOutputStream(temporary).use {
            it.write(contents)
            it.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun deleteIfPresent(file: File) {
        check(!file.exists() || file.delete()) { "Unable to remove obsolete identity recovery material" }
    }

    private companion object {
        // Both directories are named in the app's backup rules: cloud backup carries only the
        // first, and device-to-device transfer carries both.
        const val SHARED_DIRECTORY = "identity_recovery"
        const val DEVICE_ONLY_DIRECTORY = "identity_recovery_device"
        const val MARKER = "keep_on_device"
        const val PHRASE = "phrase_backup"
        const val ACCOUNT = "account_key_backup"
        const val MEDIA = "media_key_backup"
        val FILE_NAMES = listOf(PHRASE, ACCOUNT, MEDIA)
        val backupFileMutex = Mutex()
    }
}
