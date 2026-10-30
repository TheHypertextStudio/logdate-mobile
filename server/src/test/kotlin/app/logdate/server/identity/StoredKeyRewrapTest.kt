package app.logdate.server.identity

import app.logdate.server.oauth.InMemoryOAuthSigningKeyRepository
import app.logdate.server.oauth.StoredOAuthSigningKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StoredKeyRewrapTest {
    private val oldCipher = AtRestKeyCipher(OLD_KEK)
    private val newCipher = AtRestKeyCipher(NEW_KEK)
    private val rewrapCipher = AtRestKeyCipher(NEW_KEK, decryptOnlySeeds = listOf(OLD_KEK))

    @Test
    fun `re-encrypts signing keys and the OAuth key under the new key-encryption key`() =
        runTest {
            val signingKeys = InMemorySigningKeyRepository()
            val signingKey = signingKeys.save(signingKey { oldCipher.encrypt(SIGNING_KEY, it) })
            val oauthKeys = oauthRepository(oldCipher.encrypt(OAUTH_KEY, OAUTH_CONTEXT))

            val report = rewrap(signingKeys, oauthKeys).run()

            assertEquals(StoredKeyRewrap.Report(rewrapped = 2, alreadyCurrent = 0, unreadable = emptyList()), report)
            assertContentEquals(
                SIGNING_KEY,
                newCipher.decrypt(signingKeys.encryptedValueOf(signingKey.id.toString()), AtRestKeyContext.signingKey(signingKey.id)),
            )
            assertContentEquals(OAUTH_KEY, newCipher.decrypt(oauthKeys.encryptedValueOf(OAUTH_KEY_ID), OAUTH_CONTEXT))
        }

    @Test
    fun `keys in the earlier format are upgraded without a decrypt-only key`() =
        runTest {
            val signingKeys = InMemorySigningKeyRepository()
            val stored = signingKeys.save(signingKey { LegacyAtRestFormat.encrypt(NEW_KEK, SIGNING_KEY) })
            val oauthKeys = oauthRepository(LegacyAtRestFormat.encrypt(NEW_KEK, OAUTH_KEY))

            val report = StoredKeyRewrap(mapOf("signing_keys" to signingKeys, "oauth_signing_keys" to oauthKeys), newCipher).run()

            assertEquals(StoredKeyRewrap.Report(rewrapped = 2, alreadyCurrent = 0, unreadable = emptyList()), report)
            val upgraded = signingKeys.encryptedValueOf(stored.id.toString())
            assertTrue(upgraded.startsWith("v2:"))
            assertContentEquals(SIGNING_KEY, newCipher.decrypt(upgraded, AtRestKeyContext.signingKey(stored.id)))
            assertContentEquals(OAUTH_KEY, newCipher.decrypt(oauthKeys.encryptedValueOf(OAUTH_KEY_ID), OAUTH_CONTEXT))
        }

    @Test
    fun `running again changes nothing`() =
        runTest {
            val signingKeys = InMemorySigningKeyRepository().apply { save(signingKey { oldCipher.encrypt(SIGNING_KEY, it) }) }
            val oauthKeys = oauthRepository(oldCipher.encrypt(OAUTH_KEY, OAUTH_CONTEXT))
            rewrap(signingKeys, oauthKeys).run()
            val afterFirstRun = signingKeys.encryptedPrivateKeys() + oauthKeys.encryptedPrivateKeys()

            val report = rewrap(signingKeys, oauthKeys).run()

            assertEquals(StoredKeyRewrap.Report(rewrapped = 0, alreadyCurrent = 2, unreadable = emptyList()), report)
            assertEquals(afterFirstRun, signingKeys.encryptedPrivateKeys() + oauthKeys.encryptedPrivateKeys())
        }

    @Test
    fun `keys no known key-encryption key can read are reported and left unchanged`() =
        runTest {
            val signingKeys = InMemorySigningKeyRepository()
            val stored = signingKeys.save(signingKey { AtRestKeyCipher(OTHER_KEK).encrypt(SIGNING_KEY, it) })

            val report = rewrap(signingKeys, InMemoryOAuthSigningKeyRepository()).run()

            assertEquals(listOf("signing_keys/${stored.id}"), report.unreadable)
            assertEquals(0, report.rewrapped)
            assertEquals(stored.privateKeyEncrypted, signingKeys.encryptedValueOf(stored.id.toString()))
        }

    @Test
    fun `revoked signing keys are neither re-encrypted nor reported`() =
        runTest {
            val signingKeys = InMemorySigningKeyRepository()
            signingKeys.save(signingKey { AtRestKeyCipher(OTHER_KEK).encrypt(SIGNING_KEY, it) }.copy(revokedAt = Clock.System.now()))
            val active = signingKeys.save(signingKey { oldCipher.encrypt(SIGNING_KEY, it) })

            val report = rewrap(signingKeys, InMemoryOAuthSigningKeyRepository()).run()

            assertEquals(StoredKeyRewrap.Report(rewrapped = 1, alreadyCurrent = 0, unreadable = emptyList()), report)
            assertEquals(listOf(active.id.toString()), signingKeys.encryptedPrivateKeys().map { it.id })
        }

    @Test
    fun `a key another instance already replaced isn't overwritten`() =
        runTest {
            val signingKeys = InMemorySigningKeyRepository()
            val stored = signingKeys.save(signingKey { oldCipher.encrypt(SIGNING_KEY, it) })
            val replacedElsewhere = newCipher.encrypt(SIGNING_KEY, AtRestKeyContext.signingKey(stored.id))
            val racingStore =
                object : EncryptedPrivateKeyStore by signingKeys {
                    override suspend fun encryptedPrivateKeys(): List<EncryptedPrivateKey> {
                        val snapshot = signingKeys.encryptedPrivateKeys()
                        signingKeys.replaceEncryptedPrivateKey(stored.id.toString(), stored.privateKeyEncrypted, replacedElsewhere)
                        return snapshot
                    }
                }

            val report = StoredKeyRewrap(mapOf("signing_keys" to racingStore), rewrapCipher).run()

            assertEquals(StoredKeyRewrap.Report(rewrapped = 0, alreadyCurrent = 1, unreadable = emptyList()), report)
            assertEquals(replacedElsewhere, signingKeys.encryptedValueOf(stored.id.toString()))
        }

    private fun rewrap(
        signingKeys: SigningKeyRepository,
        oauthKeys: InMemoryOAuthSigningKeyRepository,
    ) = StoredKeyRewrap(mapOf("signing_keys" to signingKeys, "oauth_signing_keys" to oauthKeys), rewrapCipher)

    private fun oauthRepository(privateKeyEncrypted: String) =
        InMemoryOAuthSigningKeyRepository().apply {
            getOrCreate {
                StoredOAuthSigningKey(
                    keyId = OAUTH_KEY_ID,
                    privateKeyEncrypted = privateKeyEncrypted,
                    publicKeySpki = "spki",
                    createdAt = Clock.System.now(),
                )
            }
        }

    /** A signing key whose private key [encrypt] wraps under the context of its own row. */
    private fun signingKey(encrypt: (context: String) -> String): StoredSigningKey {
        val id = Uuid.random()
        return StoredSigningKey(
            id = id,
            accountId = Uuid.random(),
            publicKeyMultibase = "zPublicKey",
            privateKeyEncrypted = encrypt(AtRestKeyContext.signingKey(id)),
            createdAt = Clock.System.now(),
        )
    }

    private suspend fun EncryptedPrivateKeyStore.encryptedValueOf(id: String): String =
        encryptedPrivateKeys().single { it.id == id }.privateKeyEncrypted

    private companion object {
        const val OLD_KEK = "old-key-encryption-key-0123456789abcdef"
        const val NEW_KEK = "new-key-encryption-key-0123456789abcdef"
        const val OTHER_KEK = "unrelated-key-encryption-key-0123456789"
        const val OAUTH_KEY_ID = "oauth-kid"
        val OAUTH_CONTEXT = AtRestKeyContext.oauthSigningKey(OAUTH_KEY_ID)
        val SIGNING_KEY = "signing key".encodeToByteArray()
        val OAUTH_KEY = "oauth key".encodeToByteArray()
    }
}
