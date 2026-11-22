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
    override suspend fun hasMediaBackup(): Boolean = backupFileMutex.withLock { mediaFile().exists() }

    override suspend fun readMediaIdentity(identityBinding: String): MediaIdentityBackup? =
        backupFileMutex.withLock { readMediaIdentities().firstOrNull { it.identityBinding == identityBinding } }

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
        backupFileMutex.withLock {
            val updated = RetainedIdentityRecords.addMedia(readMediaIdentities(), identity) ?: return@withLock
            writeAtomically(mediaFile(), RetainedIdentityRecords.encodeMedia(updated))
        }
    }

    override suspend fun hasRootBackup(): Boolean = backupFileMutex.withLock { backupFile().exists() || accountFile().exists() }

    override suspend fun hasBackup(): Boolean =
        backupFileMutex.withLock { backupFile().exists() || accountFile().exists() || mediaFile().exists() }

    override suspend fun readAccountIdentity(): AccountIdentityBackup? =
        backupFileMutex.withLock {
            val file = accountFile()
            if (!file.exists()) return@withLock null
            try {
                check(file.length() <= RetainedIdentityRecords.MAX_ACCOUNT_BYTES) { "Invalid account identity backup size" }
                RetainedIdentityRecords.decodeAccount(file.readBytes())
            } catch (_: Exception) {
                Napier.w("Native account identity backup needs recovery")
                throw IllegalStateException("The retained account identity backup could not be read")
            }
        }

    override suspend fun writeAccountIdentity(identity: AccountIdentityBackup) {
        backupFileMutex.withLock {
            writeAtomically(accountFile(), RetainedIdentityRecords.encodeAccount(identity))
            deleteIfPresent(backupFile())
        }
    }

    override suspend fun readPhrase(): String? =
        backupFileMutex.withLock {
            val file = backupFile()
            if (!file.exists()) return@withLock null
            runCatching {
                val phrase = file.readText().trim()
                phrase.ifEmpty { null }
            }.getOrElse {
                Napier.w("Failed to read identity recovery phrase backup")
                null
            }
        }

    override suspend fun writePhrase(phrase: String) {
        backupFileMutex.withLock {
            writeAtomically(backupFile(), phrase.encodeToByteArray())
            deleteIfPresent(accountFile())
        }
    }

    override suspend fun clear() {
        backupFileMutex.withLock {
            deleteIfPresent(backupFile())
            deleteIfPresent(accountFile())
            deleteIfPresent(mediaFile())
        }
    }

    private fun backupFile(): File = File(context.filesDir, BACKUP_PATH)

    private fun mediaFile(): File = File(context.filesDir, "identity_recovery/media_key_backup")

    private fun accountFile(): File = File(context.filesDir, "identity_recovery/account_key_backup")

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
        const val BACKUP_PATH = "identity_recovery/phrase_backup"
        val backupFileMutex = Mutex()
    }
}
