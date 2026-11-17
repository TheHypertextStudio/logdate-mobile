package app.logdate.server.crypto

import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class BackupFileStreamingTest {
    private val service =
        EncryptionService(
            EncryptionPolicy(EncryptionMode.AT_REST_ONLY, true, true),
            PayloadCodec(NoOpKeyring),
        )

    @Test
    fun `one gigabyte archive round trips with bounded heap`() {
        if (System.getenv("LOGDATE_LARGE_BACKUP_TEST") != "1") return
        val plain = Files.createTempFile("backup-gigabyte-", ".bin")
        val encrypted = Files.createTempFile("backup-gigabyte-encrypted-", ".bin")
        val restored = Files.createTempFile("backup-gigabyte-restored-", ".bin")
        try {
            RandomAccessFile(plain.toFile(), "rw").use { it.setLength(1024L * 1024 * 1024) }
            service.processBackupUpload(plain, encrypted)
            service.processBackupDownload(encrypted, restored, true)
            kotlin.test.assertEquals(Files.size(plain), Files.size(restored))
            assertContentEquals(digest(plain), digest(restored))
        } finally {
            Files.deleteIfExists(plain)
            Files.deleteIfExists(encrypted)
            Files.deleteIfExists(restored)
        }
    }

    @Test
    fun `hundred megabyte archive round trips with bounded file chunks`() {
        val plain = Files.createTempFile("backup-large-plain-", ".bin")
        val encrypted = Files.createTempFile("backup-large-encrypted-", ".bin")
        val restored = Files.createTempFile("backup-large-restored-", ".bin")
        try {
            RandomAccessFile(plain.toFile(), "rw").use { it.setLength(100L * 1024 * 1024) }
            service.processBackupUpload(plain, encrypted)
            service.processBackupDownload(encrypted, restored, true)
            kotlin.test.assertEquals(Files.size(plain), Files.size(restored))
            kotlin.test.assertContentEquals(digest(plain), digest(restored))
        } finally {
            Files.deleteIfExists(plain)
            Files.deleteIfExists(encrypted)
            Files.deleteIfExists(restored)
        }
    }

    private fun digest(path: java.nio.file.Path): ByteArray =
        MessageDigest.getInstance("SHA-256").let { digest ->
            Files.newInputStream(path).buffered().use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    digest.update(chunk, 0, read)
                }
            }
            digest.digest()
        }

    @Test
    fun `file encryption keeps legacy backup wire format`() {
        val plain = Files.createTempFile("backup-plain-", ".zip")
        val encrypted = Files.createTempFile("backup-encrypted-", ".bin")
        val restored = Files.createTempFile("backup-restored-", ".zip")
        try {
            val data = ByteArray(256 * 1024) { it.toByte() }
            Files.write(plain, data)
            service.processBackupUpload(plain, encrypted)
            assertContentEquals(data, service.processBackupDownload(Files.readAllBytes(encrypted), true))
            service.processBackupDownload(encrypted, restored, true)
            assertContentEquals(data, Files.readAllBytes(restored))

            Files.write(encrypted, service.processBackupUpload(data).data)
            service.processBackupDownload(encrypted, restored, true)
            assertContentEquals(data, Files.readAllBytes(restored))
        } finally {
            Files.deleteIfExists(plain)
            Files.deleteIfExists(encrypted)
            Files.deleteIfExists(restored)
        }
    }

    @Test
    fun `tampered archive cannot be returned as a restored file`() {
        val plain = Files.createTempFile("backup-plain-", ".zip")
        val encrypted = Files.createTempFile("backup-encrypted-", ".bin")
        val restored = Files.createTempFile("backup-restored-", ".zip")
        try {
            Files.write(plain, ByteArray(128 * 1024) { it.toByte() })
            service.processBackupUpload(plain, encrypted)
            val corrupted = Files.readAllBytes(encrypted)
            corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
            Files.write(encrypted, corrupted)
            Files.delete(restored)
            assertFailsWith<Exception> { service.processBackupDownload(encrypted, restored, true) }
            kotlin.test.assertFalse(Files.exists(restored))
        } finally {
            Files.deleteIfExists(plain)
            Files.deleteIfExists(encrypted)
            Files.deleteIfExists(restored)
        }
    }

    @Test
    fun `cancelled encryption stops before reading the whole archive`() {
        val plain = Files.createTempFile("backup-cancel-plain-", ".bin")
        val encrypted = Files.createTempFile("backup-cancel-encrypted-", ".bin")
        try {
            RandomAccessFile(plain.toFile(), "rw").use { it.setLength(100L * 1024 * 1024) }
            var chunks = 0
            assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
                service.processBackupUpload(plain, encrypted) {
                    if (++chunks == 4) throw kotlin.coroutines.cancellation.CancellationException("cancelled")
                }
            }
            kotlin.test.assertTrue(Files.size(encrypted) < Files.size(plain))
        } finally {
            Files.deleteIfExists(plain)
            Files.deleteIfExists(encrypted)
        }
    }
}
