package app.logdate.client.data.account

import app.logdate.client.device.crypto.AccountIdentityBackup
import app.logdate.client.device.crypto.AccountKeyEnvelopeCipher
import app.logdate.client.device.crypto.DesktopCryptoManager
import app.logdate.client.device.crypto.IdentityKeyBackupStore
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.KeyDerivation
import app.logdate.client.device.crypto.NoOpIdentityKeyBackupStore
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.networking.AccountKeyEnvelopeApi
import app.logdate.client.sync.crypto.MediaPayloadKeyProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AccountKeyUnlockCoordinatorTest {
    @Test
    fun `account unlock preserves a conflicting root delivered by native restore`() =
        runTest {
            val owner = Uuid.random().toString()
            val retained = AccountIdentityBackup(owner, ByteArray(32) { 7 })
            var portable = retained
            val backup =
                object : IdentityKeyBackupStore by NoOpIdentityKeyBackupStore {
                    override suspend fun readAccountIdentity() = portable

                    override suspend fun writeAccountIdentity(identity: AccountIdentityBackup) {
                        portable = identity
                    }
                }
            val local = keys(backup)
            val secret = ByteArray(32)
            val sealed =
                AccountKeyEnvelopeCipher(DesktopCryptoManager())
                    .seal(owner, "AQ", secret, ByteArray(32) { 8 }, ByteArray(32) { 9 })
            val remote = EnvelopeRemote().apply { ciphertext = Base64.encode(sealed) }
            assertFailsWith<IllegalStateException> {
                coordinator(remote, local).unlockOrProvision("https://server/api/v1", owner, "access", "AQ", secret, false)
            }
            assertContentEquals(retained.key, portable.key)
            assertFalse(local.identity.hasIdentityKey())
        }

    @Test
    fun `native passkey sign-in provisions an envelope from the existing Android identity`() =
        runTest {
            val fixtures = DefaultPasskeyAccountRepositoryTest()
            val auth = fixtures.FakePasskeyApiClient()
            val owner =
                auth.completeAuthenticationResponse
                    .getOrThrow()
                    .account.id
                    .toString()
            for (retainedOwner in listOf(owner, owner.uppercase())) {
                val local = keys()
                local.identity.installAccountKey(retainedOwner, ByteArray(32) { 5 })
                val remote = EnvelopeRemote()
                val passkey =
                    DefaultPasskeyAccountRepositoryTest.FakePasskeyManager().apply {
                        val first = Base64.UrlSafe.encode(ByteArray(32) { 9 }).trimEnd('=')
                        authenticateWithPasskeyResponse =
                            Result.success(
                                """
                                {"id":"AQ","rawId":"AQ","type":"public-key",
                                 "response":{"clientDataJSON":"e30","authenticatorData":"AQ","signature":"Ag","userHandle":""},
                                 "clientExtensionResults":{"prf":{"results":{"first":"$first"}}}}
                                """.trimIndent(),
                            )
                    }
                val canonical =
                    object : app.logdate.client.device.identity.CanonicalOwnerProvider {
                        override suspend fun getCanonicalOwnerId(): String = retainedOwner

                        override suspend fun hasBoundOwner(): Boolean = true
                    }
                val repository =
                    DefaultPasskeyAccountRepository(
                        apiClient = auth,
                        passkeyManager = passkey,
                        restoreCredentialManager = DefaultPasskeyAccountRepositoryTest.FakeRestoreCredentialManager(),
                        sessionStorage = DefaultPasskeyAccountRepositoryTest.FakeSessionStorage(),
                        platformAccountManager = DefaultPasskeyAccountRepositoryTest.FakePlatformAccountManager(),
                        configRepository = DefaultPasskeyAccountRepositoryTest.FakeConfigRepository(),
                        canonicalOwnerProvider = canonical,
                        hasLocalData = { true },
                        repositoryScope = backgroundScope,
                        accountKeyUnlockCoordinator = coordinator(remote, local),
                    )
                repository.authenticateWithPasskey("testuser").getOrThrow()
                assertEquals(1, remote.writes)
                assertNotNull(remote.ciphertext)
                assertContentEquals(ByteArray(32) { 5 }, local.identity.getIdentityKey())
                assertEquals(retainedOwner, local.storage.getString("identity_account_id_v1"))
            }
        }

    @Test
    fun `existing device publishes opaque keys and another device opens them`() =
        runTest {
            val owner = Uuid.random().toString()
            val key = ByteArray(32) { it.toByte() }
            val mediaKey = ByteArray(32) { (it + 32).toByte() }
            val secret = ByteArray(32) { (it + 64).toByte() }
            val remote = EnvelopeRemote()
            val phone = keys()
            phone.identity.installAccountKey(owner, key)
            phone.media.installAccountKey(mediaKey)
            assertEquals(
                AccountKeyUnlockStatus.UNLOCKED,
                coordinator(remote, phone)
                    .unlockOrProvision("https://server/api/v1", owner, "access", "AQ", secret, true),
            )
            assertNotNull(remote.ciphertext)
            assertFalse(
                Base64
                    .decode(remote.ciphertext!!)
                    .toList()
                    .windowed(32)
                    .contains(key.toList()),
            )
            val newDevice = keys()
            assertEquals(
                AccountKeyUnlockStatus.UNLOCKED,
                coordinator(remote, newDevice)
                    .unlockOrProvision("https://server/api/v1", owner, "access", "AQ", secret, false),
            )
            assertContentEquals(key, newDevice.identity.getIdentityKey())
            assertContentEquals(mediaKey, newDevice.media.getOrCreateKey())
            assertEquals(1, remote.writes)
        }

    @Test
    fun `lost upload response is reconciled without replacing the envelope`() =
        runTest {
            val owner = Uuid.random().toString()
            val phone = keys()
            phone.identity.installAccountKey(owner, ByteArray(32) { 5 })
            val remote = EnvelopeRemote(dropResponse = true)
            val service = coordinator(remote, phone)
            assertEquals(
                AccountKeyUnlockStatus.UNLOCKED,
                service.unlockOrProvision(
                    "https://server/api/v1",
                    owner,
                    "access",
                    "AQ",
                    ByteArray(
                        32,
                    ) {
                        9
                    },
                    true,
                ),
            )
            val ciphertext = remote.ciphertext
            assertEquals(
                AccountKeyUnlockStatus.UNLOCKED,
                service.unlockOrProvision(
                    "https://server/api/v1",
                    owner,
                    "access",
                    "AQ",
                    ByteArray(
                        32,
                    ) {
                        9
                    },
                    true,
                ),
            )
            assertEquals(ciphertext, remote.ciphertext)
            assertEquals(1, remote.writes)
        }

    @Test
    fun `adopted account and missing local identity never mint or publish keys`() =
        runTest {
            val owner = Uuid.random().toString()
            val remote = EnvelopeRemote()
            val local = keys()
            val service = coordinator(remote, local)
            assertEquals(
                AccountKeyUnlockStatus.APPROVAL_REQUIRED,
                service.unlockOrProvision("https://server/api/v1", owner, "access", "AQ", ByteArray(32), true),
            )
            assertFalse(local.identity.hasIdentityKey())
            local.identity.installAccountKey(owner, ByteArray(32) { 7 })
            assertEquals(
                AccountKeyUnlockStatus.APPROVAL_REQUIRED,
                service.unlockOrProvision("https://server/api/v1", owner, "access", "AQ", ByteArray(32), false),
            )
            assertEquals(0, remote.writes)
        }

    @Test
    fun `conflicting legacy media key is rejected before installing an identity`() =
        runTest {
            val owner = Uuid.random().toString()
            val local = keys()
            local.media.installAccountKey(ByteArray(32) { 1 })
            val sealed =
                AccountKeyEnvelopeCipher(
                    DesktopCryptoManager(),
                ).seal(owner, "AQ", ByteArray(32), ByteArray(32) { 2 }, ByteArray(32) { 3 })
            val remote = EnvelopeRemote().apply { ciphertext = Base64.encode(sealed) }
            assertFailsWith<IllegalArgumentException> {
                coordinator(remote, local).unlockOrProvision("https://server/api/v1", owner, "access", "AQ", ByteArray(32), false)
            }
            assertFalse(local.identity.hasIdentityKey())
            assertContentEquals(ByteArray(32) { 1 }, local.media.getOrCreateKey())
        }

    @Test
    fun `local key readiness requires an exact existing account binding and creates nothing`() =
        runTest {
            val owner = Uuid.random().toString()
            val local = keys()
            val remote = EnvelopeRemote()
            val service = coordinator(remote, local)
            assertFalse(service.hasLocalAccountKeys(owner))
            assertFalse(local.identity.hasIdentityKey())
            local.identity.installAccountKey(owner, ByteArray(32) { 3 }, ByteArray(32) { 4 })
            assertTrue(service.hasLocalAccountKeys(owner))
            assertFalse(service.hasLocalAccountKeys(Uuid.random().toString()))
            assertContentEquals(ByteArray(32) { 3 }, local.identity.getIdentityKey())
            assertEquals(0, remote.writes)
        }

    @Test
    fun `new account binding preserves its existing local root and never mints missing keys`() =
        runTest {
            val owner = Uuid.random().toString()
            val local = keys()
            val service = coordinator(EnvelopeRemote(), local)
            assertFalse(service.bindLocalKeysForNewAccount(owner))
            assertFalse(local.identity.hasIdentityKey())
            local.identity.ensureIdentityKey()
            val before = local.identity.getIdentityKey()
            assertTrue(service.bindLocalKeysForNewAccount(owner))
            assertContentEquals(before, local.identity.getIdentityKey())
            assertEquals(owner, local.storage.getString("identity_account_id_v1"))
            assertFalse(service.bindLocalKeysForNewAccount(Uuid.random().toString()))
            assertContentEquals(before, local.identity.getIdentityKey())
        }

    private fun keys(backup: IdentityKeyBackupStore = NoOpIdentityKeyBackupStore): LocalKeys {
        val storage = UnlockTestStorage()
        val crypto = DesktopCryptoManager()
        val identity = IdentityKeyManager(storage, crypto, backup)
        return LocalKeys(identity, MediaPayloadKeyProvider(storage, crypto, identity, KeyDerivation(crypto)), storage)
    }

    private fun coordinator(
        remote: EnvelopeRemote,
        local: LocalKeys,
    ) = AccountKeyUnlockCoordinator(remote, AccountKeyEnvelopeCipher(DesktopCryptoManager()), local.identity, local.media)

    private class LocalKeys(
        val identity: IdentityKeyManager,
        val media: MediaPayloadKeyProvider,
        val storage: SecureStorage,
    )

    private class EnvelopeRemote(
        private val dropResponse: Boolean = false,
    ) : AccountKeyEnvelopeApi {
        var ciphertext: String? = null
        var writes = 0

        override suspend fun isSupported(apiBaseUrl: String): Result<Boolean> = Result.success(true)

        override suspend fun fetch(
            apiBaseUrl: String,
            accessToken: String,
            credentialId: String,
        ): Result<String?> = Result.success(ciphertext)

        override suspend fun store(
            apiBaseUrl: String,
            accessToken: String,
            credentialId: String,
            ciphertext: String,
        ): Result<Unit> {
            writes++
            check(this.ciphertext == null || this.ciphertext == ciphertext)
            this.ciphertext = ciphertext
            return if (dropResponse) Result.failure(IllegalStateException("Connection lost")) else Result.success(Unit)
        }
    }
}

private class UnlockTestStorage : SecureStorage {
    private val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }

    override fun observeString(key: String): Flow<String?> = flowOf(values[key])

    override fun observeAll(): Flow<Map<String, String>> = flowOf(values.toMap())

    override suspend fun encrypt(data: ByteArray): ByteArray = data

    override suspend fun decrypt(data: ByteArray): ByteArray = data
}
