package app.logdate.client.device.crypto

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockStoreIdentityKeyBackupStoreTest {
    private val bytes = FakeBlockStoreBytes()
    private val store = BlockStoreIdentityKeyBackupStore(bytes)

    @Test
    fun `a phrase round-trips`() =
        runTest {
            store.writePhrase("alpha beta gamma")

            assertEquals("alpha beta gamma", BlockStoreIdentityKeyBackupStore(bytes).readPhrase())
            assertNull(store.readAccountIdentity())
            assertTrue(store.hasRootBackup())
        }

    @Test
    fun `an account key with its media key round-trips and replaces the phrase`() =
        runTest {
            store.writePhrase("alpha beta gamma")
            store.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 1 }, ByteArray(32) { 2 }))

            val restored = store.readAccountIdentity()
            assertEquals("owner", restored?.accountId)
            assertContentEquals(ByteArray(32) { 1 }, restored?.key)
            assertContentEquals(ByteArray(32) { 2 }, restored?.mediaKey)
            assertNull(store.readPhrase())
        }

    @Test
    fun `the key goes to the cloud only when it can be encrypted end to end`() =
        runTest {
            bytes.endToEnd = false
            store.writePhrase("alpha beta gamma")
            assertFalse(bytes.backedUp)

            bytes.endToEnd = true
            store.writePhrase("alpha beta gamma")
            assertTrue(bytes.backedUp)
        }

    @Test
    fun `a 24-word phrase fits one Block Store entry`() =
        runTest {
            store.writePhrase(List(24) { "abandonment" }.joinToString(" "))

            assertTrue(checkNotNull(bytes.stored).size <= 4096)
        }

    @Test
    fun `a tampered entry is reported as unreadable rather than returned`() =
        runTest {
            store.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 1 }))
            bytes.stored = bytes.stored!!.decodeToString().replace("owner", "other").encodeToByteArray()

            assertFailsWith<IllegalStateException> { store.readAccountIdentity() }
            assertTrue(store.hasRootBackup())
        }

    @Test
    fun `clearing deletes the entry`() =
        runTest {
            store.writePhrase("alpha beta gamma")

            store.clear()

            assertFalse(store.hasBackup())
            assertNull(store.readPhrase())
        }

    @Test
    fun `status is device only without a screen lock`() =
        runTest {
            bytes.endToEnd = false

            assertEquals(
                KeyBackupStatus.DeviceOnly(DeviceOnlyReason.NO_SCREEN_LOCK),
                AndroidKeyBackupStatusSource(bytes) { true }.observe().first(),
            )
        }

    @Test
    fun `status names Block Store only while the mirror is on`() =
        runTest {
            bytes.endToEnd = true

            assertEquals(
                KeyBackupStatus.PlatformBackup(setOf(KeyBackupTransport.GOOGLE_AUTO_BACKUP, KeyBackupTransport.GOOGLE_BLOCK_STORE)),
                AndroidKeyBackupStatusSource(bytes) { true }.observe().first(),
            )
            assertEquals(
                KeyBackupStatus.PlatformBackup(setOf(KeyBackupTransport.GOOGLE_AUTO_BACKUP)),
                AndroidKeyBackupStatusSource(bytes) { false }.observe().first(),
            )
        }

    @Test
    fun `status is unsupported when Play services can't answer`() =
        runTest {
            bytes.failing = true

            assertEquals(
                KeyBackupStatus.DeviceOnly(DeviceOnlyReason.UNSUPPORTED),
                AndroidKeyBackupStatusSource(bytes) { true }.observe().first(),
            )
        }
}

private class FakeBlockStoreBytes : BlockStoreBytes {
    var stored: ByteArray? = null
    var backedUp = false
    var endToEnd = true
    var failing = false

    override suspend fun read(key: String): ByteArray? = stored

    override suspend fun write(
        key: String,
        bytes: ByteArray,
        backUpToCloud: Boolean,
    ) {
        stored = bytes
        backedUp = backUpToCloud
    }

    override suspend fun delete(key: String) {
        stored = null
    }

    override suspend fun isEndToEndEncryptionAvailable(): Boolean {
        check(!failing) { "Play services unavailable" }
        return endToEnd
    }
}
