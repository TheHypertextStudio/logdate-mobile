package app.logdate.client.device.restore

import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * A version 4 sender emits a selected camera original from its live path, which the restore rules
 * do not include. A vacant receiver places it beside the receipt that selected it, where staging
 * and reconciliation already expect a camera original, and only when the callback matches the
 * receipt's pinned inventory.
 *
 * Returns true when the callback bytes were consumed here.
 */
fun pinDirectNativeCameraOriginal(
    filesDir: File,
    incomingPath: String,
    size: Long,
    open: () -> InputStream,
): Boolean {
    if (!incomingPath.startsWith("files/camera-captures/")) return false
    val current = File(filesDir, "native_journal_transfer/current")
    val entry = pinnedCameraInventoryEntry(current, incomingPath) ?: return false
    val target = File(current, incomingPath)
    if (entry.size != size || Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
    val temporary = File.createTempFile(".camera-original-", ".part", checkNotNull(current.parentFile))
    try {
        open().use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (temporary.length() != entry.size || retainedJournalChecksum(temporary) != entry.sha256) {
            Napier.w("A transferred camera original did not match its receipt inventory")
            return true
        }
        retainedJournalDurableDirectory(checkNotNull(target.parentFile))
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        retainedJournalSyncDirectory(checkNotNull(target.parentFile))
    } finally {
        temporary.delete()
    }
    return true
}

private fun pinnedCameraInventoryEntry(
    current: File,
    path: String,
): NativeJournalMediaInventory.Entry? {
    val receiptFile = File(current, NATIVE_SOURCE_RECEIPT_PATH)
    val inventoryFile = File(current, NATIVE_MEDIA_INVENTORY_PATH)
    if (!Files.isRegularFile(receiptFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
        !Files.isRegularFile(inventoryFile.toPath(), LinkOption.NOFOLLOW_LINKS)
    ) {
        return null
    }
    return runCatching {
        val receipt = Json.decodeFromString<NativeJournalSourceReceipt>(retainedJournalReadBounded(receiptFile, 8192))
        if (receipt.version != 4) return null
        verifyNativeJournalMediaInventory(inventoryFile, checkNotNull(receipt.mediaInventorySha256)).entry(path)
    }.getOrNull()
}
