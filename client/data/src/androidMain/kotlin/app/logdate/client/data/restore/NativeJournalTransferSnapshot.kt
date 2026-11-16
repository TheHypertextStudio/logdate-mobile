package app.logdate.client.data.restore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.logdate.client.data.journals.toModel
import app.logdate.client.data.location.OfflineFirstLocationHistoryRepository
import app.logdate.client.data.notes.DatabaseNotePlaceResolver
import app.logdate.client.data.notes.JournalNoteQueries
import app.logdate.client.data.notes.drafts.AndroidLocalEntryDraftStore
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.NativeJournalSnapshotScope
import app.logdate.client.database.captureNativeJournalDatabaseSnapshot
import app.logdate.client.database.encryption.DatabasePassphraseProvider
import app.logdate.client.database.encryption.PassphraseBackupStore
import app.logdate.client.database.getDatabaseBuilder
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.database.protectDatabaseFile
import app.logdate.client.database.readNativeJournalSnapshotMediaReferences
import app.logdate.client.database.verifyNativeJournalDatabaseSnapshot
import app.logdate.client.datastore.DataStoreKeyValueStorage
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.createAndroidMainDataStore
import app.logdate.client.device.identity.DefaultCanonicalOwnerProvider
import app.logdate.client.device.restore.NATIVE_MEDIA_INVENTORY_PATH
import app.logdate.client.device.restore.NATIVE_SOURCE_RECEIPT_PATH
import app.logdate.client.device.restore.NativeJournalMediaInventory
import app.logdate.client.device.restore.NativeJournalSourceReceipt
import app.logdate.client.device.restore.NativeJournalTransferOwnership
import app.logdate.client.device.restore.retainedJournalPassphraseFile
import app.logdate.client.device.restore.retainedJournalWorkingKeyName
import app.logdate.client.device.restore.verifyNativeJournalMediaInventory
import app.logdate.client.device.restore.verifyNativeJournalSourceReceipt
import app.logdate.client.device.restore.writeNativeJournalMediaInventory
import app.logdate.client.device.restore.writeNativeJournalSourceReceipt
import app.logdate.client.device.storage.createAndroidSecureStorage
import app.logdate.client.domain.export.archive.StagingDirectoryArchiveContainer
import app.logdate.client.domain.restore.archive3.NativeArchive3Original
import app.logdate.client.domain.restore.archive3.NativeArchive3SnapshotInput
import app.logdate.client.domain.restore.archive3.NativeArchive3SnapshotWriter
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Place
import app.logdate.shared.model.profile.LogDateProfile
import app.logdate.shared.model.user.UserData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Owns exactly one callback-created immutable subtree, never a restored or abandoned subtree. */
internal class NativeJournalTransferSnapshot(
    private val context: Context,
) {
    private val root = File(context.filesDir, "native_journal_transfer")
    private val current = File(root, "current")
    private val ownership = NativeJournalTransferOwnership(File(context.noBackupFilesDir, "native-journal-sender-owner"), current)
    private var prepared = false
    private var selectedMedia: List<NativeJournalMediaInventory.Entry> = emptyList()
    private var entryDraftFileChecksum: String? = null
    private var preferencesChecksum: String? = null
    private var noDatabaseSource = false
    private var noDatabasePreferencesChecksum: String? = null
    private var noDatabaseDraftChecksum: String? = null

    fun prepare(includeProfileOnlySource: Boolean = false) {
        selectedMedia = emptyList()
        entryDraftFileChecksum = null
        preferencesChecksum = null
        noDatabaseSource = false
        noDatabasePreferencesChecksum = null
        noDatabaseDraftChecksum = null
        if (!context.getDatabasePath("logdate").exists() && prepareWithoutDatabase(includeProfileOnlySource)) return
        ownership.discardOwned()
        check(!current.exists()) { "Incoming native journal inputs must remain in custody" }
        check(root.mkdirs() || root.isDirectory)
        val staging = File(root, ".${Uuid.random()}.preparing")
        ownership.begin(staging)
        val target = File(staging, "database/logdate").apply { check(checkNotNull(parentFile).mkdirs()) }
        val keys = sourceKeyCandidates()
        try {
            pinSource(staging, target, keys)
        } finally {
            keys.forEach { it.fill(0) }
            if (staging.exists()) ownership.discardOwned()
        }
    }

    /** Returns true when the source holds no journal database, recording what it holds instead. */
    private fun prepareWithoutDatabase(includeProfileOnlySource: Boolean): Boolean {
        check(!current.exists()) { "The retained native journal must remain available for recovery" }
        val dataStore = createAndroidMainDataStore(context)
        if (includeProfileOnlySource) bindOwnerForProfileOnlySource(dataStore)
        val preferencesFile = File(context.filesDir, "logdate.preferences_pb")
        val draftFile = File(context.filesDir, "datastore/entry_drafts.preferences_pb")
        val beforePreferences = preferencesFile.takeIf(File::exists)?.let(::nativeSnapshotChecksum)
        val beforeDrafts = draftFile.takeIf(File::exists)?.let(::nativeSnapshotChecksum)
        val preferences = runBlocking { dataStore.data.first() }
        val drafts = runBlocking { AndroidLocalEntryDraftStore(context).getAllDrafts() }
        val selectedPaths =
            nativeTransferMediaPaths(
                nativeTransferDraftMediaReferences(preferences, drafts),
                context.packageName,
                context.filesDir,
            )
        prepareNativeDraftOnlyDatabase(context, ::initializeDraftOnlyDatabase) {
            drafts.isNotEmpty() ||
                (includeProfileOnlySource && NativeArchive3SourceCensus.hasPreDatabaseIosContent(preferences)) ||
                selectedPaths.any { path ->
                    File(context.filesDir, path.removePrefix("files/")).exists()
                }
        }
        check(beforePreferences == preferencesFile.takeIf(File::exists)?.let(::nativeSnapshotChecksum)) {
            "Preferences changed during native source selection"
        }
        check(beforeDrafts == draftFile.takeIf(File::exists)?.let(::nativeSnapshotChecksum)) {
            "Entry drafts changed during native source selection"
        }
        if (context.getDatabasePath("logdate").exists()) return false
        checkNoDatabaseSourceUnchanged(beforePreferences, beforeDrafts)
        noDatabaseSource = true
        noDatabasePreferencesChecksum = beforePreferences
        noDatabaseDraftChecksum = beforeDrafts
        return true
    }

    /** A profile-only source still needs its canonical owner before a journal database is created for it. */
    private fun bindOwnerForProfileOnlySource(dataStore: DataStore<Preferences>) {
        val existing = runBlocking { dataStore.data.first() }
        if (!NativeArchive3SourceCensus.hasPreDatabaseIosContent(existing)) return
        check(NativeArchive3SourceCensus.unsupportedPreferences(existing).isEmpty()) {
            "Native iOS transfer cannot retain these preferences"
        }
        runBlocking { DefaultCanonicalOwnerProvider(DataStoreKeyValueStorage(dataStore)).getCanonicalOwnerId() }
    }

    private fun pinSource(
        staging: File,
        target: File,
        keys: List<ByteArray>,
    ) {
        val (preferences, acceptedKey) = exportWithAcceptedKey(staging, target, keys)
        val entryDraftFile = File(context.filesDir, "datastore/entry_drafts.preferences_pb")
        val beforeDrafts = entryDraftFile.takeIf(File::exists)?.let(::nativeSnapshotChecksum)
        val entryDrafts = runBlocking { AndroidLocalEntryDraftStore(context).getAllDrafts() }
        check(beforeDrafts == entryDraftFile.takeIf(File::exists)?.let(::nativeSnapshotChecksum)) {
            "Entry drafts changed during native media selection"
        }
        val references =
            readNativeJournalSnapshotMediaReferences(target, acceptedKey) +
                nativeTransferDraftMediaReferences(preferences, entryDrafts)
        val selectedPaths = nativeTransferMediaPaths(references, context.packageName, context.filesDir)
        val mediaInventory = File(staging, NATIVE_MEDIA_INVENTORY_PATH)
        writeNativeJournalMediaInventory(mediaInventory, context.filesDir, selectedPaths)
        val inventory = verifyNativeJournalMediaInventory(mediaInventory, nativeSnapshotChecksum(mediaInventory))
        requireSelectedOriginalsInventoried(selectedPaths, inventory)
        val receipt = File(staging, NATIVE_SOURCE_RECEIPT_PATH)
        val pinnedPreferences = File(staging, "files/logdate.preferences_pb")
        val pinnedEntryDrafts = pinEntryDrafts(staging, entryDraftFile, beforeDrafts)
        writeNativeJournalSourceReceipt(
            receipt,
            preferences,
            pinnedPreferences,
            mapOf("database/logdate" to target),
            mediaInventory = mediaInventory,
            entryDraftsFile = pinnedEntryDrafts,
        )
        val selectedPreferencesChecksum = nativeSnapshotChecksum(pinnedPreferences)
        requirePinnedNativeTransferFile(pinnedPreferences, selectedPreferencesChecksum)
        staging.walkBottomUp().filter(File::isDirectory).forEach(::syncDirectory)
        ownership.record(staging)
        Files.move(staging.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(root)
        prepared = true
        selectedMedia = inventory.files
        entryDraftFileChecksum = entryDraftFile.takeIf(File::exists)?.let(::nativeSnapshotChecksum)
        preferencesChecksum = selectedPreferencesChecksum
        check(entryDraftFileChecksum == beforeDrafts) { "Entry drafts changed during native media selection" }
        checkPinnedPreferencesUnchanged()
    }

    private fun requireSelectedOriginalsInventoried(
        selectedPaths: Collection<String>,
        inventory: NativeJournalMediaInventory,
    ) {
        selectedPaths.forEach { path ->
            val source = File(context.filesDir, path.removePrefix("files/"))
            if (path.startsWith("files/camera-captures/")) {
                check(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "Referenced camera original is missing from the native source"
                }
            }
            if (Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                check(inventory.entry(path) != null) { "Referenced native original could not be captured" }
            }
        }
    }

    private fun pinEntryDrafts(
        staging: File,
        entryDraftFile: File,
        beforeDrafts: String?,
    ): File? =
        entryDraftFile.takeIf(File::isFile)?.let { source ->
            val destination = File(staging, "files/datastore/entry_drafts.preferences_pb")
            check(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true)
            Files.copy(source.toPath(), destination.toPath(), LinkOption.NOFOLLOW_LINKS)
            requirePinnedNativeTransferFile(destination, checkNotNull(beforeDrafts))
            destination
        }

    fun finish() {
        if (noDatabaseSource) {
            checkNoDatabaseSourceUnchanged(noDatabasePreferencesChecksum, noDatabaseDraftChecksum)
        }
        if (prepared) ownership.discardOwned()
        prepared = false
        noDatabaseSource = false
        selectedMedia = emptyList()
        entryDraftFileChecksum = null
        preferencesChecksum = null
        noDatabasePreferencesChecksum = null
        noDatabaseDraftChecksum = null
    }

    fun backupSelectedMedia(emit: (File) -> Unit) {
        if (!prepared) {
            if (noDatabaseSource) checkNoDatabaseSourceUnchanged(noDatabasePreferencesChecksum, noDatabaseDraftChecksum)
            return
        }
        checkPinnedPreferencesUnchanged()
        selectedMedia.forEach { entry ->
            val file = File(context.filesDir, entry.path.removePrefix("files/"))
            emitVerifiedNativeMedia(file, entry, emit)
        }
        checkPinnedPreferencesUnchanged()
    }

    /** A never-used installation has no Apple payload; do not manufacture a journal or identity for it. */
    fun skipEmptyIosTransfer(): Boolean {
        if (!noDatabaseSource) return false
        checkNoDatabaseSourceUnchanged(noDatabasePreferencesChecksum, noDatabaseDraftChecksum)
        val preferences = runBlocking { createAndroidMainDataStore(context).data.first() }
        check(
            NativeArchive3SourceCensus.unsupportedPreferences(preferences).isEmpty() &&
                !NativeArchive3SourceCensus.hasPreDatabaseIosContent(preferences) &&
                NativeArchive3SourceCensus.hasOnlyEmptyDraftState(preferences),
        ) { "Database-free source contains content that cannot be skipped" }
        checkNoDatabaseSourceUnchanged(noDatabasePreferencesChecksum, noDatabaseDraftChecksum)
        return true
    }

    /** Produces an admitted major-3 directory from the callback's already fenced native snapshot. */
    suspend fun writeArchive3(container: StagingDirectoryArchiveContainer) {
        check(prepared && !noDatabaseSource)
        val files = checkNotNull(validateNativeJournalTransfer(context))
        val databaseFile = checkNotNull(files["database/logdate"])
        val preferencesFile = checkNotNull(files["files/logdate.preferences_pb"])
        val keyFile = listOf("passphrase_backup", "working_passphrase_backup").mapNotNull { files["files/db_recovery/$it"] }.single()
        val passphrase = Base64.decode(keyFile.readText().trim())
        val databaseKey = passphrase.copyOf()
        val readWorkspace = File(context.filesDir, "logdate-os-transfer/.read")
        val readDatabase = File(readWorkspace, "logdate")
        val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var openedDatabase: LogDateDatabase? = null
        try {
            try {
                check(!readWorkspace.exists()) { "Native archive reader already exists" }
                val pinnedChecksum = nativeSnapshotChecksum(databaseFile)
                copyDurably(databaseFile, readDatabase)
                requirePinnedNativeTransferFile(databaseFile, pinnedChecksum)
                requirePinnedNativeTransferFile(readDatabase, pinnedChecksum)
                val pinnedPreferences =
                    PreferenceDataStoreFactory.createWithPath(scope = preferenceScope, produceFile = { preferencesFile.path.toPath() })
                val database = getRoomDatabase(getDatabaseBuilder(context, databaseKey, readDatabase), driver = null)
                openedDatabase = database
                val input = sourceArchiveInput(database, pinnedPreferences, files, databaseFile)
                NativeArchive3SnapshotWriter.write(input, container)
                checkNotNull(validateNativeJournalTransfer(context))
            } finally {
                try {
                    openedDatabase?.close()
                } finally {
                    try {
                        check(readWorkspace.deleteRecursively()) { "Native archive reader could not be removed" }
                    } finally {
                        preferenceScope.cancel()
                        databaseKey.fill(0)
                        passphrase.fill(0)
                    }
                }
            }
        } catch (failure: Throwable) {
            runCatching { container.discard() }
            throw failure
        }
    }

    private suspend fun sourceArchiveInput(
        database: LogDateDatabase,
        pinnedPreferences: DataStore<Preferences>,
        files: Map<String, File>,
        databaseFile: File,
    ): NativeArchive3SnapshotInput {
        val unsupported = NativeArchive3SourceCensus.inspect(database.openHelper.readableDatabase).toMutableSet()
        val prefs = pinnedPreferences.data.first()
        unsupported += NativeArchive3SourceCensus.unsupportedPreferences(prefs)
        val receipt = Json.decodeFromString<NativeJournalSourceReceipt>(checkNotNull(files[NATIVE_SOURCE_RECEIPT_PATH]).readText())
        val drafts = archiveDrafts(prefs, receipt)
        val journals = database.journalDao().getAll()
        if (journals.any { it.deletedAt != null || it.coverImageUri != null }) unsupported += "journalCoversOrDeletion"
        val queries = journalNoteQueries(database)
        val notes = queries.allNotesObserved.first()
        val noteLocationMetadata = NativeArchive3SourceCensus.noteLocationMetadata(database.openHelper.readableDatabase)
        val semanticPlaces = NativeArchive3SourceCensus.semanticPlaceRows(database.openHelper.readableDatabase)
        val links = queries.getAllJournalNoteLinks().groupBy({ it.second }, { it.first })
        val userData = LogdatePreferencesDataSource(pinnedPreferences).userData.first()
        if (userData.profilePhotoUri != null) unsupported += "profilePhoto"
        val (places, placeSourceTimes) = archivePlaces(database)
        return NativeArchive3SnapshotInput(
            exportedAt = Clock.System.now().toString(),
            journals = journals.map { it.toModel() },
            notes = notes,
            noteSourceLocationMetadata = noteLocationMetadata,
            journalIdsByNote = links,
            drafts = drafts,
            profile = archive3Profile(userData),
            places = places,
            placeSourceTimes = placeSourceTimes,
            semanticPlaces = semanticPlaces,
            locationHistory = OfflineFirstLocationHistoryRepository(database.locationHistoryDao()).getAllLocationHistory(),
            originals = archiveOriginals(notes, drafts),
            unsupportedPopulatedCategories = unsupported,
            validateContext = archive3ContextValidator(databaseFile),
        )
    }

    private fun journalNoteQueries(database: LogDateDatabase) =
        JournalNoteQueries(
            database.textNoteDao(),
            database.imageNoteDao(),
            database.audioNoteDao(),
            database.videoNoteDao(),
            database.journalContentDao(),
            database.mediaCaptionDao(),
            DatabaseNotePlaceResolver(database.placeDao()),
            null,
        )

    private fun archive3Profile(userData: UserData) =
        LogDateProfile(
            displayName = userData.displayName,
            birthday = userData.birthday.takeIf { it != kotlin.time.Instant.DISTANT_PAST },
            bio = userData.bio,
            originalBio = userData.originalBio,
            createdAt = userData.profileCreatedAt,
            lastUpdatedAt = userData.profileLastUpdatedAt,
        )

    private fun archive3ContextValidator(databaseFile: File): () -> Unit {
        val marker = File(context.noBackupFilesDir, "native-journal-sender-owner")
        val markerHash = nativeSnapshotChecksum(marker)
        return {
            checkPinnedPreferencesUnchanged()
            checkEntryDraftFileUnchanged()
            requirePinnedNativeTransferFile(marker, markerHash)
            check(current.isDirectory && databaseFile.exists())
        }
    }

    private suspend fun archiveDrafts(
        prefs: Preferences,
        receipt: NativeJournalSourceReceipt,
    ): List<EditorDraft> {
        val entryDrafts = AndroidLocalEntryDraftStore(context).getAllDrafts()
        val modernDrafts =
            NativeArchive3EditorDrafts.read(
                prefs,
                receipt.canonicalOwnerId,
                receipt.canonicalOwnerBound == "true",
                receipt.backendOrigin,
            )
        val drafts = modernDrafts + NativeArchive3EntryDrafts.convert(entryDrafts, receipt.canonicalOwnerBound == "true")
        check(drafts.map { it.id }.distinct().size == drafts.size) { "Draft identities conflict across source stores" }
        return drafts
    }

    private suspend fun archivePlaces(database: LogDateDatabase): Pair<List<Place.UserDefined>, Map<Uuid, Pair<Long, Long>>> {
        val placeRows = database.userPlaceDao().getAllPlaces()
        val places =
            placeRows.map { row ->
                Place.UserDefined(
                    id = Uuid.parse(row.id),
                    displayName = row.name,
                    lat = row.latitude,
                    lng = row.longitude,
                    radiusMeters = row.radiusMeters,
                    description = row.description,
                )
            }
        val placeSourceTimes = placeRows.associate { Uuid.parse(it.id) to (it.createdAt to it.updatedAt) }
        check(placeSourceTimes.size == placeRows.size)
        return places to placeSourceTimes
    }

    private fun archiveOriginals(
        notes: List<JournalNote>,
        drafts: List<EditorDraft>,
    ): List<NativeArchive3Original> {
        val mediaByPath = selectedMedia.associateBy { it.path }
        val originalReferences = (notes.mapNotNull { it.mediaRefOrNull() } + drafts.flatMap { it.lateMediaReferences() }).distinct()
        val originals =
            originalReferences.map { reference ->
                val paths = nativeTransferMediaPaths(setOf(reference), context.packageName, context.filesDir)
                check(paths.size == 1)
                val entry = checkNotNull(mediaByPath[paths.single()]) { "Native original was not pinned" }
                val file = File(context.filesDir, entry.path.removePrefix("files/"))
                NativeArchive3Original(reference, entry.size, entry.sha256) {
                    verifySelectedMediaFile(file, entry)
                    file.source()
                }
            }
        return originals
    }

    fun pinnedSourceIdentity(): Pair<NativeJournalSourceReceipt, ByteArray> {
        check(prepared && !noDatabaseSource)
        val receiptFile = checkNotNull(validateNativeJournalTransfer(context))[NATIVE_SOURCE_RECEIPT_PATH]
        val bytes = checkNotNull(receiptFile).readBytes()
        return Json.decodeFromString<NativeJournalSourceReceipt>(bytes.decodeToString()) to
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private fun checkPinnedPreferencesUnchanged() {
        requirePinnedNativeTransferFile(File(current, "files/logdate.preferences_pb"), checkNotNull(preferencesChecksum))
    }

    private fun checkNoDatabaseSourceUnchanged(
        expectedPreferences: String?,
        expectedDrafts: String?,
    ) {
        val database = context.getDatabasePath("logdate")
        check(listOf("", "-wal", "-shm", "-journal").none { File(database.path + it).exists() }) {
            "Journal appeared during database-free native transfer"
        }
        check(File(context.filesDir, "logdate.preferences_pb").takeIf(File::exists)?.let(::nativeSnapshotChecksum) == expectedPreferences) {
            "Preferences changed during database-free native transfer"
        }
        check(
            File(context.filesDir, "datastore/entry_drafts.preferences_pb")
                .takeIf(File::exists)
                ?.let(::nativeSnapshotChecksum) == expectedDrafts,
        ) { "Entry drafts changed during database-free native transfer" }
    }

    private fun checkEntryDraftFileUnchanged() {
        val file = File(context.filesDir, "datastore/entry_drafts.preferences_pb")
        check(file.takeIf(File::exists)?.let(::nativeSnapshotChecksum) == entryDraftFileChecksum) {
            "Entry drafts changed during native transfer"
        }
    }

    private fun exportWithAcceptedKey(
        staging: File,
        target: File,
        keys: List<ByteArray>,
    ): Pair<Preferences, ByteArray> {
        var failure: Exception? = null
        for (key in keys) {
            try {
                val preferences =
                    captureNativeJournalDatabaseSnapshot(context.getDatabasePath("logdate"), key, target) { writeScope ->
                        // SQL -> DataStore is the same order used by scoped journal/draft writes.
                        // DataStore may run its transform on another thread; write the SQL scope only after it returns.
                        capturePreferencesOnSqlThread(
                            capture = {
                                createAndroidMainDataStore(context).updateData { snapshot ->
                                    copyDurably(
                                        File(context.filesDir, "logdate.preferences_pb"),
                                        File(staging, "files/logdate.preferences_pb"),
                                    )
                                    snapshot
                                }
                            },
                            write = { snapshot ->
                                val scope =
                                    checkNotNull(
                                        RetainedJournalSourceScope.from(
                                            snapshot,
                                            app.logdate.shared.config.DefaultLogDateConfigRepository.DEFAULT_BACKEND_URL,
                                        ),
                                    )
                                writeScope(
                                    NativeJournalSnapshotScope(
                                        checkNotNull(scope.ownerId),
                                        scope.bound,
                                        scope.backendOrigin,
                                        nativeSnapshotChecksum(File(staging, "files/logdate.preferences_pb")),
                                    ),
                                )
                                val portable = File(staging, "files/db_recovery/${retainedJournalPassphraseFile(context).name}")
                                writeDurably(portable, Base64.encode(key).encodeToByteArray())
                            },
                        )
                    }
                return preferences to key
            } catch (error: Exception) {
                failure = error
                listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
            }
        }
        throw IOException("The source journal could not produce a verified native snapshot", failure)
    }

    private fun sourceKeyCandidates(): List<ByteArray> {
        val primary =
            runBlocking {
                createAndroidSecureStorage(context).getString(retainedJournalWorkingKeyName(context))
            }?.let { runCatching { Base64.decode(it) }.getOrNull() }
        return (listOfNotNull(primary) + PassphraseBackupStore(context) { retainedJournalPassphraseFile(context) }.readCandidates())
            .filter { it.size == 32 }
            .distinctBy { Base64.encode(it) }
    }

    private fun initializeDraftOnlyDatabase() {
        val provider =
            DatabasePassphraseProvider(
                createAndroidSecureStorage(context),
                PassphraseBackupStore(context) { retainedJournalPassphraseFile(context) },
                storageKey = { retainedJournalWorkingKeyName(context) },
                hasExistingDatabase = { context.getDatabasePath("logdate").exists() },
            )
        val candidates = runBlocking { provider.getPassphraseCandidates() }
        val selected = candidates.first()
        try {
            val database = getRoomDatabase(getDatabaseBuilder(context, selected.copyOf()), driver = null)
            try {
                database.openHelper.writableDatabase
                protectDatabaseFile(context)
                runBlocking { provider.confirmOpenedDatabasePassphrase(selected) }
            } finally {
                database.close()
            }
        } finally {
            candidates.forEach { it.fill(0) }
        }
    }
}

/** Waits for DataStore while SQL is held, then resumes the SQL write on its transaction thread. */
internal fun <T> capturePreferencesOnSqlThread(
    capture: suspend () -> T,
    write: (T) -> Unit,
): T {
    val owner = Thread.currentThread()
    val value = runBlocking { withTimeout(15_000) { capture() } }
    check(Thread.currentThread() === owner) { "Native SQL transaction changed thread" }
    write(value)
    return value
}

internal fun validateNativeJournalTransfer(context: Context): Map<String, File>? {
    val current = File(context.filesDir, "native_journal_transfer/current")
    if (!current.exists()) return null
    val files = current.walkTopDown().filter(File::isFile).associateBy { it.relativeTo(current).invariantSeparatorsPath }
    val database = checkNotNull(files["database/logdate"])
    check(files.keys.none { it in setOf("database/logdate-wal", "database/logdate-shm", "database/logdate-journal") })
    val receipt = checkNotNull(files[NATIVE_SOURCE_RECEIPT_PATH])
    val preferences = checkNotNull(files["files/logdate.preferences_pb"])
    verifyNativeJournalSourceReceipt(receipt, preferences, mapOf("database/logdate" to database))
    val sourceReceipt = Json.decodeFromString<NativeJournalSourceReceipt>(receipt.readText())
    sourceReceipt.entryDraftsSha256?.let { expected ->
        val drafts = checkNotNull(files["files/datastore/entry_drafts.preferences_pb"])
        requirePinnedNativeTransferFile(drafts, expected)
    }
    val scratch = File(context.noBackupFilesDir, "native-journal-scope-validation").apply { check(mkdirs() || isDirectory) }
    val scope =
        runBlocking {
            checkNotNull(readRetainedJournalSourceScope(preferences, receipt, scratch, mapOf("database/logdate" to database)))
        }
    val selectedKeys = listOf("passphrase_backup", "working_passphrase_backup").mapNotNull { files["files/db_recovery/$it"] }
    if (selectedKeys.isEmpty()) throw MissingNativeJournalTransferKeyException()
    val key = selectedKeys.single()
    val decoded = Base64.decode(key.readText().trim())
    try {
        check(
            verifyNativeJournalDatabaseSnapshot(database, decoded) ==
                NativeJournalSnapshotScope(
                    checkNotNull(scope.ownerId),
                    scope.bound,
                    scope.backendOrigin,
                    nativeSnapshotChecksum(preferences),
                ),
        ) { "Encrypted source scope does not match native preferences" }
    } finally {
        decoded.fill(0)
    }
    return files
}

private fun copyDurably(
    source: File,
    target: File,
) {
    check(Files.isRegularFile(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    check(checkNotNull(target.parentFile).let { it.mkdirs() || it.isDirectory })
    source.inputStream().use { input ->
        FileOutputStream(target).use { output ->
            input.copyTo(output)
            output.fd.sync()
        }
    }
}

private fun verifySelectedMediaFile(
    file: File,
    entry: NativeJournalMediaInventory.Entry,
) {
    check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    check(file.length() == entry.size && nativeSnapshotChecksum(file) == entry.sha256) {
        "Native original changed during device transfer"
    }
}

internal fun emitVerifiedNativeMedia(
    file: File,
    entry: NativeJournalMediaInventory.Entry,
    emit: (File) -> Unit,
) {
    verifySelectedMediaFile(file, entry)
    emit(file)
    verifySelectedMediaFile(file, entry)
}

internal fun requirePinnedNativeTransferFile(
    file: File,
    pinnedSha256: String,
) {
    check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    check(nativeSnapshotChecksum(file) == pinnedSha256) { "Native transfer source changed after snapshot selection" }
}

private fun writeDurably(
    target: File,
    bytes: ByteArray,
) {
    check(checkNotNull(target.parentFile).let { it.mkdirs() || it.isDirectory })
    FileOutputStream(target).use {
        it.write(bytes)
        it.fd.sync()
    }
}

private fun syncDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

internal fun nativeSnapshotChecksum(file: File): String =
    file.inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
