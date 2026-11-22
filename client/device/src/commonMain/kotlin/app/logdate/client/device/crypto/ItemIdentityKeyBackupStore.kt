package app.logdate.client.device.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Named secret items in a platform store that carries them off the device, such as iCloud
 * Keychain. Every call throws when the platform reports anything other than success or a missing
 * item, so an unreadable store is never mistaken for an empty one.
 */
interface IdentityBackupItems {
    suspend fun read(name: String): ByteArray?

    suspend fun write(
        name: String,
        value: ByteArray,
    )

    suspend fun delete(name: String)
}

/**
 * The identity backup for platforms that keep it in named secret items rather than files.
 *
 * It holds the same three records as Android's backup files, in the same formats: the recovery
 * phrase, or an account key with its paired media key, plus up to 64 media keys. Writing a phrase
 * removes the account key and writing an account key removes the phrase, so only one root exists.
 */
class ItemIdentityKeyBackupStore(
    private val items: IdentityBackupItems,
) : IdentityKeyBackupStore {
    private val mutex = Mutex()

    override suspend fun hasRootBackup(): Boolean = mutex.withLock { items.read(PHRASE) != null || items.read(ACCOUNT) != null }

    override suspend fun hasBackup(): Boolean = hasRootBackup() || hasMediaBackup()

    override suspend fun hasMediaBackup(): Boolean = mutex.withLock { items.read(MEDIA) != null }

    override suspend fun readPhrase(): String? =
        mutex.withLock {
            items
                .read(PHRASE)
                ?.decodeToString()
                ?.trim()
                ?.ifEmpty { null }
        }

    override suspend fun writePhrase(phrase: String) {
        require(phrase.isNotBlank())
        mutex.withLock {
            items.write(PHRASE, phrase.encodeToByteArray())
            items.delete(ACCOUNT)
        }
    }

    override suspend fun readAccountIdentity(): AccountIdentityBackup? =
        mutex.withLock { items.read(ACCOUNT)?.let(RetainedIdentityRecords::decodeAccount) }

    override suspend fun writeAccountIdentity(identity: AccountIdentityBackup) {
        val encoded = RetainedIdentityRecords.encodeAccount(identity)
        mutex.withLock {
            items.write(ACCOUNT, encoded)
            items.delete(PHRASE)
        }
    }

    override suspend fun readMediaIdentity(identityBinding: String): MediaIdentityBackup? =
        mutex.withLock { readMedia().firstOrNull { it.identityBinding == identityBinding } }

    override suspend fun writeMediaIdentity(identity: MediaIdentityBackup) {
        mutex.withLock {
            val updated = RetainedIdentityRecords.addMedia(readMedia(), identity) ?: return@withLock
            items.write(MEDIA, RetainedIdentityRecords.encodeMedia(updated))
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            items.delete(PHRASE)
            items.delete(ACCOUNT)
            items.delete(MEDIA)
        }
    }

    private suspend fun readMedia(): List<MediaIdentityBackup> = items.read(MEDIA)?.let(RetainedIdentityRecords::decodeMedia).orEmpty()

    private companion object {
        // The phrase keeps the name the first iOS store used, so a phrase it backed up still restores.
        const val PHRASE = "identity_recovery_phrase_backup_v1"
        const val ACCOUNT = "identity_account_key_backup_v1"
        const val MEDIA = "identity_media_key_backup_v1"
    }
}
