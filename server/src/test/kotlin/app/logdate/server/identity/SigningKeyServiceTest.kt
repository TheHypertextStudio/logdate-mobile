package app.logdate.server.identity

import studio.hypertext.atproto.crypto.Base58Btc
import java.security.PrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for [SigningKeyService], focusing on the secure management of account-level
 * signing keys.
 *
 * These tests cover the full lifecycle of cryptographic keys used for AT Protocol
 * identity operations, including:
 * - Deterministic and random key generation across supported elliptic curves (P-256, K-256).
 * - Key rotation and active key management to ensure continuity of identity.
 * - Secure export and import workflows using passphrase-based encryption (PBKDF2/AES).
 * - Two-stage key activation ("prepare" and "activate") for safe infrastructure migrations.
 */
@OptIn(ExperimentalUuidApi::class)
class SigningKeyServiceTest {
    @Test
    fun `ensureActiveKey reuses existing key until rotation`() =
        kotlinx.coroutines.test.runTest {
            val repository = InMemorySigningKeyRepository()
            val service = SigningKeyService(repository, "test-kek")
            val accountId = Uuid.random()

            val first = service.ensureActiveKey(accountId)
            val second = service.ensureActiveKey(accountId)
            val rotated = service.rotateKey(accountId)

            assertEquals(first.id, second.id)
            assertNotEquals(first.id, rotated.id)
            assertEquals(rotated.id, repository.findActiveByAccountId(accountId)?.id)
        }

    @Test
    fun `decryptPrivateKey returns EC private key`() =
        kotlinx.coroutines.test.runTest {
            val service = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val key = service.ensureActiveKey(Uuid.random())

            val decrypted: PrivateKey = service.decryptPrivateKey(key)

            assertTrue(decrypted.encoded.isNotEmpty())
            assertEquals("EC", decrypted.algorithm)
        }

    @Test
    fun `exported signing keys round trip through passphrase encryption`() =
        kotlinx.coroutines.test.runTest {
            val service = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val accountId = Uuid.random()
            val activeKey = service.ensureActiveKey(accountId)

            val exported = service.exportActiveKey(accountId = accountId, passphrase = "correct horse battery staple")
            val decrypted = service.decryptExportedKey(exported, passphrase = "correct horse battery staple")

            assertEquals(activeKey.publicKeyMultibase, exported.publicKeyMultibase)
            assertTrue(exported.publicKeyDidKey.startsWith("did:key:z"))
            assertTrue(exported.encryptedPrivateKey.isNotBlank())
            assertTrue(exported.salt.isNotBlank())
            assertTrue(exported.iv.isNotBlank())
            assertEquals("EC", decrypted.algorithm)
        }

    @Test
    fun `prepared and imported signing keys can be activated safely`() =
        kotlinx.coroutines.test.runTest {
            val repository = InMemorySigningKeyRepository()
            val service = SigningKeyService(repository, "test-kek")
            val accountId = Uuid.random()
            val first = service.ensureActiveKey(accountId)

            val prepared = service.prepareKey(accountId)
            val activated = service.activatePreparedKey(accountId, prepared)
            val exported = service.exportActiveKey(accountId, "import-secret")
            val imported = service.importActiveKey(accountId, exported, "import-secret")

            assertNotEquals(first.id, prepared.id)
            assertEquals(prepared.id, activated.id)
            assertEquals(exported.publicKeyMultibase, imported.publicKeyMultibase)
            assertEquals(imported.id, repository.findActiveByAccountId(accountId)?.id)
        }

    @Test
    fun `importActiveKey rejects mismatched public did key metadata`() =
        kotlinx.coroutines.test.runTest {
            val service = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val accountId = Uuid.random()
            val exported = service.exportActiveKey(service.ensureActiveKey(accountId).accountId, "secret")

            assertFailsWith<IllegalArgumentException> {
                service.importActiveKey(
                    accountId = accountId,
                    exportedKey = exported.copy(publicKeyDidKey = "did:key:zDifferent"),
                    passphrase = "secret",
                )
            }
        }

    @Test
    fun `base58 encoder handles empty and leading zero bytes`() {
        assertEquals("", Base58Btc.encode(byteArrayOf()))
        assertEquals("1", Base58Btc.encode(byteArrayOf(0)))
        assertEquals("112", Base58Btc.encode(byteArrayOf(0, 0, 1)))
    }

    @Test
    fun `seeded service generates and decrypts K-256 keys`() =
        kotlinx.coroutines.test.runTest {
            val service = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")

            val generated = service.generateKeyPair()
            val decrypted = service.decryptPrivateKey(service.ensureActiveKey(Uuid.random()))

            assertEquals("K-256", generated.algorithm)
            assertTrue(generated.publicKeyMultibase.startsWith("z"))
            assertEquals("EC", decrypted.algorithm)
        }

    @Test
    fun `service can generate both supported hosted signing key curves`() {
        val service = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")

        val p256 = service.generateKeyPair(studio.hypertext.atproto.crypto.EcCurve.P256)
        val k256 = service.generateKeyPair(studio.hypertext.atproto.crypto.EcCurve.K256)

        assertEquals("P-256", p256.algorithm)
        assertEquals("K-256", k256.algorithm)
        assertTrue(p256.publicKeyMultibase.startsWith("z"))
        assertTrue(k256.publicKeyMultibase.startsWith("z"))
    }

    @Test
    fun `a sealed prepared key reopens with its id and stays bound to its account`() =
        kotlinx.coroutines.test.runTest {
            val service = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val accountId = Uuid.random()
            val prepared = service.prepareKey(accountId)

            val token = service.sealPreparedKey(prepared, prevCid = "bafyprev", operationDigest = "digest")
            val opened = service.openPreparedKey(accountId, token)

            assertEquals(prepared.id, opened.key.id)
            assertEquals(prepared.privateKeyEncrypted, opened.key.privateKeyEncrypted)
            service.decryptPrivateKey(opened.key)
            assertFailsWith<IdentityLifecycleValidationException> { service.openPreparedKey(Uuid.random(), token) }
        }

    @Test
    fun `a stored private key is bound to its own row`() =
        kotlinx.coroutines.test.runTest {
            val service = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val key = service.ensureActiveKey(Uuid.random())

            assertTrue(key.privateKeyEncrypted.startsWith("v2:"))
            assertFailsWith<javax.crypto.AEADBadTagException> { service.decryptPrivateKey(key.copy(id = Uuid.random())) }
        }

    @Test
    fun `a private key stored in the earlier format is still read`() =
        kotlinx.coroutines.test.runTest {
            val service = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val current = service.ensureActiveKey(Uuid.random())
            val pkcs8 = service.decryptPrivateKey(current).encoded

            val legacy = current.copy(privateKeyEncrypted = LegacyAtRestFormat.encrypt("test-kek", pkcs8))

            assertTrue(service.decryptPrivateKey(legacy).encoded.contentEquals(pkcs8))
        }
}
