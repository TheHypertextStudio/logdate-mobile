package app.logdate.client.data.restore

import android.content.Context
import app.logdate.client.device.restore.NATIVE_MEDIA_INVENTORY_PATH
import app.logdate.client.device.restore.NATIVE_SOURCE_RECEIPT_PATH
import app.logdate.client.device.restore.NativeJournalRestoreAuthority
import app.logdate.client.device.restore.NativeJournalSourceReceipt
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.PostRestoreType
import app.logdate.client.device.restore.retainedJournalPassphraseFile
import app.logdate.client.device.restore.verifyNativeJournalMediaInventory
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal class MissingNativeJournalTransferKeyException : IllegalStateException()

/** Installation is allowed only by local vacancy intent captured before the first native file. */
internal class NativeJournalTransferRestore(
    private val context: Context,
    private val restorePreferences: (File) -> Unit,
    private val validateSnapshot: (Context) -> Map<String, File>? = ::validateNativeJournalTransfer,
) {
    private val current = File(context.filesDir, "native_journal_transfer/current")
    private val authority = NativeJournalRestoreAuthority(context)
    private val staging = NativeJournalTransferStaging(context)

    fun restore(): Boolean {
        if (staging.wasStaged()) return false
        if (current.exists() && authority.matchesCompletedSnapshot(snapshotFiles())) {
            authority.authorizeCompletedReplay(context.getDatabasePath("logdate"))
            return true
        }
        val files =
            try {
                validateSnapshot(context)
            } catch (_: MissingNativeJournalTransferKeyException) {
                return false
            }
        if (files == null) {
            check(!authority.isSnapshotRequired() && authority.installedReceipt() == null) {
                "The required immutable native journal remains incomplete"
            }
            return false
        }
        val receiptHash = checksum(files.getValue(NATIVE_SOURCE_RECEIPT_PATH))
        if (receiptHash == authority.completedReceipt()) {
            authority.authorizeCompletedReplay(context.getDatabasePath("logdate"))
            return true
        }
        if (authority.requiresCustodyForIncoming()) {
            staging.stage(files)
            return false
        }
        authority.requireVacant()
        val key =
            files
                .filterKeys { it in setOf("files/db_recovery/passphrase_backup", "files/db_recovery/working_passphrase_backup") }
                .values
                .single()
        val sourceDatabase = files.getValue("database/logdate")
        val install = authority.prepareInstallation(receiptHash, context.getDatabasePath("logdate"), checksum(sourceDatabase))
        listOf("logdate-wal", "logdate-shm", "logdate-journal").forEach { name ->
            val file = context.getDatabasePath(name)
            if (file.exists()) check(file.delete())
        }
        syncDirectory(context.getDatabasePath("logdate").parentFile!!)
        if (install) installAtomically(sourceDatabase, context.getDatabasePath("logdate"))
        installAtomically(key, retainedJournalPassphraseFile(context))
        restorePreferences(files.getValue("files/logdate.preferences_pb"))
        authority.recordInstalledPortableState()
        return true
    }

    fun wasStaged(): Boolean = staging.wasStaged()

    fun finish(): Boolean {
        val detector = PostRestoreDetector(context)
        check(detector.hasCompletedNativeRestoreDelivery())
        if (isAwaitingSnapshotKey(detector)) return false
        if (staging.wasStaged()) return finishStagedRestore()
        if (current.isDirectory && authority.installedReceipt() != null) {
            val files = validatedSnapshotOrNull() ?: return false
            if (hasReceiptPinnedCameraOriginals(files)) {
                // Fresh installs still take the normal DB, preferences, and account path. Preserve
                // the already verified source separately so post-start reconciliation can import
                // its pending draft without relying on the disposable callback workspace.
                staging.stage(files)
                return true
            }
        }
        pendingCleanupReceipt()?.let(::consumeSnapshot)
        authority.finish()
        return false
    }

    private fun isAwaitingSnapshotKey(detector: PostRestoreDetector): Boolean {
        if (!current.isDirectory ||
            !authority.isSnapshotRequired() ||
            authority.installedReceipt() != null ||
            detector.detect(isOnboarded = true) != PostRestoreType.CONTENT_MISSING
        ) {
            return false
        }
        return try {
            validateSnapshot(context)
            false
        } catch (_: MissingNativeJournalTransferKeyException) {
            true
        }
    }

    private fun finishStagedRestore(): Boolean {
        if (staging.hasPendingStageMarker() && authority.installedReceipt() != null) return true
        staging.finish()
        authority.finish()
        return false
    }

    private fun validatedSnapshotOrNull(): Map<String, File>? =
        try {
            validateSnapshot(context)
        } catch (_: MissingNativeJournalTransferKeyException) {
            null
        }

    private fun pendingCleanupReceipt(): String? {
        authority.cleanupReceipt()?.let { return it }
        if (!current.exists()) return null
        val receipt = checkNotNull(authority.installedReceipt())
        val files = snapshotFiles()
        check(
            (
                authority.completedReceipt() == receipt &&
                    authority.matchesCompletedSnapshot(files)
            ) ||
                files[NATIVE_SOURCE_RECEIPT_PATH]?.let(::checksum) == receipt,
        ) { "Uninstalled native journal inputs must remain in custody" }
        authority.rememberCompletedSnapshot(receipt, files)
        authority.beginCleanup(receipt)
        return receipt
    }

    private fun consumeSnapshot(receipt: String) {
        check(receipt == authority.installedReceipt())
        val consumed = File(current.parentFile, ".$receipt.consumed")
        if (!consumed.exists() && current.exists()) {
            check(authority.matchesCompletedSnapshot(snapshotFiles()))
            Files.move(current.toPath(), consumed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(current.parentFile!!)
        }
        if (consumed.exists()) {
            check(Files.isDirectory(consumed.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            check(consumed.deleteRecursively())
            syncDirectory(consumed.parentFile!!)
        }
    }

    fun reconciliationScheduled() {
        if (staging.hasPendingStageMarker() && authority.installedReceipt() != null) {
            staging.finish()
            authority.finish()
        }
    }

    private fun snapshotFiles(): Map<String, File> =
        current.walkTopDown().filter(File::isFile).associateBy { it.relativeTo(current).invariantSeparatorsPath }
}

private fun hasReceiptPinnedCameraOriginals(files: Map<String, File>): Boolean {
    val receiptFile = files[NATIVE_SOURCE_RECEIPT_PATH] ?: return false
    val receipt = Json.decodeFromString<NativeJournalSourceReceipt>(receiptFile.readText())
    if (receipt.version != 4 || receipt.entryDraftsSha256 == null) return false
    val expectedInventoryHash = receipt.mediaInventorySha256 ?: return false
    val inventoryFile = files[NATIVE_MEDIA_INVENTORY_PATH] ?: return false
    val inventory = verifyNativeJournalMediaInventory(inventoryFile, expectedInventoryHash)
    return inventory.files.any { it.path.startsWith("files/camera-captures/") }
}

private fun installAtomically(
    source: File,
    target: File,
) {
    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
    val temporary = File.createTempFile(".native-journal-", ".part", target.parentFile)
    try {
        source.inputStream().use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(target.parentFile!!)
    } finally {
        temporary.delete()
    }
}

private fun checksum(file: File): String =
    file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

private fun syncDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
