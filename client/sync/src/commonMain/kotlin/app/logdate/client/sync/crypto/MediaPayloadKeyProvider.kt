package app.logdate.client.sync.crypto

import app.logdate.client.device.crypto.AccountIdentityBackup
import app.logdate.client.device.crypto.CryptoManager
import app.logdate.client.device.crypto.ExistingIdentityKeySnapshot
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.KeyDerivation
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.device.storage.getBytes
import app.logdate.client.device.storage.putBytes

/**
 * Supplies the key media payloads are encrypted with.
 *
 * The key is derived from this device's identity key, so the same recovery phrase reproduces it
 * anywhere. That is what lets media uploaded on one device be opened on another, and what lets it
 * survive a reinstall. Earlier builds generated a random key per device instead, which quietly made
 * media readable only on the device that uploaded it; those keys are kept and still used where they
 * exist, because the media already encrypted under them cannot be read any other way.
 */
class MediaPayloadKeyProvider(
    private val secureStorage: SecureStorage,
    private val cryptoManager: CryptoManager,
    private val identityKeyManager: IdentityKeyManager,
    private val keyDerivation: KeyDerivation,
) {
    suspend fun getOrCreateKey(): ByteArray {
        val legacy =
            identityKeyManager.withIdentityMutation {
                if (!identityKeyManager.hasIdentityKey()) {
                    secureStorage
                        .getBytes(
                            KEY_STORAGE_KEY,
                        )?.takeIf { it.size == KEY_LENGTH_BYTES }
                } else {
                    null
                }
            }
        if (legacy != null) return legacy
        return identityKeyManager.withIdentityKey { getOrCreateKeyLocked(it) }
    }

    suspend fun getAccountKeys(accountId: String): AccountIdentityBackup =
        identityKeyManager.snapshotAccountKeys(accountId, ::getOrCreateKeyLocked)

    /** Existing approved keys only; this read never binds an offline root or creates an identity. */
    suspend fun existingAccountKeys(accountId: String): ExistingIdentityKeySnapshot? =
        identityKeyManager.snapshotExistingKeys(accountId) { key ->
            keyDerivation.deriveKey(key, CONTEXT_MEDIA, MEDIA_KEY_ID)
        }

    private suspend fun getOrCreateKeyLocked(identityKey: ByteArray): ByteArray {
        val existing = secureStorage.getBytes(KEY_STORAGE_KEY)
        if (existing != null) {
            check(existing.size == KEY_LENGTH_BYTES) { "Stored media key needs recovery" }
            return existing
        }
        val derived = keyDerivation.deriveKey(identityKey, CONTEXT_MEDIA, MEDIA_KEY_ID)
        secureStorage.putBytes(KEY_STORAGE_KEY, derived)
        return derived
    }

    /**
     * Forgets the cached media key so the next [getOrCreateKey] selects its retained key or derives one from the current
     * identity key. Call after recovering a different identity: the cache otherwise keeps serving
     * a key derived from whatever identity minted it, silently wrong for media encrypted under
     * the newly-recovered one.
     */
    suspend fun clearCachedKey() =
        identityKeyManager.withIdentityMutation {
            secureStorage.remove(KEY_STORAGE_KEY)
        }

    suspend fun canInstallAccountKey(key: ByteArray): Boolean =
        identityKeyManager.withIdentityMutation {
            val existing = secureStorage.getBytes(KEY_STORAGE_KEY)
            key.size == KEY_LENGTH_BYTES && (existing == null || existing.contentEquals(key))
        }

    suspend fun installAccountKey(key: ByteArray) {
        identityKeyManager.withIdentityMutation {
            require(key.size == KEY_LENGTH_BYTES) { "Invalid media key length" }
            val existing = secureStorage.getBytes(KEY_STORAGE_KEY)
            require(existing == null || existing.contentEquals(key)) { "Local media key conflicts with account key" }
            secureStorage.putBytes(KEY_STORAGE_KEY, key)
        }
        identityKeyManager.ensureExistingIdentityBackup()
    }

    private companion object {
        const val KEY_STORAGE_KEY = "media_payload_key_v1"
        const val KEY_LENGTH_BYTES = 32
        const val CONTEXT_MEDIA = "media_payload"

        /** Fixed so every device reproduces one media key from the same identity. */
        const val MEDIA_KEY_ID = "media_payload_v1"
    }
}
