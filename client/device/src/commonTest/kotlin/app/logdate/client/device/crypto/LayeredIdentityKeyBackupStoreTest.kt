package app.logdate.client.device.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayeredIdentityKeyBackupStoreTest {
    private val primary = InMemoryIdentityKeyBackupStore()
    private val mirror = InMemoryIdentityKeyBackupStore()
    private var mirrorOn = true
    private val store = LayeredIdentityKeyBackupStore(primary, mirror) { mirrorOn }

    @Test
    fun `writes reach both copies`() =
        runTest {
            store.writePhrase("alpha beta")

            assertEquals("alpha beta", primary.readPhrase())
            assertEquals("alpha beta", mirror.readPhrase())
        }

    @Test
    fun `an account key replaces the phrase in both copies`() =
        runTest {
            store.writePhrase("alpha beta")
            store.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 1 }, ByteArray(32) { 2 }))

            assertNull(primary.readPhrase())
            assertNull(mirror.readPhrase())
            assertContentEquals(ByteArray(32) { 2 }, mirror.readAccountIdentity()?.mediaKey)
        }

    @Test
    fun `a missing primary is restored from the mirror and written back`() =
        runTest {
            mirror.writePhrase("alpha beta")

            assertTrue(store.hasRootBackup())
            assertEquals("alpha beta", store.readPhrase())
            assertEquals("alpha beta", primary.readPhrase())
        }

    @Test
    fun `the primary wins and a stale mirror is rewritten to match`() =
        runTest {
            primary.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 1 }))
            mirror.writePhrase("an older identity")

            assertEquals("owner", store.readAccountIdentity()?.accountId)
            assertNull(mirror.readPhrase())
            assertContentEquals(ByteArray(32) { 1 }, mirror.readAccountIdentity()?.key)
        }

    @Test
    fun `an existing primary backfills an empty mirror`() =
        runTest {
            primary.writePhrase("alpha beta")

            store.readPhrase()

            assertEquals("alpha beta", mirror.readPhrase())
        }

    @Test
    fun `an unreadable primary root is never replaced by the mirror`() =
        runTest {
            val unreadable =
                object : IdentityKeyBackupStore by primary {
                    override suspend fun hasRootBackup() = true

                    override suspend fun readPhrase(): String? = null

                    override suspend fun readAccountIdentity(): AccountIdentityBackup? = null
                }
            mirror.writePhrase("alpha beta")
            val layered = LayeredIdentityKeyBackupStore(unreadable, mirror)

            assertNull(layered.readPhrase())
            assertTrue(layered.hasRootBackup())
        }

    @Test
    fun `a corrupt mirror is rewritten from the primary`() =
        runTest {
            primary.writePhrase("alpha beta")
            var corrupt = true
            val damaged =
                object : IdentityKeyBackupStore by mirror {
                    override suspend fun readAccountIdentity(): AccountIdentityBackup? =
                        if (corrupt) error("The identity backup mirror could not be read") else mirror.readAccountIdentity()

                    override suspend fun writePhrase(phrase: String) {
                        mirror.writePhrase(phrase)
                        corrupt = false
                    }
                }
            val layered = LayeredIdentityKeyBackupStore(primary, damaged)

            assertEquals("alpha beta", layered.readPhrase())
            assertFalse(corrupt)
            assertEquals("alpha beta", mirror.readPhrase())
        }

    @Test
    fun `a failing mirror never blocks reads or writes`() =
        runTest {
            val broken =
                object : IdentityKeyBackupStore by mirror {
                    override suspend fun readPhrase(): String = error("Play services unavailable")

                    override suspend fun readAccountIdentity(): AccountIdentityBackup = error("Play services unavailable")

                    override suspend fun writePhrase(phrase: String) = error("Play services unavailable")
                }
            val layered = LayeredIdentityKeyBackupStore(primary, broken)

            layered.writePhrase("alpha beta")

            assertEquals("alpha beta", layered.readPhrase())
            assertTrue(layered.hasRootBackup())
        }

    @Test
    fun `clearing removes both copies so a reset can't bring the old key back`() =
        runTest {
            store.writePhrase("alpha beta")

            store.clear()

            assertFalse(store.hasBackup())
            assertNull(mirror.readPhrase())
        }

    @Test
    fun `a mirror that can't be cleared fails the reset before the primary is touched`() =
        runTest {
            val stuck =
                object : IdentityKeyBackupStore by mirror {
                    override suspend fun clear() = error("Play services unavailable")
                }
            val layered = LayeredIdentityKeyBackupStore(primary, stuck)
            layered.writePhrase("alpha beta")

            assertFailsWith<IllegalStateException> { layered.clear() }
            assertEquals("alpha beta", primary.readPhrase())
        }

    @Test
    fun `with the mirror off it is neither read nor written but is still cleared`() =
        runTest {
            mirror.writePhrase("from before")
            mirrorOn = false

            assertFalse(store.hasRootBackup())
            assertNull(store.readPhrase())
            store.writePhrase("alpha beta")
            assertEquals("from before", mirror.readPhrase())

            store.clear()
            assertNull(mirror.readPhrase())
        }

    @Test
    fun `media keys stay in the primary only`() =
        runTest {
            store.writeMediaIdentity(MediaIdentityBackup("binding", ByteArray(32) { 3 }))

            assertTrue(primary.hasMediaBackup())
            assertFalse(mirror.hasMediaBackup())
        }

    @Test
    fun `a new install restores its identity from the mirror instead of minting a new one`() =
        runTest {
            val crypto = FakeCryptoManager()
            val firstInstall = IdentityKeyManager(InMemorySecureStorage(), crypto, store)
            val phrase = firstInstall.setupNewIdentity()
            val original = firstInstall.getIdentityKey()

            // A reinstall before Auto Backup ran: the file and the Keystore are gone, Block Store isn't.
            primary.clear()
            val reinstall = IdentityKeyManager(InMemorySecureStorage(), crypto, store)
            reinstall.ensureIdentityKey()

            assertContentEquals(original, reinstall.getIdentityKey())
            assertEquals(phrase.toString(), primary.readPhrase())
        }
}
