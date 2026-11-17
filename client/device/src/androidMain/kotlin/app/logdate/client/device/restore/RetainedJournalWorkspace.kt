package app.logdate.client.device.restore

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Commits a verified retained journal before a separately keyed working database can exist. */
class RetainedJournalWorkspace(
    private val stateFile: File,
    private val retainedDirectory: File,
    private val sources: () -> Map<String, File>,
    private val databasePaths: Set<String>,
    private val authorityFile: File,
    private val afterDurableStep: (String) -> Unit = {},
    private val beforePrepare: () -> Unit = {},
) {
    @Serializable
    enum class Phase { PREPARING, DETACHING, WORKING }

    @Serializable
    data class Session(
        val id: String,
        val phase: Phase,
        val sourcePaths: Set<String>,
        val version: Int = 1,
        val manifestSha256: String? = null,
        val priorGenerations: Map<String, String> = emptyMap(),
        val incomingCohorts: Map<String, String> = emptyMap(),
        val sourceInventorySha256: String? = null,
    ) {
        val primaryKeyName: String get() = "db_encryption_key.working.$id"
    }

    @Serializable
    private data class Entry(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    @Serializable
    private data class Manifest(
        val version: Int = 1,
        val entries: List<Entry>,
        val priorGenerations: Map<String, String> = emptyMap(),
        val incomingCohorts: Map<String, String> = emptyMap(),
        val entryCount: Long? = null,
        val inventorySha256: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun current(): Session? =
        synchronized(lock) {
            if (!stateFile.exists()) {
                null
            } else {
                json.decodeFromString<Session>(retainedJournalReadBounded(stateFile, MAX_SESSION_BYTES)).also(::validate)
            }
        }

    /** This is called only by an explicit retained-journal recovery action after startup failed. */
    fun prepare(): Session =
        synchronized(lock) {
            var previous = current()
            if (previous?.phase == Phase.WORKING && previous.incomingCohorts.values.any(String::isEmpty)) {
                // The native delivery gate is held by the recovery caller. Abandoned partial
                // inputs remain verified custody and cannot block preserving this working DB.
                finishIncomingDelivery()
                previous = current()
            }
            previous?.takeIf { it.phase != Phase.WORKING }?.let { return@synchronized finish(it) }
            val priorGenerations =
                previous
                    ?.let {
                        // The previous immutable manifest authenticates its inherited anchors. Do not
                        // discover or rehash earlier generations into new authority from mutable files.
                        readAnchoredManifest(it.id, checkNotNull(it.manifestSha256), it)
                        it.priorGenerations + (it.id to checkNotNull(it.manifestSha256))
                    }.orEmpty()
            beforePrepare()
            val input = existingSources()
            check(databasePaths.any { it in input }) { "There is no retained database to preserve" }
            check(previous?.incomingCohorts?.values?.none(String::isEmpty) != false) {
                "Incoming journal delivery must finish before creating another working generation"
            }
            val session =
                Session(
                    Uuid.random().toString(),
                    Phase.PREPARING,
                    input.keys.filterTo(mutableSetOf()) { it in databasePaths },
                    priorGenerations = priorGenerations,
                    incomingCohorts = previous?.incomingCohorts.orEmpty(),
                    sourceInventorySha256 = inventoryDigest(input.keys),
                )
            val serializedSession = serializeSession(session)
            if (previous?.phase == Phase.WORKING && canStageNativeDelivery()) {
                // Preserve the prior local admission before replacing its authority token. A
                // transferred marker or stale receipt cannot supply this proof.
                retainedJournalWriteAtomic(acceptedWorkingFile(), previous.id)
            }
            retainedJournalWriteAtomic(authorityFile, session.id)
            afterDurableStep("authority-committed")
            retainedJournalWriteAtomic(stateFile, serializedSession)
            afterDurableStep("preparing-committed")
            finish(session)
        }

    /** Startup calls this before resolving any database key or opening Room. */
    fun resume(): Session? =
        synchronized(lock) {
            current()?.let { return@synchronized finish(it) }
            if (retainedJournalHasInterruptedPreparation(authorityFile, retainedDirectory, acceptedWorkingFile())) prepare() else null
        }

    fun prepareNativeDelivery(): Boolean =
        synchronized(lock) {
            resume()
            canStageNativeDelivery()
        }

    fun canStageNativeDelivery(): Boolean =
        synchronized(lock) {
            val session = current() ?: return@synchronized false
            session.phase == Phase.WORKING &&
                (
                    retainedJournalMatchesLocalSession(authorityFile, session.id) ||
                        retainedJournalMatchesLocalSession(acceptedWorkingFile(), session.id)
                )
        }

    /** The database module calls this only after a real Room open and the privacy check succeed. */
    fun recordWorkingJournalOpened() =
        synchronized(lock) {
            val session = current() ?: return@synchronized
            check(session.phase == Phase.WORKING)
            if (!canStageNativeDelivery()) retainedJournalWriteAtomic(acceptedWorkingFile(), session.id)
        }

    private fun acceptedWorkingFile(): File = File(authorityFile.parentFile, ".retained_workspace_accepted")

    fun retainedSources(): Map<String, Map<String, File>> =
        synchronized(lock) {
            val session = current() ?: return@synchronized emptyMap()
            check(session.phase == Phase.WORKING)
            (session.priorGenerations + (session.id to checkNotNull(session.manifestSha256))).mapValues { (id, hash) ->
                val manifest = readAnchoredManifest(id, hash)
                manifest.entries.associate { it.path to safeFile(File(retainedDirectory, id), it.path) }
            }
        }

    fun beginIncomingDelivery(deliveryGeneration: String): String =
        synchronized(lock) {
            val session = checkNotNull(current())
            check(session.phase == Phase.WORKING)
            Uuid.parse(deliveryGeneration)
            val existing = session.incomingCohorts[deliveryGeneration]
            check(existing == null || existing.isEmpty()) { "Completed native delivery cannot accept more files" }
            if (existing == null) {
                // Reserve the final hash before accepting any bytes from this delivery generation.
                writeSession(session.copy(incomingCohorts = session.incomingCohorts + (deliveryGeneration to "")))
            }
            NativeIncomingJournal(File(retainedDirectory, "incoming")).begin(deliveryGeneration)
        }

    fun captureIncomingFile(
        id: String,
        path: String,
        size: Long,
        input: java.io.InputStream,
    ) = synchronized(lock) {
        val session = checkNotNull(current())
        check(session.phase == Phase.WORKING && session.incomingCohorts[id] == "")
        NativeIncomingJournal(File(retainedDirectory, "incoming")).capture(id, path, size, input)
    }

    fun finishIncomingDelivery() =
        synchronized(lock) {
            val session = current() ?: return@synchronized
            val incoming = NativeIncomingJournal(File(retainedDirectory, "incoming"))
            val completed =
                session.incomingCohorts.mapValues { (id, hash) ->
                    if (hash.isEmpty()) {
                        incoming.begin(id)
                        incoming.finish(id)
                    } else {
                        hash
                    }
                }
            if (completed != session.incomingCohorts) writeSession(session.copy(incomingCohorts = completed))
        }

    fun incomingPayloads(): Map<String, List<NativeIncomingJournal.Payload>> =
        synchronized(lock) {
            val session = current() ?: return@synchronized emptyMap()
            val incoming = NativeIncomingJournal(File(retainedDirectory, "incoming"))
            check(incoming.pending().isEmpty()) { "Native incoming delivery has not finished" }
            session.incomingCohorts.mapValues { (id, hash) -> incoming.verified(id, hash) }
        }

    @Suppress("unused") // Kept: the androidTest restore suites check that a delivery stays in verified custody with it
    fun coherentIncomingPayloads(): Map<String, Map<String, NativeIncomingJournal.Payload>> =
        synchronized(lock) {
            val session = current() ?: return@synchronized emptyMap()
            val incoming = NativeIncomingJournal(File(retainedDirectory, "incoming"))
            session.incomingCohorts
                .mapNotNull { (id, hash) ->
                    if (hash.isEmpty()) null else incoming.coherentVerified(id, hash)?.let { id to it }
                }.toMap()
        }

    fun requireBackupReady(
        deviceTransfer: Boolean,
        workingDatabase: File,
        workingPassphrase: File,
    ) {
        synchronized(lock) {
            val session = current() ?: return
            check(session.phase == Phase.WORKING) { "Retained journal activation must commit before native backup" }
            check(deviceTransfer) { "Retained journal custody must be preserved before replacing native cloud backup" }
            check(Files.isRegularFile(workingDatabase.toPath(), LinkOption.NOFOLLOW_LINKS) && workingDatabase.length() > 0)
            check(Files.isRegularFile(workingPassphrase.toPath(), LinkOption.NOFOLLOW_LINKS) && workingPassphrase.length() > 0)
            incomingPayloads()
            readAnchoredManifest(session.id, checkNotNull(session.manifestSha256), session)
            session.priorGenerations.forEach { (id, hash) ->
                val manifest = readAnchoredManifest(id, hash)
                check(manifest.priorGenerations.all { (ancestor, digest) -> session.priorGenerations[ancestor] == digest }) {
                    "Retained journal ancestry changed"
                }
            }
        }
    }

    private fun finish(initial: Session): Session {
        var session = initial
        if (session.phase != Phase.WORKING) {
            check(authorityFile.isFile && authorityFile.length() in 1..128 && authorityFile.readText() == session.id) {
                "A transferred retention marker cannot authorize removal of local journal files"
            }
        }
        if (session.phase == Phase.PREPARING) {
            if (!File(retainedDirectory, session.id).exists()) {
                val inventory = existingSources().keys
                check(inventory.any { it in databasePaths }) { "The original database must remain available until retention commits" }
                if (!matchesInventory(session, inventory)) {
                    session =
                        session.copy(
                            sourcePaths = inventory.filterTo(mutableSetOf()) { it in databasePaths },
                            sourceInventorySha256 = inventoryDigest(inventory),
                        )
                    writeSession(session)
                }
            }
            val directory = snapshot(session)
            session = session.copy(phase = Phase.DETACHING, manifestSha256 = retainedJournalChecksum(File(directory, "manifest.json")))
            writeSession(session)
            afterDurableStep("detaching-committed")
        }
        if (session.phase == Phase.DETACHING) {
            val directory = File(retainedDirectory, session.id)
            val manifestFile = File(directory, "manifest.json")
            check(retainedJournalChecksum(manifestFile) == session.manifestSha256) { "Retained journal manifest changed" }
            val manifest = readVerifiedManifest(directory, session)
            val current = sources()
            val database = manifest.entries.filter { it.path in databasePaths }
            // Validate the complete remaining source set before removing even one file.
            database.forEach { entry ->
                val source = checkNotNull(current[entry.path])
                if (source.exists()) check(matches(source, entry)) { "Original journal changed after retention" }
            }
            database.forEach { entry ->
                val source = checkNotNull(current[entry.path])
                if (source.exists()) {
                    Files.delete(source.toPath())
                    retainedJournalSyncDirectory(checkNotNull(source.parentFile))
                    afterDurableStep("detached:${entry.path}")
                }
            }
            session = session.copy(phase = Phase.WORKING)
            writeSession(session)
            afterDurableStep("working-committed")
        }
        return session
    }

    private fun snapshot(session: Session): File {
        val committed = File(retainedDirectory, session.id)
        if (committed.exists()) {
            readVerifiedManifest(committed, session)
            return committed
        }
        val input = existingSources()
        check(matchesInventory(session, input.keys)) { "Retained recovery source inventory changed" }
        retainedJournalDurableDirectory(retainedDirectory)
        val staging = File(retainedDirectory, ".${session.id}.preparing")
        if (staging.exists()) check(staging.deleteRecursively())
        retainedJournalDurableDirectory(staging)
        val ledger = File(staging, ".inventory")
        retainedJournalDurableDirectory(ledger)
        input.toSortedMap().forEach { (path, source) ->
            val target = safeFile(staging, path)
            retainedJournalDurableDirectory(checkNotNull(target.parentFile))
            FileOutputStream(target).use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            Files.setPosixFilePermissions(target.toPath(), Files.getPosixFilePermissions(source.toPath()))
            FileChannel.open(target.toPath(), StandardOpenOption.READ).use { it.force(true) }
            val entry = Entry(path, target.length(), retainedJournalChecksum(target))
            val encoded = json.encodeToString(entry).encodeToByteArray()
            check(encoded.size <= MAX_ENTRY_BYTES)
            retainedJournalWriteAtomic(File(ledger, pathDigest(path) + ".json"), encoded)
            afterDurableStep("copied:$path")
        }
        check(existingSources().keys == input.keys)
        val digest = MessageDigest.getInstance("SHA-256")
        ledgerFiles(ledger).forEach { record ->
            val entry = readEntry(record)
            check(matches(checkNotNull(input[entry.path]), entry)) { "Source changed while preserving the journal" }
            updateInventory(digest, record)
        }
        val manifest =
            Manifest(
                version = 2,
                entries = emptyList(),
                priorGenerations = session.priorGenerations,
                incomingCohorts = session.incomingCohorts,
                entryCount = input.size.toLong(),
                inventorySha256 = digestHex(digest),
            )
        val encoded = json.encodeToString(manifest).encodeToByteArray()
        check(encoded.size <= MAX_MANIFEST_BYTES)
        retainedJournalWriteAtomic(File(staging, "manifest.json"), encoded)
        staging.walkBottomUp().filter(File::isDirectory).forEach(::retainedJournalSyncDirectory)
        Files.move(staging.toPath(), committed.toPath(), StandardCopyOption.ATOMIC_MOVE)
        retainedJournalSyncDirectory(retainedDirectory)
        afterDurableStep("snapshot-committed")
        return committed
    }

    private fun inventoryDigest(paths: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths.sorted().forEach {
            digest.update(it.encodeToByteArray())
            digest.update(0.toByte())
        }
        return digestHex(digest)
    }

    private fun matchesInventory(
        session: Session,
        paths: Set<String>,
    ): Boolean = session.sourceInventorySha256?.let { it == inventoryDigest(paths) } ?: (session.sourcePaths == paths)

    private fun pathDigest(path: String): String =
        digestHex(MessageDigest.getInstance("SHA-256").apply { update(path.encodeToByteArray()) })

    private fun ledgerFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().sortedBy(File::getName).onEach {
            check(it.name.matches(Regex("[a-f0-9]{64}\\.json")))
            check(Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS))
        }

    private fun readEntry(file: File): Entry {
        val entry = json.decodeFromString<Entry>(retainedJournalReadBounded(file, MAX_ENTRY_BYTES))
        check(file.name == pathDigest(entry.path) + ".json")
        return entry
    }

    private fun updateInventory(
        digest: MessageDigest,
        record: File,
    ) {
        digest.update((record.name + ":" + retainedJournalChecksum(record) + "\n").encodeToByteArray())
    }

    private fun digestHex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    private fun readAnchoredManifest(
        id: String,
        expectedHash: String,
        session: Session? = null,
    ): Manifest {
        val directory = File(retainedDirectory, id)
        check(retainedJournalChecksum(File(directory, "manifest.json")) == expectedHash) { "Retained journal manifest changed" }
        return readVerifiedManifest(directory, session)
    }

    private fun readVerifiedManifest(
        directory: File,
        session: Session? = null,
    ): Manifest {
        val file = File(directory, "manifest.json")
        var manifest = json.decodeFromString<Manifest>(retainedJournalReadBounded(file, MAX_MANIFEST_BYTES))
        check(manifest.version in 1..2)
        if (manifest.version == 2) {
            check(manifest.entries.isEmpty() && manifest.entryCount != null && manifest.entryCount >= 1)
            val digest = MessageDigest.getInstance("SHA-256")
            val entries =
                ledgerFiles(File(directory, ".inventory")).map { record ->
                    updateInventory(digest, record)
                    readEntry(record)
                }
            check(entries.size.toLong() == manifest.entryCount && digestHex(digest) == manifest.inventorySha256)
            manifest = manifest.copy(entries = entries)
        }
        val paths = manifest.entries.map { it.path }.toSet()
        check(paths.size == manifest.entries.size && paths.any { it in databasePaths })
        validateAnchors(directory.name, manifest.priorGenerations)
        if (session != null) {
            check(matchesInventory(session, paths) && manifest.priorGenerations == session.priorGenerations)
            check(manifest.incomingCohorts.all { (id, hash) -> session.incomingCohorts[id] == hash })
        }
        manifest.entries.forEach { check(matches(safeFile(directory, it.path), it)) { "Retained journal bytes changed" } }
        return manifest
    }

    private fun existingSources(): Map<String, File> =
        sources().filterValues(File::exists).also { files ->
            files.forEach { (name, file) ->
                safeFile(retainedDirectory, name)
                check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Recovery source must be a regular file" }
            }
        }

    private fun matches(
        file: File,
        entry: Entry,
    ): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            file.length() == entry.size &&
            retainedJournalChecksum(file) == entry.sha256

    private fun validate(session: Session) {
        Uuid.parse(session.id)
        check(session.version == 1)
        check(session.sourceInventorySha256 == null || session.sourceInventorySha256.matches(Regex("[a-f0-9]{64}")))
        session.incomingCohorts.forEach { (id, hash) ->
            Uuid.parse(id)
            check(hash.isEmpty() || hash.matches(Regex("[a-f0-9]{64}")))
        }
        validateAnchors(session.id, session.priorGenerations)
        check(session.sourcePaths.size in 1..4096 && session.sourcePaths.any { it in databasePaths })
        session.sourcePaths.forEach { safeFile(retainedDirectory, it) }
        if (session.phase != Phase.PREPARING) check(session.manifestSha256?.matches(Regex("[a-f0-9]{64}")) == true)
    }

    private fun validateAnchors(
        currentId: String,
        anchors: Map<String, String>,
    ) {
        check(currentId !in anchors)
        anchors.forEach { (id, hash) ->
            Uuid.parse(id)
            check(hash.matches(Regex("[a-f0-9]{64}")))
        }
    }

    private fun safeFile(
        root: File,
        relative: String,
    ): File {
        check(relative.length in 1..1024 && !relative.startsWith('/') && relative.split('/').none { it == ".." || it.isEmpty() })
        return File(root, relative).also { check(it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) }
    }

    private fun serializeSession(session: Session): ByteArray {
        validate(session)
        val reserved = session.copy(incomingCohorts = session.incomingCohorts.mapValues { (_, hash) -> hash.ifEmpty { "0".repeat(64) } })
        check(json.encodeToString(reserved).encodeToByteArray().size <= MAX_SESSION_BYTES)
        val serialized = json.encodeToString(session).encodeToByteArray()
        check(serialized.size in 1..MAX_SESSION_BYTES) { "Retained journal state exceeds its readable size" }
        if (session.phase == Phase.PREPARING) {
            // Reserve the manifest hash before accepting a transaction that must later persist it.
            val detaching = session.copy(phase = Phase.DETACHING, manifestSha256 = "0".repeat(64))
            check(json.encodeToString(detaching).encodeToByteArray().size <= MAX_SESSION_BYTES) {
                "Retained journal state has no capacity for its committed manifest"
            }
        }
        return serialized
    }

    private fun writeSession(session: Session) = retainedJournalWriteAtomic(stateFile, serializeSession(session))

    private companion object {
        const val MAX_SESSION_BYTES = 64 * 1024
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_ENTRY_BYTES = 8192
        val lock = Any()
    }
}
