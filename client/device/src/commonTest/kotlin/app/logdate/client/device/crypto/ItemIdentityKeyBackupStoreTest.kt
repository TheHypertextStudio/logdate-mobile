package app.logdate.client.device.crypto

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemIdentityKeyBackupStoreTest {
    private val items = FakeIdentityBackupItems()
    private val store = ItemIdentityKeyBackupStore(items)

    @Test
    fun `an account key and its media key survive a reinstall`() =
        runTest {
            store.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 1 }, ByteArray(32) { 2 }))

            val restored = ItemIdentityKeyBackupStore(items).readAccountIdentity()

            assertEquals("owner", restored?.accountId)
            assertContentEquals(ByteArray(32) { 1 }, restored?.key)
            assertContentEquals(ByteArray(32) { 2 }, restored?.mediaKey)
            assertTrue(store.hasRootBackup())
        }

    @Test
    fun `writing an account key removes the phrase and writing a phrase removes the account key`() =
        runTest {
            store.writePhrase("alpha beta")
            store.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 1 }))

            assertNull(store.readPhrase())
            assertEquals("owner", store.readAccountIdentity()?.accountId)

            store.writePhrase("gamma delta")

            assertNull(store.readAccountIdentity())
            assertEquals("gamma delta", store.readPhrase())
        }

    @Test
    fun `a phrase saved by the earlier phrase-only store still restores`() =
        runTest {
            items.values["identity_recovery_phrase_backup_v1"] = "alpha beta".encodeToByteArray()

            assertTrue(store.hasRootBackup())
            assertEquals("alpha beta", store.readPhrase())
        }

    @Test
    fun `a damaged account key is reported and never treated as missing`() =
        runTest {
            store.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 1 }))
            val record = items.values.getValue("identity_account_key_backup_v1")
            items.values["identity_account_key_backup_v1"] = record.decodeToString().replace("owner", "other").encodeToByteArray()

            assertTrue(store.hasRootBackup())
            assertFailsWith<IllegalStateException> { store.readAccountIdentity() }
        }

    @Test
    fun `media keys are kept once each and a conflicting key is refused`() =
        runTest {
            val binding = Base64.encode(ByteArray(32) { 7 })
            store.writeMediaIdentity(MediaIdentityBackup(binding, ByteArray(32) { 3 }))
            store.writeMediaIdentity(MediaIdentityBackup(binding, ByteArray(32) { 3 }))

            assertTrue(store.hasMediaBackup())
            assertContentEquals(ByteArray(32) { 3 }, store.readMediaIdentity(binding)?.key)
            assertFailsWith<IllegalStateException> { store.writeMediaIdentity(MediaIdentityBackup(binding, ByteArray(32) { 4 })) }
            assertContentEquals(ByteArray(32) { 3 }, store.readMediaIdentity(binding)?.key)
        }

    @Test
    fun `clearing removes every record`() =
        runTest {
            store.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 1 }))
            store.writeMediaIdentity(MediaIdentityBackup(Base64.encode(ByteArray(32) { 7 }), ByteArray(32) { 3 }))

            store.clear()

            assertTrue(items.values.isEmpty())
            assertFalse(store.hasBackup())
        }

    @Test
    fun `a store that can't be read fails instead of looking empty`() =
        runTest {
            items.failReads = true

            assertFailsWith<IllegalStateException> { store.hasRootBackup() }
            assertFailsWith<IllegalStateException> { store.readAccountIdentity() }
        }
}

private class FakeIdentityBackupItems : IdentityBackupItems {
    val values = mutableMapOf<String, ByteArray>()
    var failReads = false

    override suspend fun read(name: String): ByteArray? {
        check(!failReads) { "The identity backup could not be read" }
        return values[name]
    }

    override suspend fun write(
        name: String,
        value: ByteArray,
    ) {
        values[name] = value
    }

    override suspend fun delete(name: String) {
        values.remove(name)
    }
}
