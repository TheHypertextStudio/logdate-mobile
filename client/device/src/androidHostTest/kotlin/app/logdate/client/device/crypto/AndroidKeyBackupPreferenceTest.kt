package app.logdate.client.device.crypto

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidKeyBackupPreferenceTest {
    private val tempDir = createTempDirectory("key-backup-preference-test").toFile()
    private val context = mockk<Context>(relaxed = true).also { every { it.filesDir } returns tempDir }
    private val files = AndroidIdentityKeyBackupStore(context)
    private val bytes = RecordingBlockStoreBytes()
    private val mirror = BlockStoreIdentityKeyBackupStore(bytes) { !files.isKeptOnDevice() }

    @Test
    fun `cloud backup is allowed until the person turns it off`() =
        runTest {
            assertTrue(AndroidKeyBackupPreference(files, mirror).cloudBackupAllowed.first())
        }

    @Test
    fun `turning cloud backup off moves the files and rewrites Block Store without the cloud`() =
        runTest {
            files.writePhrase("alpha beta")
            mirror.writePhrase("alpha beta")
            assertTrue(bytes.backedUp)
            val preference = AndroidKeyBackupPreference(files, mirror)

            preference.setCloudBackupAllowed(false)

            assertFalse(preference.cloudBackupAllowed.first())
            assertFalse(bytes.backedUp)
            assertTrue(!File(tempDir, "identity_recovery/phrase_backup").exists())
            assertFalse(AndroidKeyBackupPreference(files, mirror).cloudBackupAllowed.first())

            preference.setCloudBackupAllowed(true)

            assertTrue(preference.cloudBackupAllowed.first())
            assertTrue(bytes.backedUp)
            assertTrue(File(tempDir, "identity_recovery/phrase_backup").exists())
            assertEquals("alpha beta", files.readPhrase())
        }
}

private class RecordingBlockStoreBytes : BlockStoreBytes {
    var stored: ByteArray? = null
    var backedUp = false

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

    override suspend fun isEndToEndEncryptionAvailable(): Boolean = true
}
