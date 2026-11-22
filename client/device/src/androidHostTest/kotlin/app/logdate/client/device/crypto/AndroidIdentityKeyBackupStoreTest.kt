package app.logdate.client.device.crypto

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AndroidIdentityKeyBackupStore], which mirrors the identity recovery phrase into a
 * file that survives what [app.logdate.client.device.storage.SecureStorage] (KeyStore-bound)
 * cannot: a device-to-device transfer or a cloud restore onto a new phone.
 *
 * These tests run on the JVM via `androidHostTest` - no emulator, no Robolectric. [Context] is
 * mocked directly and pointed at a real temp directory, matching the pattern already used for
 * local unit tests elsewhere in this codebase (e.g. `AndroidNetworkSaverModeProviderTest`).
 */
class AndroidIdentityKeyBackupStoreTest {
    private val tempDir = createTempDirectory("identity-key-backup-test").toFile()
    private val context =
        mockk<Context>(relaxed = true).also {
            every { it.filesDir } returns tempDir
        }
    private val store = AndroidIdentityKeyBackupStore(context)

    @Test
    fun `media-only custody is distinct from unreadable root recovery material`() =
        runTest {
            val identity = MediaIdentityBackup(Base64.encode(ByteArray(32) { 2 }), ByteArray(32) { 8 })
            store.writeMediaIdentity(identity)
            assertTrue(store.hasBackup())
            assertEquals(false, store.hasRootBackup())
            val phrase = File(tempDir, "identity_recovery/phrase_backup")
            phrase.writeText("")
            assertNull(store.readPhrase())
            assertTrue(store.hasRootBackup())
        }

    @Test
    fun `concurrent backup agents retain every media identity binding`() =
        runTest {
            val identities =
                (0 until 32).map { index ->
                    MediaIdentityBackup(Base64.encode(ByteArray(32) { index.toByte() }), ByteArray(32) { (index + 64).toByte() })
                }

            coroutineScope {
                identities
                    .map { identity ->
                        async(Dispatchers.IO) { AndroidIdentityKeyBackupStore(context).writeMediaIdentity(identity) }
                    }.awaitAll()
            }

            val reopened = AndroidIdentityKeyBackupStore(context)
            identities.forEach { identity ->
                assertContentEquals(identity.key, reopened.readMediaIdentity(identity.identityBinding)?.key)
            }
        }

    @Test
    fun `paired account identity and independent media key survive one portable write`() =
        runTest {
            val root = ByteArray(32) { 7 }
            val media = ByteArray(32) { 8 }
            store.writeAccountIdentity(AccountIdentityBackup("fixture-account", root, media))
            val restored = AndroidIdentityKeyBackupStore(context).readAccountIdentity()
            assertContentEquals(root, restored?.key)
            assertContentEquals(media, restored?.mediaKey)
        }

    @Test
    fun `explicit identity recovery selects its own media key while retaining the earlier key`() =
        runTest {
            val first = MediaIdentityBackup(Base64.encode(ByteArray(32) { 6 }), ByteArray(32) { 8 })
            val second = MediaIdentityBackup(Base64.encode(ByteArray(32) { 7 }), ByteArray(32) { 9 })
            store.writeMediaIdentity(first)
            store.writeMediaIdentity(second)
            val reopened = AndroidIdentityKeyBackupStore(context)
            assertContentEquals(first.key, reopened.readMediaIdentity(first.identityBinding)?.key)
            assertContentEquals(second.key, reopened.readMediaIdentity(second.identityBinding)?.key)
        }

    @Test
    fun `a different key for the same identity cannot replace retained media material`() =
        runTest {
            val first = MediaIdentityBackup(Base64.encode(ByteArray(32) { 6 }), ByteArray(32) { 8 })
            store.writeMediaIdentity(first)
            assertFailsWith<IllegalStateException> {
                store.writeMediaIdentity(MediaIdentityBackup(first.identityBinding, ByteArray(32) { 9 }))
            }
            assertContentEquals(first.key, store.readMediaIdentity(first.identityBinding)?.key)
        }

    @Test
    fun `independent media key survives native backup store reopening`() =
        runTest {
            val identity = MediaIdentityBackup(Base64.encode(ByteArray(32) { 6 }), ByteArray(32) { 8 })
            store.writeMediaIdentity(identity)
            val restored = AndroidIdentityKeyBackupStore(context).readMediaIdentity(identity.identityBinding)
            assertEquals(identity.identityBinding, restored?.identityBinding)
            assertContentEquals(identity.key, restored?.key)
        }

    @Test
    fun `corrupted media recovery key is retained and refused`() =
        runTest {
            val file =
                File(tempDir, "identity_recovery/media_key_backup").apply {
                    parentFile?.mkdirs()
                    writeText("truncated")
                }
            assertFailsWith<IllegalStateException> { store.readMediaIdentity("binding") }
            assertEquals("truncated", file.readText())
        }

    @Test
    fun `phrase-less account identity survives a new backup store instance`() =
        runTest {
            val key = ByteArray(32) { 7 }
            store.writeAccountIdentity(AccountIdentityBackup("fixture-account", key))
            val restored = AndroidIdentityKeyBackupStore(context).readAccountIdentity()
            assertEquals("fixture-account", restored?.accountId)
            assertContentEquals(key, restored?.key)
        }

    @Test
    fun `valid length corrupted account key fails integrity validation and preserves the file`() =
        runTest {
            store.writeAccountIdentity(AccountIdentityBackup("fixture-account", ByteArray(32) { 7 }))
            val file = File(tempDir, "identity_recovery/account_key_backup")
            val corrupted = file.readText().replace(Base64.encode(ByteArray(32) { 7 }), Base64.encode(ByteArray(32) { 8 }))
            file.writeText(corrupted)

            assertFailsWith<IllegalStateException> { store.readAccountIdentity() }

            assertEquals(corrupted, file.readText())
        }

    @Test
    fun `valid length corrupted paired media key fails integrity validation and preserves the file`() =
        runTest {
            val mediaKey = ByteArray(32) { 8 }
            store.writeAccountIdentity(AccountIdentityBackup("fixture-account", ByteArray(32) { 7 }, mediaKey))
            val file = File(tempDir, "identity_recovery/account_key_backup")
            val corrupted = file.readText().replace(Base64.encode(mediaKey), Base64.encode(ByteArray(32) { 9 }))
            assertTrue(corrupted != file.readText())
            file.writeText(corrupted)

            assertFailsWith<IllegalStateException> { store.readAccountIdentity() }

            assertEquals(corrupted, file.readText())
        }

    @Test
    fun `valid length corrupted bound media key fails integrity validation and preserves the file`() =
        runTest {
            val identity = MediaIdentityBackup(Base64.encode(ByteArray(32) { 6 }), ByteArray(32) { 8 })
            store.writeMediaIdentity(identity)
            val file = File(tempDir, "identity_recovery/media_key_backup")
            val corrupted = file.readText().replace(Base64.encode(identity.key), Base64.encode(ByteArray(32) { 9 }))
            assertTrue(corrupted != file.readText())
            file.writeText(corrupted)

            assertFailsWith<IllegalStateException> { store.readMediaIdentity(identity.identityBinding) }

            assertEquals(corrupted, file.readText())
        }

    @Test
    fun `interrupted account backup replacement preserves the prior recovery key`() =
        runTest {
            val original = ByteArray(32) { 7 }
            store.writeAccountIdentity(AccountIdentityBackup("fixture-account", original))
            File(tempDir, "identity_recovery/account_key_backup.part").mkdir()

            assertFailsWith<java.io.IOException> {
                store.writeAccountIdentity(AccountIdentityBackup("fixture-account", ByteArray(32) { 8 }))
            }

            assertContentEquals(original, store.readAccountIdentity()?.key)
            assertTrue(File(tempDir, "identity_recovery/account_key_backup").isFile)
        }

    @Test
    fun `unreadable phrase backup remains present and is never mistaken for an empty recovery store`() =
        runTest {
            val phraseFile =
                File(tempDir, "identity_recovery/phrase_backup").apply {
                    parentFile?.mkdirs()
                    mkdir()
                }

            assertNull(store.readPhrase())
            assertTrue(store.hasBackup())
            assertTrue(phraseFile.isDirectory)
        }

    @Test
    fun `changing back to a phrase removes the obsolete account key`() =
        runTest {
            store.writeAccountIdentity(AccountIdentityBackup("fixture-account", ByteArray(32) { 7 }))
            store.writePhrase("replacement phrase")
            assertNull(store.readAccountIdentity())
            assertEquals("replacement phrase", store.readPhrase())
        }

    @Test
    fun `unrecognized account backup versions fail without deleting the recovery file`() =
        runTest {
            val file =
                File(tempDir, "identity_recovery/account_key_backup").apply {
                    parentFile?.mkdirs()
                    writeText("""{"version":99,"accountId":"fixture","key":"invalid"}""")
                }
            assertFailsWith<IllegalStateException> { store.readAccountIdentity() }
            assertTrue(file.exists())
            assertTrue(store.hasBackup())
        }

    @Test
    fun `malformed account recovery material cannot escape in exception diagnostics`() =
        runTest {
            val secret = Base64.encode(ByteArray(32) { 71 })
            val file =
                File(tempDir, "identity_recovery/account_key_backup").apply {
                    parentFile?.mkdirs()
                    writeText("""{"version":2,"accountId":"synthetic","key":"$secret","checksum":"""")
                }
            val original = file.readBytes()
            val failure = assertFailsWith<IllegalStateException> { store.readAccountIdentity() }
            assertTrue(secret !in failure.stackTraceToString())
            assertContentEquals(original, file.readBytes())
        }

    @Test
    fun `malformed media recovery material cannot escape in exception diagnostics`() =
        runTest {
            val secret = Base64.encode(ByteArray(32) { 72 })
            val binding = Base64.encode(ByteArray(32) { 73 })
            val file =
                File(tempDir, "identity_recovery/media_key_backup").apply {
                    parentFile?.mkdirs()
                    writeText("""{"version":1,"identities":[{"identityBinding":"$binding","key":"$secret","checksum":"""")
                }
            val original = file.readBytes()
            val failure = assertFailsWith<IllegalStateException> { store.readMediaIdentity(binding) }
            assertTrue(secret !in failure.stackTraceToString())
            assertContentEquals(original, file.readBytes())
        }

    @Test
    fun `account and media records written on Android read the same in the iCloud Keychain store`() =
        runTest {
            val binding = Base64.encode(ByteArray(32) { 9 })
            store.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 5 }, ByteArray(32) { 6 }))
            store.writeMediaIdentity(MediaIdentityBackup(binding, ByteArray(32) { 4 }))
            val items =
                object : IdentityBackupItems {
                    val values =
                        mapOf(
                            "identity_account_key_backup_v1" to File(tempDir, "identity_recovery/account_key_backup").readBytes(),
                            "identity_media_key_backup_v1" to File(tempDir, "identity_recovery/media_key_backup").readBytes(),
                        )

                    override suspend fun read(name: String): ByteArray? = values[name]

                    override suspend fun write(
                        name: String,
                        value: ByteArray,
                    ) = error("read only")

                    override suspend fun delete(name: String) = error("read only")
                }
            val keychain = ItemIdentityKeyBackupStore(items)

            val account = keychain.readAccountIdentity()
            assertEquals("owner", account?.accountId)
            assertContentEquals(ByteArray(32) { 5 }, account?.key)
            assertContentEquals(ByteArray(32) { 6 }, account?.mediaKey)
            assertContentEquals(ByteArray(32) { 4 }, keychain.readMediaIdentity(binding)?.key)
        }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `read returns null when nothing has been written`() =
        runTest {
            assertNull(store.readPhrase())
        }

    @Test
    fun `write then read round-trips the phrase`() =
        runTest {
            val phrase = (1..12).joinToString(" ") { "word$it" }

            store.writePhrase(phrase)

            assertEquals(phrase, store.readPhrase())
        }

    @Test
    fun `write creates the backup file under identity_recovery`() =
        runTest {
            store.writePhrase("a b c")

            val backupFile = File(tempDir, "identity_recovery/phrase_backup")
            assertTrue(backupFile.exists())
            assertEquals("a b c", backupFile.readText())
        }

    @Test
    fun `write overwrites a previous backup`() =
        runTest {
            store.writePhrase("first phrase")
            store.writePhrase("second phrase")

            assertEquals("second phrase", store.readPhrase())
        }

    @Test
    fun `clear removes the backup file`() =
        runTest {
            store.writePhrase("a b c")

            store.clear()

            assertNull(store.readPhrase())
            assertTrue(!File(tempDir, "identity_recovery/phrase_backup").exists())
        }

    @Test
    fun `clear is safe to call when nothing was written`() =
        runTest {
            store.clear()

            assertNull(store.readPhrase())
        }

    @Test
    fun `keeping the key on this device moves every file out of the cloud backup directory and back`() =
        runTest {
            val binding = Base64.encode(ByteArray(32) { 2 })
            store.writeAccountIdentity(AccountIdentityBackup("owner", ByteArray(32) { 1 }, ByteArray(32) { 3 }))
            store.writeMediaIdentity(MediaIdentityBackup(binding, ByteArray(32) { 4 }))

            store.keepOnDevice(true)

            assertTrue(store.isKeptOnDevice())
            assertTrue(File(tempDir, "identity_recovery").listFiles().orEmpty().isEmpty())
            assertTrue(File(tempDir, "identity_recovery_device/account_key_backup").exists())
            assertEquals("owner", AndroidIdentityKeyBackupStore(context).readAccountIdentity()?.accountId)
            assertContentEquals(ByteArray(32) { 4 }, store.readMediaIdentity(binding)?.key)

            store.writePhrase("alpha beta")
            assertTrue(File(tempDir, "identity_recovery_device/phrase_backup").exists())
            assertTrue(!File(tempDir, "identity_recovery/phrase_backup").exists())

            store.keepOnDevice(false)

            assertEquals(false, store.isKeptOnDevice())
            assertEquals("alpha beta", store.readPhrase())
            assertTrue(File(tempDir, "identity_recovery/phrase_backup").exists())
            assertTrue(File(tempDir, "identity_recovery_device").listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `a move interrupted by a crash finishes on the next read`() =
        runTest {
            store.writePhrase("alpha beta")
            File(tempDir, "identity_recovery_device").mkdirs()
            File(tempDir, "identity_recovery_device/keep_on_device").writeBytes(byteArrayOf())

            assertEquals("alpha beta", AndroidIdentityKeyBackupStore(context).readPhrase())
            assertTrue(!File(tempDir, "identity_recovery/phrase_backup").exists())
        }

    @Test
    fun `clearing a key kept on this device removes it`() =
        runTest {
            store.keepOnDevice(true)
            store.writePhrase("alpha beta")

            store.clear()

            assertEquals(false, store.hasBackup())
            assertTrue(!File(tempDir, "identity_recovery_device/phrase_backup").exists())
            assertTrue(store.isKeptOnDevice())
        }
}
