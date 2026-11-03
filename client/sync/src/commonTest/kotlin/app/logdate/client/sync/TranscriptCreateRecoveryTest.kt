package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.DefaultCloudMediaDataSource
import app.logdate.client.sync.cloud.MediaDownloadResponse
import app.logdate.client.sync.conflict.KeyValueSyncConflictStore
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadInboxTest
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.recovery.WireDownload
import app.logdate.client.sync.test.FakeCloudApiClient
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.FakeSessionStorage
import app.logdate.client.sync.test.FakeSyncMetadataService
import app.logdate.client.sync.test.testDefaultSyncManager
import app.logdate.shared.model.sync.ContentChange
import app.logdate.shared.model.sync.ContentChangesResponse
import app.logdate.shared.model.sync.ContentUpdateRequest
import app.logdate.shared.model.sync.ContentUpdateResponse
import app.logdate.shared.model.sync.ContentUploadRequest
import app.logdate.shared.model.sync.ContentUploadResponse
import app.logdate.shared.model.sync.VersionConstraint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TranscriptCreateRecoveryTest {
    @Test
    fun `transcript committed during initial create uploads afterward as a versioned update`() =
        runTest {
            val original = audio()
            val latest = original.copy(transcript = TranscriptDocument.fromPlainText("Newest speech").copy(revision = 2))
            val backing = FakeJournalNotesRepository().apply { create(original) }
            val notes = metadataOnlyRepository(backing)
            val metadata = identityMetadata()
            metadata.enqueuePending(original.uid.toString(), EntityType.NOTE, PendingOperation.CREATE)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val api =
                object : FakeCloudApiClient() {
                    init {
                        updateContentResponse = Result.success(ContentUpdateResponse(original.uid.toString(), 43, 101))
                    }

                    override suspend fun uploadContent(
                        accessToken: String,
                        content: ContentUploadRequest,
                    ): Result<ContentUploadResponse> {
                        super.uploadContent(accessToken, content)
                        if (uploadContentCalls.size >
                            1
                        ) {
                            return Result.failure(CloudApiException("EXISTS", "Already created", statusCode = 412))
                        }
                        entered.complete(Unit)
                        release.await()
                        return Result.success(ContentUploadResponse(content.id, 42, 100))
                    }
                }
            val manager =
                testDefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(api),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(api),
                    journalNotesRepository = notes,
                    syncMetadataService = metadata,
                    syncScope = backgroundScope,
                )
            val uploading = async { manager.uploadPendingChanges() }
            entered.await()
            backing.removeById(original.uid)
            backing.create(latest)
            metadata.enqueueTranscriptMutation(original.uid.toString())
            val newerOperation = metadata.getPendingUploads(EntityType.NOTE).single()
            release.complete(Unit)
            assertTrue(uploading.await().success)
            assertEquals(newerOperation.operationId, metadata.getPendingUploads(EntityType.NOTE).single().operationId)
            assertEquals(latest.transcript, (notes.getNoteById(original.uid) as JournalNote.Audio).transcript)

            assertTrue(manager.uploadPendingChanges().success)

            assertEquals(1, api.uploadContentCalls.size)
            val update = api.updateContentCalls.single().third
            assertEquals(VersionConstraint.Known(42), update.versionConstraint)
            assertEquals(latest.transcript, Json.decodeFromString<TranscriptDocument>(update.transcript.orEmpty()))
            assertTrue(metadata.getPendingUploads(EntityType.NOTE).isEmpty())
        }

    @Test
    fun `lost create response rebases newer transcript without a false conflict`() =
        runTest {
            val original = audio()
            val latest = original.copy(transcript = TranscriptDocument.fromPlainText("Newest speech").copy(revision = 2))
            val backing = FakeJournalNotesRepository().apply { create(latest) }
            val notes = metadataOnlyRepository(backing)
            val metadata = identityMetadata()
            metadata.enqueuePending(original.uid.toString(), EntityType.NOTE, PendingOperation.CREATE)
            val api =
                FakeCloudApiClient().apply {
                    updateContentResponse = Result.success(ContentUpdateResponse(original.uid.toString(), 43, 101))
                    downloadMediaResponse =
                        Result.success(
                            MediaDownloadResponse(original.uid.toString(), "audio.m4a", "audio/mp4", 1, byteArrayOf(1), original.mediaRef),
                        )
                    getContentChangesResponse =
                        Result.success(
                            ContentChangesResponse(
                                listOf(
                                    ContentChange(
                                        original.uid.toString(),
                                        "AUDIO",
                                        mediaUri = original.mediaRef,
                                        durationMs = original.durationMs,
                                        caption = original.caption,
                                        createdAt = 1,
                                        // The server stamps its own modification time on CREATE.
                                        lastUpdated = 99,
                                        serverVersion = 42,
                                    ),
                                ),
                                emptyList(),
                                42,
                            ),
                        )
                }
            val manager =
                testDefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(api),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(api),
                    journalNotesRepository = notes,
                    syncMetadataService = metadata,
                    syncScope = backgroundScope,
                )

            assertTrue(manager.downloadRemoteChanges().success)

            val pending = metadata.getPendingUploads(EntityType.NOTE).single()
            assertEquals(PendingOperation.UPDATE, pending.operation)
            assertEquals(42L, pending.expectedServerVersion)
            assertEquals(latest.transcript, (notes.getNoteById(original.uid) as JournalNote.Audio).transcript)
            assertEquals(0, manager.getSyncStatus().conflictCount)
            assertFalse("downloadMedia" in api.methodCalls, "Pending local work must retain its media mapping")
            assertTrue(manager.uploadPendingChanges().success)
            assertTrue(api.uploadContentCalls.isEmpty())
            assertEquals(
                VersionConstraint.Known(42),
                api.updateContentCalls
                    .single()
                    .third.versionConstraint,
            )
            assertTrue(metadata.getPendingUploads(EntityType.NOTE).isEmpty())
        }

    @Test
    fun `lost create response acknowledges raw working text without a false conflict`() =
        runTest {
            val original = audio().copy(transcription = "  Working text.\nKeep spacing.  ")
            val backing = FakeJournalNotesRepository().apply { create(original) }
            val notes = metadataOnlyRepository(backing)
            val metadata = identityMetadata()
            metadata.enqueuePending(original.uid.toString(), EntityType.NOTE, PendingOperation.CREATE)
            val api =
                FakeCloudApiClient().apply {
                    getContentChangesResponse =
                        Result.success(
                            ContentChangesResponse(
                                listOf(
                                    ContentChange(
                                        original.uid.toString(),
                                        "AUDIO",
                                        mediaUri = original.mediaRef,
                                        durationMs = original.durationMs,
                                        caption = original.caption,
                                        createdAt = original.creationTimestamp.toEpochMilliseconds(),
                                        lastUpdated = original.lastUpdated.toEpochMilliseconds(),
                                        serverVersion = 42,
                                    ),
                                ),
                                emptyList(),
                                42,
                            ),
                        )
                }
            val manager =
                testDefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(api),
                    cloudMediaDataSource = DefaultCloudMediaDataSource(api),
                    journalNotesRepository = notes,
                    syncMetadataService = metadata,
                    syncScope = backgroundScope,
                )

            assertTrue(manager.downloadRemoteChanges().success)

            val stored = notes.getNoteById(original.uid) as JournalNote.Audio
            assertEquals(original.transcription, stored.transcription)
            assertEquals(42L, stored.syncVersion)
            assertTrue(metadata.getPendingUploads(EntityType.NOTE).isEmpty())
            assertEquals(0, manager.getSyncStatus().conflictCount)
            assertFalse("downloadMedia" in api.methodCalls)
            assertTrue(api.uploadContentCalls.isEmpty())
            assertTrue(api.updateContentCalls.isEmpty())
        }

    @Test
    fun `lost create reconciliation retains a real caption conflict`() = runTest { assertRetainedConflict(true) }

    @Test
    fun `lost create reconciliation retains a newer remote transcript conflict`() = runTest { assertRetainedConflict(false) }

    private suspend fun TestScope.assertRetainedConflict(differentCaption: Boolean) {
        val original = audio()
        val latest = original.copy(transcript = TranscriptDocument.fromPlainText("Local speech").copy(revision = 2))
        val notes = FakeJournalNotesRepository().apply { create(latest) }
        val metadata = identityMetadata()
        val storage = InMemoryKeyValueStorage()
        val conflicts = KeyValueSyncConflictStore(storage)
        val sessions = FakeSessionStorage()
        val session = requireNotNull(sessions.getOriginBoundSession())
        val selected = DownloadScope(session.session.accountId, session.origin)
        val database = DownloadInboxTest.Database()
        val inbox = DownloadInbox(database, database, { selected }, { 1L })
        metadata.enqueuePending(original.uid.toString(), EntityType.NOTE, PendingOperation.CREATE)
        val api =
            FakeCloudApiClient().apply {
                downloadMediaResponse =
                    Result.success(
                        MediaDownloadResponse(
                            original.uid.toString(),
                            "audio.m4a",
                            "audio/mp4",
                            1,
                            byteArrayOf(1),
                            original.mediaRef,
                        ),
                    )
                getContentChangesResponse =
                    Result.success(
                        ContentChangesResponse(
                            listOf(
                                ContentChange(
                                    original.uid.toString(),
                                    "AUDIO",
                                    mediaUri = original.mediaRef,
                                    durationMs = original.durationMs,
                                    caption = if (differentCaption) "Remote edit" else original.caption,
                                    createdAt = 1,
                                    lastUpdated = 99,
                                    serverVersion = 42,
                                    transcript =
                                        if (differentCaption) {
                                            null
                                        } else {
                                            Json.encodeToString(
                                                TranscriptDocument.fromPlainText("Remote speech").copy(revision = 3),
                                            )
                                        },
                                ),
                            ),
                            emptyList(),
                            42,
                        ),
                    )
            }
        val manager =
            testDefaultSyncManager(
                cloudContentDataSource = DefaultCloudContentDataSource(api),
                cloudMediaDataSource = DefaultCloudMediaDataSource(api),
                journalNotesRepository = notes,
                syncMetadataService = metadata,
                syncScope = backgroundScope,
                conflictStore = conflicts,
                sessionStorage = sessions,
                downloadInbox = inbox,
            )

        val wire =
            api.getContentChangesResponse
                .getOrThrow()
                .changes
                .single()
        inbox.stage("NOTE", 42, listOf(WireDownload(original.uid.toString(), 42, false, Json.encodeToString(wire))), selected)
        val pendingBefore = metadata.getPendingUploads(EntityType.NOTE).single()

        assertTrue(manager.downloadRemoteChanges().success)

        assertEquals(PendingOperation.CREATE, metadata.getPendingUploads(EntityType.NOTE).single().operation)
        assertEquals(latest.transcript, (notes.getNoteById(original.uid) as JournalNote.Audio).transcript)
        val records = KeyValueSyncConflictStore(storage).list()
        val record = records.single()
        assertEquals(UploadScope(selected.owner, selected.origin), record.scope)
        // Assert the serialized originals, independently of the presentation count.
        val pair =
            assertNotNull(
                Json
                    .parseToJsonElement(storage.values.values.single())
                    .jsonArray
                    .single()
                    .jsonObject["audioVersions"],
            ).jsonObject
        assertEquals(Json.encodeToJsonElement(latest), pair["local"])
        val remoteTranscript = if (differentCaption) null else Json.decodeFromString<TranscriptDocument>(requireNotNull(wire.transcript))
        val expectedRemote =
            original.copy(
                lastUpdated = Instant.fromEpochMilliseconds(99),
                caption = if (differentCaption) "Remote edit" else original.caption,
                transcript = remoteTranscript,
                syncVersion = 42,
            )
        assertEquals(Json.encodeToJsonElement(expectedRemote), pair["remote"])
        assertEquals(pendingBefore, metadata.getPendingUploads(EntityType.NOTE).single())
        assertEquals(listOf(record), KeyValueSyncConflictStore(storage).list())
        assertTrue(inbox.isAppliedVersion("NOTE", original.uid.toString(), 42, selected))
        assertEquals(1, manager.getSyncStatus().conflictCount)
        assertEquals(0, manager.getSyncStatus().earlierConflictCount)
        assertTrue(manager.downloadRemoteChanges().success)
        assertEquals(records, KeyValueSyncConflictStore(storage).list())
    }

    @Test
    fun `transcript committed during a rebased patch advances its surviving expected version`() =
        runTest {
            val original = audio().copy(syncVersion = 42, transcript = TranscriptDocument.fromPlainText("First speech").copy(revision = 1))
            val latest = original.copy(transcript = TranscriptDocument.fromPlainText("Newest speech").copy(revision = 2))
            val backing = FakeJournalNotesRepository().apply { create(original) }
            val notes = metadataOnlyRepository(backing)
            val metadata = identityMetadata()
            metadata.enqueuePending(original.uid.toString(), EntityType.NOTE, PendingOperation.CREATE)
            assertTrue(metadata.bindCreateToServerVersion(EntityType.NOTE, metadata.getPendingUploads(EntityType.NOTE).single(), 42))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val api =
                object : FakeCloudApiClient() {
                    override suspend fun updateContent(
                        accessToken: String,
                        contentId: String,
                        content: ContentUpdateRequest,
                    ): Result<ContentUpdateResponse> {
                        super.updateContent(accessToken, contentId, content)
                        if (updateContentCalls.size == 1) {
                            entered.complete(Unit)
                            release.await()
                            return Result.success(ContentUpdateResponse(contentId, 43, 100))
                        }
                        return if (content.versionConstraint == VersionConstraint.Known(43)) {
                            Result.success(ContentUpdateResponse(contentId, 44, 101))
                        } else {
                            Result.failure(CloudApiException("CONFLICT", "Expected current version", statusCode = 409))
                        }
                    }
                }
            val manager =
                testDefaultSyncManager(
                    cloudContentDataSource = DefaultCloudContentDataSource(api),
                    journalNotesRepository = notes,
                    syncMetadataService = metadata,
                    syncScope = backgroundScope,
                )
            val uploading = async { manager.uploadPendingChanges() }
            entered.await()
            backing.removeById(original.uid)
            backing.create(latest)
            metadata.enqueueTranscriptMutation(original.uid.toString())
            val newerOperation = metadata.getPendingUploads(EntityType.NOTE).single()
            assertEquals(42L, newerOperation.expectedServerVersion)
            release.complete(Unit)
            assertTrue(uploading.await().success)
            assertEquals(newerOperation.operationId, metadata.getPendingUploads(EntityType.NOTE).single().operationId)

            assertTrue(manager.uploadPendingChanges().success)

            val update = api.updateContentCalls.last().third
            assertEquals(VersionConstraint.Known(43), update.versionConstraint)
            assertEquals(latest.transcript, Json.decodeFromString<TranscriptDocument>(update.transcript.orEmpty()))
            assertEquals(latest.transcript, (notes.getNoteById(original.uid) as JournalNote.Audio).transcript)
            assertTrue(metadata.getPendingUploads(EntityType.NOTE).isEmpty())
            assertEquals(0, manager.getSyncStatus().conflictCount)
        }

    private fun audio() =
        JournalNote.Audio(
            mediaRef = "https://server.example/media/audio",
            durationMs = 4000,
            creationTimestamp = Instant.fromEpochMilliseconds(1),
            lastUpdated = Instant.fromEpochMilliseconds(2),
            caption = "My caption",
        )

    private fun metadataOnlyRepository(backing: FakeJournalNotesRepository): SyncableJournalNotesRepository =
        object : SyncableJournalNotesRepository by backing {
            override suspend fun updateSyncMetadata(
                note: JournalNote,
                syncVersion: Long,
                syncedAt: Instant,
            ) {
                val current = backing.getNoteById(note.uid) as? JournalNote.Audio ?: return
                backing.removeById(note.uid)
                backing.create(current.copy(syncVersion = syncVersion))
            }
        }

    // Keep operation identity while using the unscoped authentication fixture.
    private fun identityMetadata(): SyncMetadataService {
        val backing = FakeSyncMetadataService(trackOperationIdentity = true)
        return object : SyncMetadataService by backing {
            override suspend fun getPendingUploads(entityType: EntityType): List<PendingUpload> =
                backing.getPendingUploads(entityType).map { it.copy(scope = null) }

            override suspend fun isCurrentOperation(
                entityType: EntityType,
                pending: PendingUpload,
            ): Boolean = getPendingUploads(entityType).any { it.entityId == pending.entityId && it.operationId == pending.operationId }

            override suspend fun settleIfCurrent(
                entityType: EntityType,
                pending: PendingUpload,
                syncedAt: Instant,
                version: Long,
            ): Boolean {
                if (!isCurrentOperation(entityType, pending)) return false
                backing.markAsSynced(pending.entityId, entityType, syncedAt, version)
                return true
            }

            override suspend fun bindCreateToServerVersion(
                entityType: EntityType,
                pending: PendingUpload,
                serverVersion: Long,
            ): Boolean {
                val captured = backing.getPendingUploads(entityType).firstOrNull { it.entityId == pending.entityId } ?: return false
                return backing.bindCreateToServerVersion(entityType, pending.copy(scope = captured.scope), serverVersion)
            }

            override suspend fun advancePendingVersionAfterUpload(
                entityType: EntityType,
                uploaded: PendingUpload,
                serverVersion: Long,
            ): Boolean {
                val captured = backing.getPendingUploads(entityType).firstOrNull { it.entityId == uploaded.entityId } ?: return false
                return backing.advancePendingVersionAfterUpload(entityType, uploaded.copy(scope = captured.scope), serverVersion)
            }
        }
    }
}
