package app.logdate.client.device.crypto

import app.logdate.client.device.storage.putBytes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AccountBoundIdentityKeyReadTest {
    @Test
    fun `binding never writes a placeholder account owner when the root is missing`() =
        runTest {
            for (storedOwner in listOf(null, OWNER)) {
                val storage = InMemorySecureStorage()
                storedOwner?.let { storage.putString("identity_account_id_v1", it) }
                val before = storage.observeAll().first()
                val identity = IdentityKeyManager(storage, FakeCryptoManager())
                assertFalse(identity.bindToAccount(OWNER))
                assertEquals(before, storage.observeAll().first())
                assertFalse(identity.hasIdentityKey())
            }
        }

    @Test
    fun `an existing unbound or different account key cannot encrypt account space records`() =
        runTest {
            for (storedOwner in listOf(null, OTHER)) {
                val storage = InMemorySecureStorage()
                storage.putBytes("identity_key_v1", ROOT)
                storedOwner?.let { storage.putString("identity_account_id_v1", it) }
                val before = storage.observeAll().first()
                val identity = IdentityKeyManager(storage, FakeCryptoManager())
                assertFailsWith<IllegalStateException> { identity.getAccountIdentityKey(OWNER) }
                assertEquals(before, storage.observeAll().first(), "A refused read must preserve offline keys and ownership")
            }
        }

    @Test
    fun `an exact account key is read without writes or requiring new recovery material`() =
        runTest {
            val storage = InMemorySecureStorage()
            storage.putString("identity_account_id_v1", OWNER.uppercase())
            storage.putBytes("identity_key_v1", ROOT)
            val before = storage.observeAll().first()
            val identity = IdentityKeyManager(storage, FakeCryptoManager(), expectedAccountOwner = { OWNER })
            assertContentEquals(ROOT, identity.getAccountIdentityKey(OWNER))
            assertEquals(before, storage.observeAll().first())
        }

    @Test
    fun `a bound account without a key cannot mint one during a space read`() =
        runTest {
            val storage = InMemorySecureStorage()
            storage.putString("identity_account_id_v1", OWNER)
            val before = storage.observeAll().first()
            val identity = IdentityKeyManager(storage, FakeCryptoManager())
            assertFailsWith<IdentityKeyNotFoundException> { identity.getAccountIdentityKey(OWNER) }
            assertEquals(before, storage.observeAll().first())
        }

    @Test
    fun `a retained journal owner mismatch blocks an otherwise account bound key`() =
        runTest {
            val storage = InMemorySecureStorage()
            storage.putString("identity_account_id_v1", OWNER)
            storage.putBytes("identity_key_v1", ROOT)
            val before = storage.observeAll().first()
            val identity = IdentityKeyManager(storage, FakeCryptoManager(), expectedAccountOwner = { OTHER })
            assertFailsWith<IllegalStateException> { identity.getAccountIdentityKey(OWNER) }
            assertEquals(before, storage.observeAll().first())
        }

    private companion object {
        const val OWNER = "745d7803-013a-4bea-9605-99ac3b27dbe7"
        const val OTHER = "a8a3400a-9f3c-4fca-9a7a-7c8cbe5ca24e"
        val ROOT = ByteArray(32) { 7 }
    }
}
