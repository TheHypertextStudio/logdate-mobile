package app.logdate.server.identity

import java.security.GeneralSecurityException

/** One stored private key, encrypted under a key-encryption key. */
data class EncryptedPrivateKey(
    val id: String,
    val privateKeyEncrypted: String,
)

/** A table of private keys encrypted at rest, as [StoredKeyRewrap] sees it. */
interface EncryptedPrivateKeyStore {
    /**
     * The keys that can still be used to sign. Revoked keys are never decrypted again, so they are
     * left out: there is nothing to re-encrypt, and one nobody can read is not a fault.
     */
    suspend fun encryptedPrivateKeys(): List<EncryptedPrivateKey>

    /**
     * Replaces the encrypted value of key [id] with [replacement], but only while it still equals
     * [expected], so instances racing to re-encrypt the same key can't overwrite each other.
     * Returns whether the value was replaced.
     */
    suspend fun replaceEncryptedPrivateKey(
        id: String,
        expected: String,
        replacement: String,
    ): Boolean
}

/**
 * Re-encrypts every stored private key under the current key-encryption key in the current format,
 * so the previous key-encryption key can be retired and values from before keys were bound to where
 * they are stored are upgraded. Each value's context is its store's name and its ID, as
 * [AtRestKeyContext] builds it, so [stores] are keyed by those names.
 *
 * It's safe to run on every instance at once and to run repeatedly: keys already in the current
 * format under the current key-encryption key are left alone, and each replacement only applies if
 * no other instance changed the key first. A key no known key-encryption key can read is reported
 * and never overwritten.
 */
class StoredKeyRewrap(
    private val stores: Map<String, EncryptedPrivateKeyStore>,
    private val cipher: AtRestKeyCipher,
) {
    suspend fun run(): Report {
        var rewrapped = 0
        var alreadyCurrent = 0
        val unreadable = mutableListOf<String>()
        for ((storeName, store) in stores) {
            for (key in store.encryptedPrivateKeys()) {
                val replacement =
                    try {
                        cipher.rewrap(key.privateKeyEncrypted, AtRestKeyContext.of(storeName, key.id))
                    } catch (_: GeneralSecurityException) {
                        unreadable += "$storeName/${key.id}"
                        continue
                    }
                when {
                    replacement == null -> alreadyCurrent++
                    store.replaceEncryptedPrivateKey(key.id, key.privateKeyEncrypted, replacement) -> rewrapped++
                    else -> alreadyCurrent++
                }
            }
        }
        return Report(rewrapped = rewrapped, alreadyCurrent = alreadyCurrent, unreadable = unreadable)
    }

    data class Report(
        val rewrapped: Int,
        val alreadyCurrent: Int,
        val unreadable: List<String>,
    )
}
