package app.logdate.client.database.encryption

import app.logdate.client.device.storage.SecureStorage
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class DatabaseKeyRecoveryRequiredException(
    cause: Throwable? = null,
) : IllegalStateException("The retained journal database needs its original encryption key", cause)

/**
 * Loads or generates the database encryption passphrase.
 *
 * The passphrase is stored in two locations:
 * 1. **Primary**: [SecureStorage] (EncryptedSharedPreferences backed by Android KeyStore).
 *    Hardware-backed, but lost when the KeyStore doesn't survive a device transfer.
 * 2. **Backup**: [PassphraseBackupStore] (a file in `filesDir/db_recovery/`).
 *    Included in both D2D transfers and cloud backups. Lets us recover the
 *    passphrase and open the encrypted database even when the KeyStore is gone.
 *
 * Existing keys are offered to the database opener without mutation. The selected key is copied
 * between stores only after SQLCipher has confirmed that it opens the retained database.
 */
@OptIn(ExperimentalEncodingApi::class)
class DatabasePassphraseProvider(
    private val secureStorage: SecureStorage,
    private val backupStore: PassphraseBackupStore,
    private val storageKey: () -> String = { DB_KEY_STORAGE_KEY },
    private val hasExistingDatabase: () -> Boolean = { false },
) {
    suspend fun getPassphraseCandidates(): List<ByteArray> {
        val primary =
            try {
                secureStorage.getString(storageKey())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Napier.w("Primary database key could not be read; trying portable recovery material")
                null
            }
        val primaryKey =
            primary?.let { encoded ->
                runCatching { Base64.decode(encoded) }.getOrNull()?.takeIf { it.size == KEY_LENGTH_BYTES }
            }
        val candidates =
            (listOfNotNull(primaryKey) + backupStore.readCandidates())
                .filter { it.size == KEY_LENGTH_BYTES }
                .distinctBy { it.toList() }
        if (candidates.isNotEmpty()) return candidates

        if (backupStore.hasBackup() || hasExistingDatabase()) throw DatabaseKeyRecoveryRequiredException()

        // A replacement key is only meaningful when there is no retained database.
        Napier.i("Generating new database passphrase (no existing key found)")
        val generated = generateDatabaseKey(KEY_LENGTH_BYTES)
        backupStore.write(generated)
        secureStorage.putString(storageKey(), Base64.encode(generated))
        return listOf(generated)
    }

    /** Persist only a key that SQLCipher has already accepted for the retained database. */
    suspend fun confirmOpenedDatabasePassphrase(passphrase: ByteArray) {
        require(passphrase.size == KEY_LENGTH_BYTES) { "Database passphrase has an invalid length" }
        backupStore.write(passphrase)
        secureStorage.putString(storageKey(), Base64.encode(passphrase))
    }

    private companion object {
        const val DB_KEY_STORAGE_KEY = "db_encryption_key"
        const val KEY_LENGTH_BYTES = 32
    }
}
