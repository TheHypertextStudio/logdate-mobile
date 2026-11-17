package app.logdate.client.device.restore

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

/** Post-completion callbacks never write canonical database, preference, identity, or media paths. */
class NativeJournalReplayCustody(
    private val context: Context,
) {
    private val root = File(context.noBackupFilesDir, "native-journal-replay-custody")
    private val journal = NativeIncomingJournal(root)
    private var active: String? = null

    fun capture(
        path: String,
        size: Long,
        input: InputStream,
    ) {
        val individualMedia = isNativeSelectedOriginal(path)
        val id =
            if (individualMedia) {
                Uuid.random().toString().also(journal::begin)
            } else {
                active ?: Uuid.random().toString().also {
                    journal.begin(it)
                    active = it
                }
            }
        if (individualMedia) retainedJournalWriteAtomic(File(root, "$id/media-only"), "v1")
        journal.capture(id, path, size, input)
    }

    /** A staged source assigns its cohort before copying bytes, so retries cannot borrow another delivery's callback. */
    fun captureInCohort(
        id: String,
        path: String,
        size: Long,
        input: InputStream,
    ) {
        check(Uuid.parse(id).toString() == id)
        journal.begin(id)
        journal.capture(id, path, size, input)
    }

    fun sealedPayloadsInCohort(id: String): List<NativeIncomingJournal.Payload> {
        check(Uuid.parse(id).toString() == id)
        val directory = File(root, id)
        if (!directory.exists()) return emptyList()
        check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        return journal.verified(id, journal.finish(id))
    }

    internal fun sealCohort(id: String): String {
        requireRepairCohort(id)
        return journal.finish(id)
    }

    internal fun verifiedCohort(
        id: String,
        manifestSha256: String,
    ): List<NativeIncomingJournal.Payload> {
        requireRepairCohort(id)
        return journal.verified(id, manifestSha256)
    }

    private fun requireRepairCohort(id: String) {
        check(Uuid.parse(id).toString() == id)
        check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        check(Files.isDirectory(File(root, id).toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    fun hasCohort(id: String): Boolean {
        check(Uuid.parse(id).toString() == id)
        return File(root, id).exists()
    }

    fun retireInterruptedStagedCohortGroup(
        oldId: String,
        sources: Map<String, Pair<File, String>>,
    ) {
        check(Uuid.parse(oldId).toString() == oldId)
        sources.values.forEach { (source, hash) ->
            check(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) && retainedJournalChecksum(source) == hash)
        }
        val old = File(root, oldId)
        if (!old.exists()) return
        val byPhysicalPath = sources.mapKeys { NATIVE_JOURNAL_TRANSFER_PREFIX + it.key }
        val payloads = sealedPayloadsInCohort(oldId)
        check(
            payloads.all { payload ->
                val source = byPhysicalPath[payload.path] ?: return@all false
                payload.expectedSize == source.first.length() && prefixMatchesSource(payload.file, source.first)
            },
        )
        consume(old)
    }

    private fun prefixMatchesSource(
        partial: File,
        source: File,
    ): Boolean {
        if (partial.length() > source.length()) return false
        partial.inputStream().buffered().use { left ->
            source.inputStream().buffered().use { right ->
                val leftBytes = ByteArray(64 * 1024)
                val rightBytes = ByteArray(leftBytes.size)
                while (true) {
                    val count = left.read(leftBytes)
                    if (count < 0) return true
                    var offset = 0
                    while (offset < count) {
                        val read = right.read(rightBytes, offset, count - offset)
                        if (read < 0) return false
                        offset += read
                    }
                    if (!leftBytes.copyOfRange(0, count).contentEquals(rightBytes.copyOfRange(0, count))) return false
                }
            }
        }
    }

    private fun unaccountedSources(): List<File> =
        root.listFiles().orEmpty().filter { directory ->
            !directory.name.startsWith('.') && !isAccountedNativePrivacyRepairCohort(directory)
        }

    fun hasInputs(): Boolean {
        cleanupConsumed()
        return unaccountedSources().any { it.isDirectory }
    }

    /** Media-only custody cannot turn a fresh snapshot callback into an occupied-journal replay. */
    fun hasJournalInputs(): Boolean {
        cleanupConsumed()
        return unaccountedSources().any { directory ->
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            val marker = File(directory, "media-only")
            if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS) || retainedJournalReadBounded(marker, 16) != "v1") {
                true
            } else {
                val paths = journal.recordedPathsForRouting(directory.name)
                paths.isEmpty() || paths.any { !isNativeSelectedOriginal(it) }
            }
        }
    }

    fun sealedPayloads(): List<NativeIncomingJournal.Payload> =
        unaccountedSources().flatMap { directory ->
            check(
                Uuid.parse(directory.name).toString() == directory.name && Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS),
            )
            journal.verified(directory.name, journal.finish(directory.name))
        }

    /** Missing receipt, incomplete revisions and unfamiliar files retain the entire cohort. */
    fun finish(): Boolean {
        cleanupConsumed()
        var pending = false
        unaccountedSources().forEach { directory ->
            val id = directory.name
            check(Uuid.parse(id).toString() == id && Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            val hash = journal.finish(id)
            val payloads = journal.verified(id, hash)
            if (NativeJournalRestoreAuthority(context).matchesCompletedPayloads(payloads)) {
                consume(directory)
            } else {
                pending = true
            }
        }
        active = null
        return !pending
    }

    /** Caller has verified durable canonical objects and resolved any captured reference CAS before acknowledging these payloads. */
    fun acknowledgeRecoveredMedia(verifiedFiles: Set<String>) {
        cleanupConsumed()
        unaccountedSources().forEach { directory ->
            val id = directory.name
            check(Uuid.parse(id).toString() == id && Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            val payloads = journal.verified(id, journal.finish(id))
            val remaining =
                payloads.filterNot {
                    it.complete &&
                        (
                            it.path.startsWith("files/media/objects/sha256/") ||
                                it.path.startsWith("files/user_media/") ||
                                it.path.startsWith("files/audio_notes/")
                        ) &&
                        it.file.canonicalPath in verifiedFiles
                }
            val replay = remaining.isEmpty() || NativeJournalRestoreAuthority(context).matchesCompletedPayloads(remaining)
            if (payloads.isNotEmpty() && replay) consume(directory)
        }
        active = null
    }

    /** Exact staged path and hash proof is supplied by the caller after durable source merge coverage. */
    fun acknowledgeStagedAuxiliary(
        verifiedPhysicalPaths: Set<String>,
        acceptedSourceHashes: Map<String, String> = emptyMap(),
    ) {
        cleanupConsumed()
        val sourceCameraFiles =
            verifiedNativeJournalSources(sealedPayloads())
                .values
                .flatMap { source -> source.filterKeys { it.startsWith("files/camera-captures/") }.toList() }
                .associate { (path, file) -> path to file.canonicalPath }
        val core =
            acceptedSourceHashes.filterKeys { path ->
                path == NATIVE_SOURCE_RECEIPT_PATH || path == "files/logdate.preferences_pb" || path.startsWith("database/")
            }
        unaccountedSources().forEach { directory ->
            val id = directory.name
            check(Uuid.parse(id).toString() == id && Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            val payloads = journal.verified(id, journal.finish(id))
            val fullSourcePresent =
                NATIVE_SOURCE_RECEIPT_PATH in core &&
                    "files/logdate.preferences_pb" in core &&
                    "database/logdate" in core &&
                    core.keys.all { path ->
                        payloads.any { payload ->
                            payload.complete &&
                                payload.path == NATIVE_JOURNAL_TRANSFER_PREFIX + path &&
                                retainedJournalChecksum(payload.file) == core.getValue(path)
                        }
                    }
            if (payloads.isNotEmpty() &&
                payloads.all { payload ->
                    val path = payload.path.removePrefix(NATIVE_JOURNAL_TRANSFER_PREFIX)
                    val verifiedAuxiliary =
                        path in STAGED_AUXILIARY_PATHS &&
                            (payload.path.startsWith(NATIVE_JOURNAL_TRANSFER_PREFIX) || payload.path in RAW_STAGED_AUXILIARY) &&
                            payload.file.canonicalPath in verifiedPhysicalPaths
                    val verifiedDirectCamera =
                        path.startsWith("files/camera-captures/") &&
                            payload.path == path &&
                            sourceCameraFiles[path] == payload.file.canonicalPath &&
                            acceptedSourceHashes[path] == retainedJournalChecksum(payload.file) &&
                            payload.file.canonicalPath in verifiedPhysicalPaths
                    val verifiedCore =
                        fullSourcePresent &&
                            payload.path.startsWith(NATIVE_JOURNAL_TRANSFER_PREFIX) &&
                            core[path] == retainedJournalChecksum(payload.file)
                    payload.complete && (verifiedAuxiliary || verifiedDirectCamera || verifiedCore)
                }
            ) {
                consume(directory)
            }
        }
        active = null
    }

    /** Retires only exact source payload revisions that the caller has durably merged or imported. */
    fun acknowledgeMergedSource(
        receiptSha256: String,
        acceptedFiles: Map<String, File>,
        boundCorePhysicalPaths: Map<String, Set<String>> = emptyMap(),
    ) {
        check(receiptSha256.matches(Regex("[a-f0-9]{64}")))
        cleanupConsumed()
        val all = sealedPayloads()
        val source =
            verifiedNativeJournalSources(all).values.singleOrNull { files ->
                files["source_scope.json"]?.let { retainedJournalChecksum(it) == receiptSha256 } == true
            } ?: return
        val merged = MergedSourceAcknowledgement.verify(receiptSha256, source, acceptedFiles, boundCorePhysicalPaths)
        unaccountedSources().forEach { directory ->
            val id = directory.name
            check(Uuid.parse(id).toString() == id && Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            val payloads = journal.verified(id, journal.finish(id))
            if (merged.retires(payloads)) {
                consume(directory)
            }
        }
        active = null
    }

    private fun consume(directory: File) {
        val consumed = File(root, ".${directory.name}.consumed")
        Files.move(directory.toPath(), consumed.toPath(), StandardCopyOption.ATOMIC_MOVE)
        retainedJournalSyncDirectory(root)
        check(consumed.deleteRecursively())
        retainedJournalSyncDirectory(root)
    }

    private fun cleanupConsumed() {
        root.listFiles().orEmpty().filter { it.name.startsWith('.') && it.name.endsWith(".consumed") }.forEach {
            val id = it.name.removePrefix(".").removeSuffix(".consumed")
            check(Uuid.parse(id).toString() == id && Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS))
            check(it.deleteRecursively())
            retainedJournalSyncDirectory(root)
        }
    }

    private companion object {
        val STAGED_AUXILIARY_PATHS =
            setOf(
                "files/db_recovery/passphrase_backup",
                "files/db_recovery/working_passphrase_backup",
                NATIVE_MEDIA_INVENTORY_PATH,
                "sender_owner",
            )
        val RAW_STAGED_AUXILIARY =
            setOf("files/db_recovery/passphrase_backup", "files/db_recovery/working_passphrase_backup", NATIVE_MEDIA_INVENTORY_PATH)
    }
}

/** The accepted bytes of one merged source, checked against the custody payloads each cohort holds. */
private class MergedSourceAcknowledgement(
    private val receiptSha256: String,
    private val sourceCameraFiles: Map<String, String>,
    private val acceptedHashes: Map<String, String>,
    private val sourceReceipt: NativeJournalSourceReceipt,
    private val sourceMediaInventory: NativeJournalMediaInventory?,
    private val core: Map<String, String>,
    private val boundCorePhysicalPaths: Map<String, Set<String>>,
) {
    /** A cohort retires only when every payload it holds is an accepted byte revision of this source. */
    fun retires(payloads: List<NativeIncomingJournal.Payload>): Boolean {
        val fullSourcePresent = fullSourcePresent(payloads)
        val holdsAcceptedReceipt = holdsAcceptedReceipt(payloads)
        return payloads.isNotEmpty() &&
            payloads.all { payload -> retiresPayload(payload, fullSourcePresent, holdsAcceptedReceipt) }
    }

    private fun fullSourcePresent(payloads: List<NativeIncomingJournal.Payload>): Boolean =
        NATIVE_SOURCE_RECEIPT_PATH in core &&
            "files/logdate.preferences_pb" in core &&
            "database/logdate" in core &&
            core.all { (path, hash) ->
                payloads.any { payload ->
                    payload.complete &&
                        payload.path == NATIVE_JOURNAL_TRANSFER_PREFIX + path &&
                        retainedJournalChecksum(payload.file) == hash
                }
            }

    /** A repeated delivery carries the same receipt bytes in a cohort of its own. */
    private fun holdsAcceptedReceipt(payloads: List<NativeIncomingJournal.Payload>): Boolean =
        payloads.any {
            it.complete &&
                it.path == NATIVE_JOURNAL_TRANSFER_PREFIX + NATIVE_SOURCE_RECEIPT_PATH &&
                retainedJournalChecksum(it.file) == receiptSha256
        }

    private fun retiresPayload(
        payload: NativeIncomingJournal.Payload,
        fullSourcePresent: Boolean,
        holdsAcceptedReceipt: Boolean,
    ): Boolean {
        val path = payload.path.removePrefix(NATIVE_JOURNAL_TRANSFER_PREFIX)
        val accepted = acceptedHashes[if (path == NATIVE_SOURCE_RECEIPT_PATH) "source_scope.json" else path]
        val verifiedDirectCamera = isVerifiedDirectCamera(payload, path, accepted, holdsAcceptedReceipt)
        return payload.complete &&
            (
                payload.path.startsWith(NATIVE_JOURNAL_TRANSFER_PREFIX) ||
                    sourceCameraFiles[path] == payload.file.canonicalPath ||
                    verifiedDirectCamera
            ) &&
            accepted == retainedJournalChecksum(payload.file) &&
            (fullSourcePresent || payload.file.canonicalPath in boundCorePhysicalPaths[path].orEmpty())
    }

    /** A v4 camera original transferred in place matches the source's verified media inventory. */
    private fun isVerifiedDirectCamera(
        payload: NativeIncomingJournal.Payload,
        path: String,
        accepted: String?,
        holdsAcceptedReceipt: Boolean,
    ): Boolean =
        sourceReceipt.version == 4 &&
            path.startsWith("files/camera-captures/") &&
            payload.path == path &&
            holdsAcceptedReceipt &&
            sourceMediaInventory?.entry(path)?.let { entry ->
                entry.size == payload.expectedSize &&
                    entry.size == payload.file.length() &&
                    entry.sha256 == accepted &&
                    entry.sha256 == retainedJournalChecksum(payload.file)
            } == true

    companion object {
        /** Refuses a merge whose accepted bytes differ from any file the sealed source holds. */
        fun verify(
            receiptSha256: String,
            source: Map<String, File>,
            acceptedFiles: Map<String, File>,
            boundCorePhysicalPaths: Map<String, Set<String>>,
        ): MergedSourceAcknowledgement {
            val sourceCameraFiles =
                source.filterKeys { it.startsWith("files/camera-captures/") }.mapValues { (_, file) -> file.canonicalPath }
            val acceptedHashes = acceptedFiles.mapValues { (_, file) -> retainedJournalChecksum(file) }
            val sourceReceipt = Json.decodeFromString<NativeJournalSourceReceipt>(source.getValue("source_scope.json").readText())
            val sourceMediaInventory =
                sourceReceipt.mediaInventorySha256
                    ?.let { expected -> source[NATIVE_MEDIA_INVENTORY_PATH]?.let { verifyNativeJournalMediaInventory(it, expected) } }
            check(
                source.all { (path, file) ->
                    acceptedHashes[path] == retainedJournalChecksum(file)
                },
            ) { "Merged native source does not match accepted receipt bytes" }
            val core =
                acceptedHashes
                    .mapKeys { (path, _) -> if (path == "source_scope.json") NATIVE_SOURCE_RECEIPT_PATH else path }
                    .filterKeys { path ->
                        path == NATIVE_SOURCE_RECEIPT_PATH || path == "files/logdate.preferences_pb" || path.startsWith("database/")
                    }
            return MergedSourceAcknowledgement(
                receiptSha256,
                sourceCameraFiles,
                acceptedHashes,
                sourceReceipt,
                sourceMediaInventory,
                core,
                boundCorePhysicalPaths,
            )
        }
    }
}
