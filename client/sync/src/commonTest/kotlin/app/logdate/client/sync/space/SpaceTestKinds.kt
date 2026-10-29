package app.logdate.client.sync.space

import app.logdate.client.media.InMemoryMediaManager
import app.logdate.client.media.MediaPayload
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.repository.transcription.TranscriptDocumentStatus
import app.logdate.client.sync.cloud.DefaultCloudMediaDataSource
import app.logdate.client.sync.conflict.ConflictResolution
import app.logdate.client.sync.conflict.ConflictResolver
import app.logdate.client.sync.conflict.LastWriteWinsResolver
import app.logdate.client.sync.crypto.AesGcmMediaPayloadCrypto
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadInboxTest
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.space.mappers.SpaceJournal
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.FakeJournalRepository
import app.logdate.client.sync.test.FakeSessionStorage
import app.logdate.client.sync.test.FakeSyncMetadataService
import app.logdate.client.sync.test.InMemoryMediaSyncRefStore
import app.logdate.client.sync.test.InMemorySyncDeadLetterStore
import app.logdate.client.sync.test.InMemorySyncRetryScheduleStore
import app.logdate.client.sync.test.fakeCloudApiClient
import app.logdate.client.sync.test.testDefaultSyncManager
import app.logdate.lexicon.app.logdate.DefsMedia
import app.logdate.lexicon.app.logdate.crypto.SealedPayload
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.Journal
import app.logdate.space.SealedRecordCodec
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import studio.hypertext.atproto.lexicon.data.LexiconData
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal const val ENTRY_COLLECTION = "app.logdate.journal.entry"
internal const val TRANSCRIPT_COLLECTION = "app.logdate.journal.transcript"

/** A minimal journal mapping for tests: title, description and timestamps. */
internal object TestJournalMapper : SpaceRecordMapper<SpaceJournal> {
    override val collection = JOURNAL_COLLECTION

    override fun rkeyOf(value: SpaceJournal): String = value.journal.id.toString()

    override fun toRecord(value: SpaceJournal): JsonObject =
        buildJsonObject {
            put("\$type", JOURNAL_COLLECTION)
            put("title", value.journal.title)
            if (value.journal.description.isNotEmpty()) put("description", value.journal.description)
            put("createdAt", value.journal.created.toDatetime("$JOURNAL_COLLECTION createdAt"))
            put("updatedAt", value.journal.lastUpdated.toDatetime("$JOURNAL_COLLECTION updatedAt"))
        }

    override fun fromRecord(
        rkey: String,
        record: JsonObject,
    ): SpaceJournal {
        val title =
            (record["title"] as? JsonPrimitive)?.contentOrNull ?: throw SpaceRecordMappingException("$JOURNAL_COLLECTION/$rkey: no title")
        val created = parseDatetime("$JOURNAL_COLLECTION/$rkey createdAt", record.getValue("createdAt").jsonPrimitive.content)
        return SpaceJournal(
            Journal(
                id = parseUuidKey(JOURNAL_COLLECTION, rkey),
                title = title,
                description = record["description"]?.jsonPrimitive?.content.orEmpty(),
                created = created,
                lastUpdated =
                    record["updatedAt"]?.let { parseDatetime("$JOURNAL_COLLECTION/$rkey updatedAt", it.jsonPrimitive.content) } ?: created,
            ),
        )
    }
}

internal fun journalKind(
    journals: JournalRepository,
    resolver: ConflictResolver<Journal> = LastWriteWinsResolver(),
): SpaceKind<SpaceJournal> =
    SpaceKind.single(
        EntityType.JOURNAL,
        TestJournalMapper,
        TestJournalRecords(journals, resolver),
    ) { item, version -> item.copy(journal = item.journal.copy(syncVersion = version)) }

internal class TestJournalRecords(
    private val journals: JournalRepository,
    resolver: ConflictResolver<Journal> = LastWriteWinsResolver(),
) : SpaceLocalRecords<SpaceJournal> {
    override val conflictResolver: ConflictResolver<SpaceJournal> =
        object : ConflictResolver<SpaceJournal> {
            override fun resolve(
                local: SpaceJournal,
                remote: SpaceJournal,
                localTimestamp: Instant,
                remoteTimestamp: Instant,
            ): ConflictResolution<SpaceJournal> =
                when (val resolution = resolver.resolve(local.journal, remote.journal, localTimestamp, remoteTimestamp)) {
                    is ConflictResolution.KeepLocal -> ConflictResolution.KeepLocal(SpaceJournal(resolution.value))
                    is ConflictResolution.KeepRemote -> ConflictResolution.KeepRemote(SpaceJournal(resolution.value))
                    is ConflictResolution.Merge -> ConflictResolution.Merge(SpaceJournal(resolution.merged))
                    is ConflictResolution.RequiresManualResolution ->
                        ConflictResolution.RequiresManualResolution(
                            SpaceJournal(resolution.local),
                            SpaceJournal(resolution.remote),
                            resolution.reason,
                        )
                }

            override fun resolveDeletion(
                remote: SpaceJournal,
                remoteTimestamp: Instant,
            ) = resolver.resolveDeletion(remote.journal, remoteTimestamp)
        }

    override suspend fun load(entityId: String): SpaceJournal? =
        Uuid.parseOrNull(entityId)?.let { journals.getJournalById(it) }?.let { SpaceJournal(it) }

    override fun lastUpdatedOf(item: SpaceJournal): Instant = item.journal.lastUpdated

    override suspend fun acknowledgeUpload(
        item: SpaceJournal,
        seq: Long,
        syncedAt: Instant,
    ) {
        journals.update(item.journal.copy(syncVersion = seq))
    }

    override suspend fun replaceLocal(item: SpaceJournal) {
        if (journals.getJournalById(item.journal.id) == null) journals.create(item.journal) else journals.update(item.journal)
    }
}

/** A recording's transcript, with the note it belongs to. */
internal data class TestTranscript(
    val noteId: Uuid,
    val document: TranscriptDocument,
)

/**
 * Text and audio notes as single-block entries, for tests. An audio note's file is named by its
 * personal-space reference, and its transcript is a separate record keyed `<note>:<note>`.
 */
internal object TestEntryMapper : SpaceRecordMapper<JournalNote> {
    override val collection = ENTRY_COLLECTION

    override fun rkeyOf(value: JournalNote): String = value.uid.toString()

    override fun toRecord(value: JournalNote): JsonObject {
        val owner = "$collection/${value.uid}"
        val block =
            when (value) {
                is JournalNote.Text ->
                    buildJsonObject {
                        put("\$type", "$ENTRY_COLLECTION#textBlock")
                        put("id", value.uid.toString())
                        put("text", value.content)
                    }
                is JournalNote.Audio ->
                    buildJsonObject {
                        put("\$type", "$ENTRY_COLLECTION#audioBlock")
                        put("id", value.uid.toString())
                        put("media", encodeRecord(DefsMedia.serializer(), SpaceMediaResolver.mediaFor(value.mediaRef, owner)))
                        put("durationMs", value.durationMs)
                        if (value.transcript != null) {
                            put(
                                "transcript",
                                encodeRecord(
                                    app.logdate.lexicon.app.logdate.DefsRecordRef
                                        .serializer(),
                                    recordRef(TRANSCRIPT_COLLECTION, value.uid).copy(rkey = transcriptKey(value.uid)),
                                ),
                            )
                        }
                    }
                else -> throw SpaceRecordMappingException("$owner: tests map only text and audio notes")
            }
        return buildJsonObject {
            put("\$type", ENTRY_COLLECTION)
            put("blocks", buildJsonArray { add(block) })
            put("createdAt", value.creationTimestamp.toDatetime("$owner createdAt"))
            put("updatedAt", value.lastUpdated.toDatetime("$owner updatedAt"))
        }
    }

    override fun fromRecord(
        rkey: String,
        record: JsonObject,
    ): JournalNote {
        val owner = "$collection/$rkey"
        val uid = parseUuidKey(collection, rkey)
        val block =
            (record["blocks"] as? JsonArray)?.singleOrNull() as? JsonObject ?: throw SpaceRecordMappingException("$owner: not one block")
        val created = parseDatetime("$owner createdAt", record.getValue("createdAt").jsonPrimitive.content)
        val updated = record["updatedAt"]?.let { parseDatetime("$owner updatedAt", it.jsonPrimitive.content) } ?: created
        return when ((block["\$type"] as? JsonPrimitive)?.contentOrNull) {
            "$ENTRY_COLLECTION#textBlock" ->
                JournalNote.Text(
                    uid = uid,
                    creationTimestamp = created,
                    lastUpdated = updated,
                    content = block.getValue("text").jsonPrimitive.content,
                )
            "$ENTRY_COLLECTION#audioBlock" -> {
                val media = LexiconData.json.decodeFromJsonElement(DefsMedia.serializer(), block.getValue("media"))
                JournalNote.Audio(
                    uid = uid,
                    creationTimestamp = created,
                    lastUpdated = updated,
                    mediaRef = SpaceMediaResolver.downloaded(uid, media),
                    durationMs = block.getValue("durationMs").jsonPrimitive.longOrNull ?: 0,
                )
            }
            else -> throw SpaceRecordMappingException("$owner: a block type tests don't map")
        }
    }

    override fun blobCidsOf(value: JournalNote): List<String> =
        (value as? JournalNote.Audio)?.let { listOf(SpaceMediaResolver.blobCidFor(it.mediaRef, "$collection/${value.uid}")) }.orEmpty()
}

internal fun transcriptKey(noteId: Uuid): String = "$noteId:$noteId"

/** Transcripts with their revision, language and whether they're final; enough to tell versions apart. */
internal object TestTranscriptMapper : SpaceRecordMapper<TestTranscript> {
    override val collection = TRANSCRIPT_COLLECTION

    override fun rkeyOf(value: TestTranscript): String = transcriptKey(value.noteId)

    override fun toRecord(value: TestTranscript): JsonObject =
        buildJsonObject {
            put("\$type", TRANSCRIPT_COLLECTION)
            put(
                "entry",
                encodeRecord(
                    app.logdate.lexicon.app.logdate.DefsRecordRef
                        .serializer(),
                    recordRef(ENTRY_COLLECTION, value.noteId),
                ),
            )
            put("blockId", value.noteId.toString())
            put("language", value.document.language)
            put("revision", value.document.revision.toLong())
            put("status", if (value.document.status == TranscriptDocumentStatus.FINAL) "complete" else "inProgress")
            put("createdAt", "2026-10-01T09:00:00Z")
        }

    override fun fromRecord(
        rkey: String,
        record: JsonObject,
    ): TestTranscript {
        val noteId = parseUuidKey(collection, rkey.substringBefore(':'))
        return TestTranscript(
            noteId,
            TranscriptDocument(
                revision =
                    record
                        .getValue("revision")
                        .jsonPrimitive.longOrNull
                        ?.toInt() ?: 0,
                status =
                    if (record.getValue("status").jsonPrimitive.content ==
                        "complete"
                    ) {
                        TranscriptDocumentStatus.FINAL
                    } else {
                        TranscriptDocumentStatus.REFINING
                    },
                language = record["language"]?.jsonPrimitive?.content ?: "en-US",
            ),
        )
    }
}

internal fun noteKind(
    notes: FakeJournalNotesRepository,
    resolver: ConflictResolver<JournalNote> = LastWriteWinsResolver(),
): SpaceKind<JournalNote> {
    val entry = SpacePart.main(TestEntryMapper)
    val transcript =
        SpacePart<JournalNote, TestTranscript>(
            mapper = TestTranscriptMapper,
            recordsOf = { note -> (note as? JournalNote.Audio)?.transcript?.let { listOf(TestTranscript(note.uid, it)) }.orEmpty() },
            ownerOf = { rkey -> rkey.substringBefore(':') },
            rkeysOf = { id -> listOf("$id:$id") },
        )
    return SpaceKind(
        entityType = EntityType.NOTE,
        main = entry,
        local = TestNoteRecords(notes, resolver),
        withSyncVersion = { note, version -> note.withSyncVersion(version) },
        others = listOf(transcript),
    ) { changes, local ->
        val entryChange = changes.of(entry).lastOrNull() as? SpacePartChange.Written
        val base = entryChange?.value ?: local ?: return@SpaceKind null
        if (base !is JournalNote.Audio) return@SpaceKind base
        when (val change = changes.of(transcript).lastOrNull()) {
            is SpacePartChange.Written -> base.copy(transcript = change.value.document)
            is SpacePartChange.Deleted -> base.copy(transcript = null)
            null -> base.copy(transcript = (local as? JournalNote.Audio)?.transcript ?: base.transcript)
        }
    }
}

internal fun JournalNote.withSyncVersion(version: Long): JournalNote =
    when (this) {
        is JournalNote.Handwriting -> copy(syncVersion = version)
        is JournalNote.Text -> copy(syncVersion = version)
        is JournalNote.Image -> copy(syncVersion = version)
        is JournalNote.Video -> copy(syncVersion = version)
        is JournalNote.Audio -> copy(syncVersion = version)
    }

internal class TestNoteRecords(
    private val notes: FakeJournalNotesRepository,
    override val conflictResolver: ConflictResolver<JournalNote>,
) : SpaceLocalRecords<JournalNote> {
    override suspend fun load(entityId: String): JournalNote? = Uuid.parseOrNull(entityId)?.let { notes.getNoteById(it) }

    override fun lastUpdatedOf(item: JournalNote): Instant = item.lastUpdated

    override suspend fun acknowledgeUpload(
        item: JournalNote,
        seq: Long,
        syncedAt: Instant,
    ) {
        replaceLocal(item.withSyncVersion(seq))
    }

    override suspend fun replaceLocal(item: JournalNote) {
        notes.removeById(item.uid)
        notes.create(item)
    }
}

/** One device syncing journals and notes through [home], with everything else in memory. */
internal class SpaceDevice(
    val home: FakeDataHome,
    identityManager: app.logdate.client.device.crypto.IdentityKeyManager? = null,
    resolver: ConflictResolver<Journal> = LastWriteWinsResolver(),
    did: String? = TEST_DID,
    val journals: JournalRepository = FakeJournalRepository(),
    noteKindOf: (FakeJournalNotesRepository) -> SpaceKind<JournalNote> = { noteKind(it) },
) {
    val notes = FakeJournalNotesRepository()

    /** The inbox's clock, in epoch milliseconds; advance it to make backed-off rows due. */
    var now = 0L
    val metadata = FakeSyncMetadataService()
    val sessions = FakeSessionStorage()
    val database = DownloadInboxTest.Database()
    val inbox = DownloadInbox(database, database, { DownloadScope("test-account-id", sessions.currentOrigin) }, { now })
    val swapStore = InMemorySpaceSwapStore()
    val retrySchedule = InMemorySyncRetryScheduleStore()
    val media = FileReadingMediaManager()
    val mediaRefs = InMemoryMediaSyncRefStore()
    private val homes = SpaceDataHomes { _, token -> home.signedIn(token) }
    val deadLetters = InMemorySyncDeadLetterStore()

    /** The account's storage mode as the server reports it, and whether the server offers personal spaces. */
    var mode: AccountStorageMode? = AccountStorageMode.PERSONAL_SPACE_V1
    var serverOffersSpaces = true

    /** Whether this build syncs personal spaces. */
    var spaceEnabled = true
    var identityKey: ByteArray = TEST_IDENTITY_KEY
    var accountIdentityKey: suspend (String) -> ByteArray = { identityKey }

    val scope: UploadScope get() = UploadScope("test-account-id", sessions.currentOrigin)

    val manager =
        testDefaultSyncManager(
            identityKeyManager = identityManager,
            journalRepository = journals,
            journalNotesRepository = notes,
            syncMetadataService = metadata,
            sessionStorage = sessions,
            downloadInbox = inbox,
            retryScheduleStore = retrySchedule,
            deadLetterStore = deadLetters,
            journalConflictResolver = resolver,
            noteConflictResolver = LastWriteWinsResolver(),
            mediaManager = media,
            mediaSyncRefStore = mediaRefs,
            cloudMediaDataSource =
                SpaceRoutingMediaDataSource(
                    legacy = DefaultCloudMediaDataSource(fakeCloudApiClient()),
                    space =
                        SpaceMediaDataSource(
                            homes,
                            AesGcmMediaPayloadCrypto(TEST_MEDIA_KEY),
                            { SpaceLocation(sessions.currentOrigin, TEST_SPACE) },
                        ),
                    uploadsToSpace = { mode == AccountStorageMode.PERSONAL_SPACE_V1 },
                ),
            spaceSync =
                SpaceSyncDependencies(
                    registry = SpaceRecordRegistry(listOf(journalKind(journals, resolver), noteKindOf(notes))),
                    gate = SpaceTransportGate({ spaceEnabled }, { serverOffersSpaces }, { mode }),
                    homes = homes,
                    swapStore = swapStore,
                    accountDid = { did },
                    identityKey = { accountId -> accountIdentityKey(accountId) },
                ),
        )

    suspend fun create(journal: Journal) {
        journals.create(journal)
        metadata.enqueuePending(journal.id.toString(), EntityType.JOURNAL, PendingOperation.CREATE)
    }

    suspend fun edit(journal: Journal) {
        journals.update(journal)
        metadata.enqueuePending(journal.id.toString(), EntityType.JOURNAL, PendingOperation.UPDATE)
    }

    suspend fun delete(id: Uuid) {
        journals.delete(id)
        metadata.enqueuePending(id.toString(), EntityType.JOURNAL, PendingOperation.DELETE)
    }

    suspend fun saveNote(
        note: JournalNote,
        operation: PendingOperation = PendingOperation.CREATE,
    ) {
        notes.removeById(note.uid)
        notes.create(note)
        metadata.enqueuePending(note.uid.toString(), EntityType.NOTE, operation)
    }

    suspend fun deleteNote(id: Uuid) {
        notes.removeById(id)
        metadata.enqueuePending(id.toString(), EntityType.NOTE, PendingOperation.DELETE)
    }

    suspend fun journal(id: Uuid): Journal? = journals.getJournalById(id)

    suspend fun note(id: Uuid): JournalNote? = notes.getNoteById(id)

    suspend fun pendingJournals() = metadata.getPendingUploads(EntityType.JOURNAL)

    suspend fun pendingNotes() = metadata.getPendingUploads(EntityType.NOTE)

    /** Records staged from the space and not yet applied, of every kind. */
    fun waitingRecords() = database.rows.count { it.entityType.startsWith("SPACE_") && it.state != "APPLIED" }

    /** Rows staged for journals, keyed by sealed key. */
    fun stagedJournalRows() = database.rows.filter { it.entityType == spacePartsType(EntityType.JOURNAL) }
}

internal val TEST_MEDIA_KEY = ByteArray(32) { (it + 1).toByte() }

/** Keeps saved media in memory, and reads a file handed to it from disk as the real stores do. */
internal class FileReadingMediaManager : InMemoryMediaManager() {
    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
    ): String {
        val data = SystemFileSystem.source(Path(sourceFilePath)).buffered().use { it.readByteArray() }
        return saveMedia(MediaPayload(fileName, mimeType, data.size.toLong(), data))
    }
}

/** Opens what [this] holds under [rkey] in [collection], as another device of the owner would. */
internal suspend fun FakeDataHome.open(
    collection: String,
    rkey: String,
): SealedPayload? {
    val keys = testKeys()
    val stored = stored(SealedRecordCodec.sealedRkey(keys, collection, rkey)) ?: return null
    return SealedRecordCodec.open(keys, TEST_SPACE, TEST_DID, stored.rkey, stored.record)
}

internal suspend fun FakeDataHome.openJournal(rkey: String): SealedPayload =
    requireNotNull(open(JOURNAL_COLLECTION, rkey)) {
        "Nothing stored for $rkey"
    }

/** A personal-space reference to an uploaded recording, as a note keeps it once its file is uploaded. */
internal fun uploadedAudioRef(noteId: Uuid): String =
    SpaceMediaReference(
        "bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e",
        noteId,
        "audio/mp4",
        storedSize = 60,
        size = 11,
    ).format()
