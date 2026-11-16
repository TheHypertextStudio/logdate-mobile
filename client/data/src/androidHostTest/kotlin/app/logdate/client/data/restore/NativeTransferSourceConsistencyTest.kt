package app.logdate.client.data.restore

import app.logdate.client.device.restore.NativeJournalMediaInventory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NativeTransferSourceConsistencyTest {
    @Test
    fun `a live preference change does not invalidate the selected snapshot`() {
        val root = Files.createTempDirectory("native-transfer-preferences-").toFile()
        try {
            val pinned = root.resolve("pinned").apply { writeText("selected") }
            val live = root.resolve("live").apply { writeText("selected") }
            val checksum = nativeSnapshotChecksum(pinned)

            requirePinnedNativeTransferFile(pinned, checksum)
            live.writeText("changed")
            requirePinnedNativeTransferFile(pinned, checksum)

            pinned.writeText("tampered")
            assertFailsWith<IllegalStateException> { requirePinnedNativeTransferFile(pinned, checksum) }
        } finally {
            check(root.deleteRecursively())
        }
    }

    @Test
    fun `direct media emission uses the selected original and aborts when it changes during transfer`() {
        val root = Files.createTempDirectory("native-direct-camera-").toFile()
        try {
            val original =
                root.resolve("camera-captures/selected.mp4").apply {
                    check(parentFile!!.mkdirs())
                    writeText("selected original bytes")
                }
            val entry =
                NativeJournalMediaInventory.Entry(
                    "files/camera-captures/selected.mp4",
                    original.length(),
                    nativeSnapshotChecksum(original),
                )
            var emitted: File? = null

            assertFailsWith<IllegalStateException> {
                emitVerifiedNativeMedia(original, entry) { file ->
                    emitted = file
                    file.writeText("changed while being emitted")
                }
            }

            assertSame(original, emitted)
        } finally {
            check(root.deleteRecursively())
        }
    }
}
