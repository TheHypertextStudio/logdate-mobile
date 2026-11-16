package app.logdate.client.data.restore

import android.content.Context
import app.logdate.client.device.crypto.AndroidCryptoManager
import app.logdate.client.device.crypto.AndroidIdentityKeyBackupStore
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.KeyDerivation
import app.logdate.client.device.identity.ExistingCanonicalOwner
import app.logdate.client.device.restore.PrivateOsCustodyRecord
import app.logdate.client.device.storage.createAndroidSecureStorage
import app.logdate.client.domain.export.archive.StagingDirectoryArchiveContainer
import app.logdate.client.domain.restore.archive3.NativeArchive3Bundle
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Emits only the verified Archive3 directory and its matching private record. */
internal class NativeIosTransferSender(
    private val context: Context,
    private val journal: NativeJournalTransferSnapshot,
) {
    private val root: File get() = File(context.filesDir, "logdate-os-transfer")
    private val lockFile: File get() = File(context.noBackupFilesDir, "native-ios-transfer.lock")

    /** BackupAgent startup runs before any export callback and removes a crash-leftover secret. */
    fun discardAbandoned() {
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (lock != null) lock.use { discard() }
        }
    }

    suspend fun send(
        transportFlags: Int,
        emit: (File) -> Unit,
    ) {
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                sendLocked(transportFlags, emit)
            }
        }
    }

    private suspend fun sendLocked(
        transportFlags: Int,
        emit: (File) -> Unit,
    ) {
        discard()
        try {
            journal.prepare(includeProfileOnlySource = true)
            if (journal.skipEmptyIosTransfer()) return
            val archive = File(root, "archive")
            journal.writeArchive3(StagingDirectoryArchiveContainer(FileSystem.SYSTEM, archive.path.toPath()))
            val archiveFiles =
                archive
                    .walkTopDown()
                    .filter(File::isFile)
                    .sortedBy { it.relativeTo(archive).path }
                    .toList()
            val bundle =
                NativeArchive3Bundle.read(
                    archiveFiles.map { it.relativeTo(archive).invariantSeparatorsPath },
                    { path -> File(archive, path).source() },
                )
            val record = prepareCustodyRecord(transportFlags, archiveFiles, archive, bundle)
            val privateDirectory = File(root, ".logdate-os-private")
            check(privateDirectory.mkdir())
            val custody = File(privateDirectory, "custody-v1.json")
            record.withEncodedBytes { bytes ->
                try {
                    custody.writeBytes(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            emitNativeIosStagedFiles(root, emit)
        } finally {
            try {
                discard()
            } finally {
                journal.finish()
            }
        }
    }

    private suspend fun prepareCustodyRecord(
        transportFlags: Int,
        archiveFiles: List<File>,
        archive: File,
        bundle: NativeArchive3Bundle,
    ): PrivateOsCustodyRecord {
        val (receipt, sourceGeneration) = journal.pinnedSourceIdentity()
        val owner = ExistingCanonicalOwner(receipt.canonicalOwnerId, receipt.canonicalOwnerBound == "true")
        val crypto = AndroidCryptoManager()
        val identity =
            IdentityKeyManager(
                createAndroidSecureStorage(context),
                crypto,
                AndroidIdentityKeyBackupStore(context),
            )
        val record =
            PrivateOsCustodyRecord.prepare(
                transportFlags = transportFlags,
                transferId = Uuid.random(),
                sourceGeneration = sourceGeneration,
                owner = owner,
                checksumsFileSha256 = bundle.checksumsSha256,
                manifestSha256 = bundle.manifestSha256,
            ) {
                identity.snapshotExistingKeys(owner.rawId.takeIf { owner.isBound }) { root ->
                    KeyDerivation(crypto).deriveKey(root, "media_payload", "media_payload_v1")
                }
            } ?: run {
                if (owner.isBound || archiveFiles.any { it.relativeTo(archive).invariantSeparatorsPath.startsWith("media/") }) {
                    throw IOException("Native iOS transfer is unavailable")
                }
                PrivateOsCustodyRecord.prepareRootlessPlaintext(
                    transportFlags,
                    Uuid.random(),
                    sourceGeneration,
                    owner,
                    bundle.checksumsSha256,
                    bundle.manifestSha256,
                ) { identity.observeRootlessKeys() } ?: throw IOException("Native iOS transfer is unavailable")
            }
        return record
    }

    private fun discard() {
        if (root.exists()) check(root.deleteRecursively()) { "Native iOS staging could not be removed" }
    }
}

/** The framework receives exactly the two directories the Apple migration extension admits. */
internal fun emitNativeIosStagedFiles(
    root: File,
    emit: (File) -> Unit,
) {
    val archive = File(root, "archive")
    val privateDirectory = File(root, ".logdate-os-private")
    val custody = File(privateDirectory, "custody-v1.json")
    check(root.listFiles()?.map(File::getName)?.toSet() == setOf("archive", ".logdate-os-private"))
    check(privateDirectory.listFiles()?.map(File::getName) == listOf("custody-v1.json"))
    check(Files.isDirectory(archive.toPath(), LinkOption.NOFOLLOW_LINKS))
    check(Files.isDirectory(privateDirectory.toPath(), LinkOption.NOFOLLOW_LINKS))
    val walk =
        archive
            .walkTopDown()
            .onEnter {
                check(Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Native iOS transfer contains a linked directory" }
                true
            }
    val archiveFiles =
        walk
            .filter(File::isFile)
            .sortedBy { it.relativeTo(archive).path }
            .toList()
    check(archiveFiles.isNotEmpty())
    val files = archiveFiles + custody
    val digests = files.associateWith(::nativeIosSha256)
    files.forEach { file ->
        check(nativeIosSha256(file) == digests.getValue(file)) { "Native iOS transfer source changed" }
        emit(file)
        check(nativeIosSha256(file) == digests.getValue(file)) { "Native iOS transfer source changed" }
    }
}

private fun nativeIosSha256(file: File): String {
    check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** A normal app launch also clears private bytes left by a killed BackupAgent process. */
fun discardAbandonedNativeIosTransfer(context: Context) =
    NativeIosTransferSender(context, NativeJournalTransferSnapshot(context)).discardAbandoned()
