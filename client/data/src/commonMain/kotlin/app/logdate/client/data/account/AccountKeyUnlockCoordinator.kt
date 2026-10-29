package app.logdate.client.data.account

import app.logdate.client.device.crypto.AccountKeyEnvelopeCipher
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.networking.AccountKeyEnvelopeApi
import app.logdate.client.sync.crypto.MediaPayloadKeyProvider
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

enum class AccountKeyUnlockStatus { UNLOCKED, APPROVAL_REQUIRED, UNAVAILABLE }

class AccountKeyUnlockCoordinator(
    private val api: AccountKeyEnvelopeApi,
    private val cipher: AccountKeyEnvelopeCipher,
    private val identity: IdentityKeyManager,
    private val media: MediaPayloadKeyProvider,
) : AccountKeyAccess {
    override suspend fun hasLocalAccountKeys(accountId: String): Boolean {
        val existing = media.existingAccountKeys(accountId) ?: return false
        existing.identityKey.fill(0)
        existing.mediaKey.fill(0)
        return true
    }

    override suspend fun bindLocalKeysForNewAccount(accountId: String): Boolean {
        if (!identity.hasIdentityKey() || !identity.canUseForAccount(accountId) || !identity.bindToAccount(accountId)) return false
        return hasLocalAccountKeys(accountId)
    }

    override suspend fun unlockOrProvision(
        apiBaseUrl: String,
        accountId: String,
        accessToken: String,
        credentialId: String,
        unlockSecret: ByteArray?,
        mayPublishLocalKeys: Boolean,
    ): AccountKeyUnlockStatus {
        if (unlockSecret == null) return AccountKeyUnlockStatus.APPROVAL_REQUIRED
        if (!api.isSupported(apiBaseUrl).getOrThrow()) return AccountKeyUnlockStatus.UNAVAILABLE
        require(identity.canUseForAccount(accountId)) { "Local keys belong to another account" }
        val remote = api.fetch(apiBaseUrl, accessToken, credentialId).getOrThrow()
        if (remote != null) {
            install(accountId, credentialId, unlockSecret, remote)
            return AccountKeyUnlockStatus.UNLOCKED
        }
        if (!mayPublishLocalKeys || !identity.hasIdentityKey()) return AccountKeyUnlockStatus.APPROVAL_REQUIRED
        require(identity.bindToAccount(accountId)) { "Local keys belong to another account" }
        val snapshot = media.getAccountKeys(accountId)
        val rootKey = snapshot.key
        val mediaKey = requireNotNull(snapshot.mediaKey)
        try {
            val sealed = Base64.encode(cipher.seal(accountId, credentialId, unlockSecret, rootKey, mediaKey))
            api.store(apiBaseUrl, accessToken, credentialId, sealed).getOrElse { failure ->
                if (failure is CancellationException) throw failure
                // A lost response or a concurrent identical-key writer is settled by decrypting the stored envelope.
                val accepted = api.fetch(apiBaseUrl, accessToken, credentialId).getOrThrow() ?: throw failure
                install(accountId, credentialId, unlockSecret, accepted)
            }
        } finally {
            rootKey.fill(0)
            mediaKey.fill(0)
        }
        return AccountKeyUnlockStatus.UNLOCKED
    }

    private suspend fun install(
        accountId: String,
        credentialId: String,
        secret: ByteArray,
        ciphertext: String,
    ) {
        val bytes = Base64.decode(ciphertext)
        require(Base64.encode(bytes) == ciphertext) { "Invalid encrypted envelope" }
        val keys = cipher.open(accountId, credentialId, secret, bytes)
        try {
            require(media.canInstallAccountKey(keys.media)) { "Local media key conflicts with account key" }
            identity.installAccountKey(accountId, keys.identity, keys.media)
        } finally {
            keys.identity.fill(0)
            keys.media.fill(0)
        }
    }
}
