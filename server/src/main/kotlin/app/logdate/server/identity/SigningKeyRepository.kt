package app.logdate.server.identity

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class StoredSigningKey(
    val id: Uuid,
    val accountId: Uuid,
    val purpose: String = "atproto",
    val algorithm: String = "Ed25519",
    val publicKeyMultibase: String,
    val privateKeyEncrypted: String,
    val createdAt: Instant,
    val revokedAt: Instant? = null,
)

@OptIn(ExperimentalUuidApi::class)
interface SigningKeyRepository : EncryptedPrivateKeyStore {
    /** A missing originating epoch means zero; it cannot write after authority advances. */
    suspend fun save(
        signingKey: StoredSigningKey,
        identityEpoch: Long = 0,
    ): StoredSigningKey

    /** Atomically keeps the existing sole active key or stores this first candidate. */
    suspend fun createActiveKeyIfMissing(
        signingKey: StoredSigningKey,
        identityEpoch: Long = 0,
    ): StoredSigningKey

    suspend fun findActiveByAccountId(accountId: Uuid): StoredSigningKey?

    suspend fun revokeActiveKeys(
        accountId: Uuid,
        identityEpoch: Long = 0,
    ): Int

    /**
     * Revokes the account's active keys and stores [signingKey] as its only active key. The
     * PostgreSQL repository does both in one transaction, so a failure never leaves the account
     * without an active key.
     */
    suspend fun replaceActiveKey(
        signingKey: StoredSigningKey,
        identityEpoch: Long = 0,
    ): StoredSigningKey {
        revokeActiveKeys(signingKey.accountId, identityEpoch)
        return save(signingKey, identityEpoch)
    }
}

@OptIn(ExperimentalUuidApi::class)
class InMemorySigningKeyRepository : SigningKeyRepository {
    private val keysById = linkedMapOf<Uuid, StoredSigningKey>()

    override suspend fun save(
        signingKey: StoredSigningKey,
        identityEpoch: Long,
    ): StoredSigningKey =
        synchronized(keysById) {
            keysById[signingKey.id] = signingKey
            signingKey
        }

    override suspend fun createActiveKeyIfMissing(
        signingKey: StoredSigningKey,
        identityEpoch: Long,
    ): StoredSigningKey =
        synchronized(keysById) {
            require(signingKey.revokedAt == null)
            activeKey(signingKey.accountId) ?: signingKey.also { keysById[it.id] = it }
        }

    override suspend fun findActiveByAccountId(accountId: Uuid): StoredSigningKey? = synchronized(keysById) { activeKey(accountId) }

    private fun activeKey(accountId: Uuid): StoredSigningKey? {
        val active = keysById.values.filter { it.accountId == accountId && it.revokedAt == null }
        if (active.size > 1) throw IdentityLifecycleConflictException("Active signing keys need reconciliation")
        return active.singleOrNull()
    }

    override suspend fun revokeActiveKeys(
        accountId: Uuid,
        identityEpoch: Long,
    ): Int = synchronized(keysById) { revokeLocked(accountId) }

    private fun revokeLocked(accountId: Uuid): Int {
        val revokedAt = Clock.System.now()
        val active = keysById.values.filter { it.accountId == accountId && it.revokedAt == null }
        active.forEach { keysById[it.id] = it.copy(revokedAt = revokedAt) }
        return active.size
    }

    override suspend fun replaceActiveKey(
        signingKey: StoredSigningKey,
        identityEpoch: Long,
    ): StoredSigningKey =
        synchronized(keysById) {
            revokeLocked(signingKey.accountId)
            keysById[signingKey.id] = signingKey
            signingKey
        }

    override suspend fun encryptedPrivateKeys(): List<EncryptedPrivateKey> =
        synchronized(keysById) {
            keysById.values.filter { it.revokedAt == null }.map { EncryptedPrivateKey(it.id.toString(), it.privateKeyEncrypted) }
        }

    override suspend fun replaceEncryptedPrivateKey(
        id: String,
        expected: String,
        replacement: String,
    ): Boolean =
        synchronized(keysById) {
            val key = keysById[Uuid.parse(id)]?.takeIf { it.privateKeyEncrypted == expected } ?: return@synchronized false
            keysById[key.id] = key.copy(privateKeyEncrypted = replacement)
            true
        }
}
