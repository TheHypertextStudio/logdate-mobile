package app.logdate.client.data.restore

import android.content.Context
import app.logdate.client.datastore.createAndroidMainDataStore
import app.logdate.client.device.restore.NATIVE_JOURNAL_TRANSFER_PREFIX
import app.logdate.client.device.restore.NATIVE_MEDIA_INVENTORY_PATH
import app.logdate.client.device.restore.NATIVE_SOURCE_RECEIPT_PATH
import app.logdate.client.device.restore.NativeIncomingJournal
import app.logdate.client.device.restore.NativeJournalMediaInventory
import app.logdate.client.device.restore.NativeJournalReplayCustody
import app.logdate.client.device.restore.NativeJournalSourceReceipt
import app.logdate.client.device.restore.verifyNativeJournalMediaInventory
import app.logdate.client.device.restore.verifyNativeJournalSourceReceipt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Retains exact source scope and auxiliary hashes after a staged database cohort is merged. */
class NativeJournalStagedSourceIndex(
    private val context: Context,
) {
    data class Record(
        val receipt: NativeJournalSourceReceipt,
        val mediaInventory: NativeJournalMediaInventory?,
        val pathSha256: Map<String, String>,
        val stagedAuxiliaryPhysicalPaths: Map<String, Set<String>>,
        val sourceCorePhysicalPaths: Map<String, Set<String>>,
    )

    @Serializable
    private data class Metadata(
        val receiptSha256: String,
        val pathSha256: Map<String, String>,
        val stagedAuxiliaryPhysicalPaths: Map<String, Set<String>> = emptyMap(),
        val sourceCorePhysicalPaths: Map<String, Set<String>> = emptyMap(),
    )

    private val root = File(context.noBackupFilesDir, "native-journal-staged-sources")

    /** The worker may retain an unstaged custody source; only auxiliaries in its receipt cohort are associated. */
    fun remember(files: Map<String, File>) {
        val payloads = NativeJournalReplayCustody(context).sealedPayloads()
        val receiptPayload =
            payloads.single {
                it.path == NATIVE_JOURNAL_TRANSFER_PREFIX + NATIVE_SOURCE_RECEIPT_PATH &&
                    it.file.canonicalFile == files.getValue(NATIVE_SOURCE_RECEIPT_PATH).canonicalFile
            }
        val receiptCohort = cohortId(receiptPayload.file)
        val sourceReceipt = Json.decodeFromString<NativeJournalSourceReceipt>(receiptPayload.file.readText())
        val inventory =
            sourceReceipt.mediaInventorySha256?.let { expected ->
                files[NATIVE_MEDIA_INVENTORY_PATH]?.let { verifyNativeJournalMediaInventory(it, expected) }
            }
        var missingCore = false
        val selectedPayloads =
            files
                .mapNotNull { (path, file) ->
                    val expected = nativeSnapshotChecksum(file)
                    val matching =
                        payloads
                            .filter {
                                it.complete &&
                                    cohortId(it.file) == receiptCohort &&
                                    (
                                        it.path == NATIVE_JOURNAL_TRANSFER_PREFIX + path ||
                                            (
                                                sourceReceipt.version == 4 &&
                                                    path.startsWith("files/camera-captures/") &&
                                                    it.path == path &&
                                                    inventory?.entry(path)?.let { entry ->
                                                        entry.size == it.expectedSize &&
                                                            entry.size == it.file.length() &&
                                                            entry.sha256 == expected
                                                    } == true
                                            )
                                    ) &&
                                    nativeSnapshotChecksum(it.file) == expected
                            }.let { candidates ->
                                candidates.singleOrNull { it.path == NATIVE_JOURNAL_TRANSFER_PREFIX + path } ?: candidates.singleOrNull()
                            }
                    if (!isStagedAuxiliaryPath(path) && matching == null) missingCore = true
                    matching?.let { path to it }
                }.toMap()
        if (missingCore) {
            check(hasRemembered(nativeSnapshotChecksum(receiptPayload.file))) { "Source callback is outside its receipt cohort" }
            return
        }
        val selected = selectedPayloads.mapValues { it.value.file }
        remember(selected, selected.mapValues { receiptCohort })
    }

    fun remember(
        files: Map<String, File>,
        cohortIds: Map<String, String>,
    ) {
        check(files.keys == cohortIds.keys)
        val receiptFile = files.getValue(NATIVE_SOURCE_RECEIPT_PATH)
        val receipt =
            verifyNativeJournalSourceReceipt(
                receiptFile,
                files.getValue("files/logdate.preferences_pb"),
                files.filterKeys { it.startsWith("database/") },
            )
        val receiptCohort = cohortIds.getValue(NATIVE_SOURCE_RECEIPT_PATH)
        val inventory =
            receipt.mediaInventorySha256?.let { expected ->
                files[NATIVE_MEDIA_INVENTORY_PATH]?.let { verifyNativeJournalMediaInventory(it, expected) }
            }
        val custody = NativeJournalReplayCustody(context)
        val sealedCohorts = mutableMapOf<String, List<NativeIncomingJournal.Payload>>()
        files.filterKeys { it.startsWith("files/camera-captures/") }.forEach { (path, file) ->
            val callback =
                sealedCohorts
                    .getOrPut(cohortIds.getValue(path)) { custody.sealedPayloadsInCohort(cohortIds.getValue(path)) }
                    .singleOrNull { it.complete && it.file.canonicalFile == file.canonicalFile }
            if (callback?.path == path) {
                val entry = inventory?.entry(path)
                check(
                    receipt.version == 4 &&
                        cohortIds[path] == receiptCohort &&
                        entry != null &&
                        entry.size == callback.expectedSize &&
                        entry.size == file.length() &&
                        entry.sha256 == nativeSnapshotChecksum(file),
                ) { "Direct camera original does not match its v4 receipt cohort inventory" }
            }
        }
        val metadata = metadataFor(files, cohortIds, receiptFile)
        val destination = File(root, metadata.receiptSha256)
        if (destination.exists()) {
            updateExisting(destination, metadata, receipt.mediaInventorySha256, files[NATIVE_MEDIA_INVENTORY_PATH])
        } else {
            createNew(destination, metadata, receiptFile, receipt.mediaInventorySha256, files[NATIVE_MEDIA_INVENTORY_PATH])
        }
    }

    private fun metadataFor(
        files: Map<String, File>,
        cohortIds: Map<String, String>,
        receiptFile: File,
    ): Metadata {
        val hashes = files.mapValues { nativeSnapshotChecksum(it.value) }
        val custody = NativeJournalReplayCustody(context)
        val physicalPaths =
            files.keys.associateWith { path ->
                setOf(
                    custody
                        .sealedPayloadsInCohort(cohortIds.getValue(path))
                        .filter { payload ->
                            payload.complete &&
                                (
                                    payload.path == NATIVE_JOURNAL_TRANSFER_PREFIX + path ||
                                        (payload.path == path && path.startsWith("files/camera-captures/"))
                                ) &&
                                nativeSnapshotChecksum(payload.file) == hashes.getValue(path)
                        }.let { candidates ->
                            val file = files.getValue(path).canonicalFile
                            candidates.singleOrNull { it.file.canonicalFile == file }
                                ?: candidates.single { it.path == NATIVE_JOURNAL_TRANSFER_PREFIX + path }
                        }.file.canonicalPath,
                )
            }
        return Metadata(
            nativeSnapshotChecksum(receiptFile),
            hashes,
            physicalPaths.filterKeys(::isStagedAuxiliaryPath),
            physicalPaths.filterKeys { !isStagedAuxiliaryPath(it) },
        )
    }

    private fun updateExisting(
        destination: File,
        metadata: Metadata,
        expectedInventoryHash: String?,
        inventoryFile: File?,
    ) {
        val existingRecord = read(destination)
        val existing = existingRecord.pathSha256
        check(existing.filterKeys { !isStagedAuxiliaryPath(it) } == metadata.pathSha256.filterKeys { !isStagedAuxiliaryPath(it) })
        val compatibleAuxiliary =
            metadata.pathSha256.filter { (path, hash) -> isStagedAuxiliaryPath(path) && (existing[path] == null || existing[path] == hash) }
        val combined =
            (existingRecord.stagedAuxiliaryPhysicalPaths.keys + compatibleAuxiliary.keys).associateWith { path ->
                val paths =
                    existingRecord.stagedAuxiliaryPhysicalPaths[path]
                        .orEmpty()
                        .filter { File(it).exists() }
                        .toSet() +
                        metadata.stagedAuxiliaryPhysicalPaths[path]
                            .orEmpty()
                            .takeIf { path in compatibleAuxiliary }
                            .orEmpty()
                check(paths.size <= 16)
                paths
            }
        val combinedCore =
            (existingRecord.sourceCorePhysicalPaths.keys + metadata.sourceCorePhysicalPaths.keys).associateWith { path ->
                val paths =
                    existingRecord.sourceCorePhysicalPaths[path]
                        .orEmpty()
                        .filter { File(it).exists() }
                        .toSet() +
                        metadata.sourceCorePhysicalPaths[path].orEmpty()
                check(paths.size <= 16)
                paths
            }
        val updated = Metadata(metadata.receiptSha256, existing + compatibleAuxiliary, combined, combinedCore)
        existing[NATIVE_MEDIA_INVENTORY_PATH]?.let { check(it == expectedInventoryHash) }
        val temporary = File(destination, ".metadata.part")
        write(temporary, Json.encodeToString(Metadata.serializer(), updated))
        Files.move(
            temporary.toPath(),
            File(destination, "metadata.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        sync(destination)
        inventoryFile?.let { retainMediaInventory(metadata.receiptSha256, it) }
    }

    private fun createNew(
        destination: File,
        metadata: Metadata,
        receiptFile: File,
        expectedInventoryHash: String?,
        inventoryFile: File?,
    ) {
        check(root.mkdirs() || root.isDirectory)
        val temporary = Files.createTempDirectory(root.toPath(), ".staged-").toFile()
        try {
            copy(receiptFile, File(temporary, "receipt.json"))
            inventoryFile?.let { copy(it, File(temporary, "media-inventory.json")) }
            check(nativeSnapshotChecksum(File(temporary, "receipt.json")) == metadata.receiptSha256)
            expectedInventoryHash?.let { expected ->
                File(temporary, "media-inventory.json").takeIf(File::exists)?.let { verifyNativeJournalMediaInventory(it, expected) }
            }
            write(File(temporary, "metadata.json"), Json.encodeToString(Metadata.serializer(), metadata))
            sync(temporary)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            sync(root)
        } finally {
            if (temporary.exists()) check(temporary.deleteRecursively())
        }
    }

    /** This marker is written only after durable database merge coverage is proven. */
    fun admitAfterMerged(receiptSha256: String) {
        val directory = directory(receiptSha256)
        read(directory)
        val marker = File(directory, "admitted")
        if (!marker.exists()) {
            val temporary = File(directory, ".admitted.part")
            write(temporary, receiptSha256)
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
            sync(directory)
        }
        check(marker.readText() == receiptSha256)
    }

    fun admittedSources(): Map<String, Record> =
        root
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.matches(SHA256) && File(it, "admitted").exists() }
            .associate { directory ->
                check(File(directory, "admitted").readText() == directory.name)
                directory.name to read(directory)
            }

    fun admittedMediaInventories(): List<NativeJournalMediaInventory> = admittedSources().values.mapNotNull(Record::mediaInventory)

    /** A Backup settings retry is local only when the retained callback and current account scope still agree. */
    fun isRecoverableAuxiliary(payload: NativeIncomingJournal.Payload): Boolean =
        runCatching {
            runBlocking {
                val preferences = createAndroidMainDataStore(context).data.first()
                val current = RetainedJournalSourceScope.from(preferences) ?: return@runBlocking false
                admittedSources().any { (receipt, record) ->
                    val source =
                        normalizeBackendOrigin(record.receipt.backendOrigin)?.let { origin ->
                            RetainedJournalSourceScope(
                                record.receipt.canonicalOwnerId,
                                record.receipt.canonicalOwnerBound == "true",
                                origin,
                            )
                        }
                    source?.matchesCurrent(current) == true && verifiedAuxiliary(receipt, listOf(payload)).isNotEmpty()
                }
            }
        }.getOrDefault(false)

    fun hasRemembered(receiptSha256: String): Boolean =
        directory(receiptSha256).takeIf(File::exists)?.let {
            read(it)
            true
        } == true

    /** A v3 inventory may arrive in a later framework callback than its core journal. */
    fun retainMediaInventory(
        receiptSha256: String,
        file: File,
    ) {
        val directory = directory(receiptSha256)
        val expected = checkNotNull(read(directory).receipt.mediaInventorySha256)
        verifyNativeJournalMediaInventory(file, expected)
        val destination = File(directory, "media-inventory.json")
        if (destination.exists()) {
            verifyNativeJournalMediaInventory(destination, expected)
            return
        }
        val temporary = File(directory, ".media-inventory.part")
        copy(file, temporary)
        verifyNativeJournalMediaInventory(temporary, expected)
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        sync(directory)
    }

    /** A caller still proves source-session scope and durable use before retiring these exact callbacks. */
    fun verifiedAuxiliary(
        receiptSha256: String,
        payloads: Iterable<NativeIncomingJournal.Payload>,
    ): Map<String, File> {
        val admitted = admittedSources()[receiptSha256] ?: return emptyMap()
        val hashes = admitted.pathSha256
        return payloads
            .filter { payload ->
                val path = payload.path.removePrefix(NATIVE_JOURNAL_TRANSFER_PREFIX)
                val directCamera =
                    admitted.receipt.version == 4 &&
                        path.startsWith("files/camera-captures/") &&
                        payload.path == path &&
                        admitted.mediaInventory?.entry(path)?.let { entry ->
                            entry.size == payload.expectedSize && entry.size == payload.file.length() && entry.sha256 == hashes[path]
                        } == true
                payload.complete &&
                    (payload.path.startsWith(NATIVE_JOURNAL_TRANSFER_PREFIX) || payload.path in RAW_AUXILIARY_PATHS || directCamera) &&
                    isStagedAuxiliaryPath(path)
            }.mapNotNull { payload ->
                val path = payload.path.removePrefix(NATIVE_JOURNAL_TRANSFER_PREFIX)
                val expected =
                    if (path == NATIVE_MEDIA_INVENTORY_PATH) admitted.receipt.mediaInventorySha256 else hashes[path]
                val bound =
                    if (path == NATIVE_MEDIA_INVENTORY_PATH && path !in admitted.stagedAuxiliaryPhysicalPaths) {
                        admitted.mediaInventory != null
                    } else {
                        payload.file.canonicalPath in admitted.stagedAuxiliaryPhysicalPaths[path].orEmpty()
                    }
                if (bound && expected == nativeSnapshotChecksum(payload.file)) payload.file.canonicalPath to payload.file else null
            }.toMap()
    }

    private fun read(directory: File): Record {
        check(directory.name.matches(SHA256) && Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        val metadataFile = File(directory, "metadata.json")
        check(metadataFile.length() in 1..16_384)
        val metadata = Json.decodeFromString<Metadata>(metadataFile.readText())
        check(metadata.receiptSha256 == directory.name && metadata.pathSha256.values.all { it.matches(SHA256) })
        val receiptFile = File(directory, "receipt.json")
        check(nativeSnapshotChecksum(receiptFile) == directory.name)
        check(metadata.pathSha256[NATIVE_SOURCE_RECEIPT_PATH] == directory.name)
        check(receiptFile.length() in 1..8192)
        val receipt = Json.decodeFromString<NativeJournalSourceReceipt>(receiptFile.readText())
        val mediaInventory =
            receipt.mediaInventorySha256?.let { hash ->
                check(metadata.pathSha256[NATIVE_MEDIA_INVENTORY_PATH] == null || metadata.pathSha256[NATIVE_MEDIA_INVENTORY_PATH] == hash)
                File(directory, "media-inventory.json").takeIf(File::exists)?.let { verifyNativeJournalMediaInventory(it, hash) }
            }
        if (receipt.mediaInventorySha256 == null) check(!File(directory, "media-inventory.json").exists())
        return Record(receipt, mediaInventory, metadata.pathSha256, metadata.stagedAuxiliaryPhysicalPaths, metadata.sourceCorePhysicalPaths)
    }

    private fun directory(receiptSha256: String): File {
        check(receiptSha256.matches(SHA256))
        return File(root, receiptSha256)
    }

    private fun cohortId(file: File): String =
        checkNotNull(file.parentFile?.parentFile?.parentFile)
            .name
            .also {
                check(
                    kotlin.uuid.Uuid
                        .parse(it)
                        .toString() == it,
                )
            }

    private fun copy(
        source: File,
        destination: File,
    ) {
        check(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS))
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun write(
        destination: File,
        value: String,
    ) {
        FileOutputStream(destination).use {
            it.write(value.encodeToByteArray())
            it.fd.sync()
        }
    }

    private fun sync(directory: File) = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        val AUXILIARY_PATHS =
            setOf(
                "files/db_recovery/passphrase_backup",
                "files/db_recovery/working_passphrase_backup",
                NATIVE_MEDIA_INVENTORY_PATH,
                "sender_owner",
            )

        private fun isStagedAuxiliaryPath(path: String): Boolean = path in AUXILIARY_PATHS || path.startsWith("files/camera-captures/")

        val RAW_AUXILIARY_PATHS =
            setOf("files/db_recovery/passphrase_backup", "files/db_recovery/working_passphrase_backup", NATIVE_MEDIA_INVENTORY_PATH)
    }
}
