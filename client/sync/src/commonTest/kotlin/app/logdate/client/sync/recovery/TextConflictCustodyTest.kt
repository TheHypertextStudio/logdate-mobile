package app.logdate.client.sync.recovery

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.ChangesPage
import app.logdate.client.sync.DownloadStrategy
import app.logdate.client.sync.InMemoryKeyValueStorage
import app.logdate.client.sync.RemoteOutcome
import app.logdate.client.sync.SyncDownloadEngine
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.conflict.JournalNoteConflictResolver
import app.logdate.client.sync.conflict.KeyValueSyncConflictStore
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.test.fakeSyncMetadataService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TextConflictCustodyTest {
    @Test
    fun `download keeps canonical prose and retains both versions across restart and replay`() =
        runTest {
            for (pending in listOf(false, true)) {
                val fixture = Fixture(pending)
                assertTrue(fixture.download().success)
                assertEquals(fixture.local, fixture.canonical)
                assertFalse(fixture.canonical.content.contains("Merged conflict"))
                assertTrue(
                    fixture.storage.values.values
                        .joinToString()
                        .contains(fixture.local.content),
                )
                assertTrue(
                    fixture.storage.values.values
                        .joinToString()
                        .contains(fixture.remote.content),
                )
                val restarted = KeyValueSyncConflictStore(fixture.storage)
                assertEquals(1, restarted.list().size)
                assertEquals(fixture.remote.syncVersion, restarted.list().single().remoteVersion)
                assertEquals(UploadScope("synthetic-owner", "https://sync.example"), restarted.list().single().scope)
                val pair =
                    assertNotNull(
                        Json
                            .parseToJsonElement(
                                fixture.storage.values.values
                                    .single(),
                            ).jsonArray
                            .single()
                            .jsonObject["textVersions"],
                    ).jsonObject
                assertEquals(Json.encodeToJsonElement(fixture.local), pair["local"])
                assertEquals(Json.encodeToJsonElement(fixture.remote), pair["remote"])
                assertEquals(Instant.fromEpochMilliseconds(1).toString(), pair["localTimestamp"]?.jsonPrimitive?.content)
                assertEquals(Instant.fromEpochMilliseconds(2).toString(), pair["remoteTimestamp"]?.jsonPrimitive?.content)
                fixture.replay()
                assertTrue(fixture.download().success)
                assertEquals(1, restarted.list().size)
                assertEquals(if (pending) 1 else 0, fixture.metadata.getPendingUploads(EntityType.NOTE).size)
            }
        }

    @Test
    fun `failure to preserve either version leaves canonical prose and remote inbox intact`() =
        runTest {
            for (pending in listOf(false, true)) {
                val fixture = Fixture(pending)
                fixture.failWrites = true
                assertFailsWith<app.logdate.client.sync.SyncStorageUnavailableException> { fixture.download() }
                assertEquals(fixture.local, fixture.canonical)
                assertEquals(
                    "encrypted-synthetic-record",
                    fixture.inbox
                        .pending("NOTE")
                        .single()
                        .payload,
                )
                assertEquals(if (pending) 1 else 0, fixture.metadata.getPendingUploads(EntityType.NOTE).size)
                fixture.failWrites = false
                fixture.now = 100_000L
                assertTrue(fixture.download().success)
                assertEquals(fixture.local, fixture.canonical)
                assertTrue(
                    fixture.storage.values.values
                        .joinToString()
                        .contains(fixture.remote.content),
                )
                assertEquals(if (pending) 1 else 0, fixture.metadata.getPendingUploads(EntityType.NOTE).size)
            }
        }

    @Test
    fun `new remote versions retain earlier pairs rather than replacing them`() =
        runTest {
            val fixture = Fixture(false)
            assertTrue(fixture.download().success)
            fixture.remote = fixture.remote.copy(content = "A later distinct remote version.", syncVersion = 3)
            fixture.replay()
            assertTrue(fixture.download().success)
            val retained = KeyValueSyncConflictStore(fixture.storage).list()
            assertEquals(2, retained.size)
            assertEquals(setOf(2L, 3L), retained.map { it.remoteVersion }.toSet())
            assertEquals(fixture.local, fixture.canonical)
        }

    @Test
    fun `scope change after custody persistence cannot acknowledge another account inbox`() =
        runTest {
            val fixture = Fixture(false)
            fixture.afterWrite = { fixture.scope = DownloadScope("other-owner", "https://other.example") }
            assertFailsWith<CancellationException> { fixture.download() }
            assertEquals(fixture.local, fixture.canonical)
            val retained = KeyValueSyncConflictStore(fixture.storage).list().single()
            assertEquals(UploadScope("synthetic-owner", "https://sync.example"), retained.scope)
            fixture.scope = DownloadScope("synthetic-owner", "https://sync.example")
            fixture.afterWrite = null
            fixture.now = 100_000L
            assertEquals(
                "encrypted-synthetic-record",
                fixture.inbox
                    .pending("NOTE")
                    .single()
                    .payload,
            )
            assertTrue(fixture.download().success)
            assertEquals(1, KeyValueSyncConflictStore(fixture.storage).list().size)
        }

    @Test
    fun `store deduplicates exact pairs but preserves a different body at equal versions`() =
        runTest {
            val fixture = Fixture(false)
            assertTrue(fixture.download().success)
            val restarted = KeyValueSyncConflictStore(fixture.storage)
            val first = restarted.list().single()
            restarted.add(first.copy(id = Uuid.random().toString()))
            assertEquals(1, restarted.list().size)
            val pair = assertNotNull(first.textVersions)
            val changed = pair.copy(local = pair.local.copy(content = "A later local edit at the same acknowledged base."))
            restarted.add(first.copy(id = Uuid.random().toString(), textVersions = changed))
            assertEquals(2, restarted.list().size)
            assertEquals(setOf(pair, changed), restarted.list().map { it.textVersions }.toSet())
        }

    @Test
    fun `personal space applies a changed remote copy automatically when no local edit waits`() =
        runTest {
            val fixture = Fixture(false)
            fixture.personalSpace = true
            assertTrue(fixture.download().success)
            assertEquals(fixture.remote, fixture.canonical)
            assertTrue(KeyValueSyncConflictStore(fixture.storage).list().isEmpty())
            assertTrue(fixture.metadata.getPendingUploads(EntityType.NOTE).isEmpty())
            assertEquals(listOf("settle:STORED"), fixture.events)
        }

    @Test
    fun `personal space retains divergent pending originals before declining settlement`() =
        runTest {
            val fixture = Fixture(true)
            fixture.personalSpace = true
            assertTrue(fixture.download().success)
            assertEquals(fixture.local, fixture.canonical)
            val retained = assertNotNull(KeyValueSyncConflictStore(fixture.storage).list().single().textVersions)
            assertEquals(fixture.local, retained.local)
            assertEquals(fixture.remote, retained.remote)
            assertEquals(1, fixture.metadata.getPendingUploads(EntityType.NOTE).size)
            assertEquals(listOf("custody", "settle:DECLINED"), fixture.events)
        }

    private class Fixture(
        pending: Boolean,
    ) {
        val storage = InMemoryKeyValueStorage()
        var failWrites = false
        var personalSpace = false
        val events = mutableListOf<String>()
        var afterWrite: (() -> Unit)? = null
        var now = 1L
        private val guardedStorage =
            object : KeyValueStorage by storage {
                override suspend fun putString(
                    key: String,
                    value: String,
                ) {
                    check(!failWrites) { "Synthetic recovery persistence failure" }
                    storage.putString(key, value)
                    events += "custody"
                    afterWrite?.invoke()
                }

                override suspend fun putStringOrThrow(
                    key: String,
                    value: String,
                ) = putString(key, value)
            }
        private val db = DownloadInboxTest.Database()
        var scope = DownloadScope("synthetic-owner", "https://sync.example")
        val inbox = DownloadInbox(db, db, { scope }, { now })
        private val instant = Instant.fromEpochMilliseconds(1000)
        val local =
            JournalNote.Text(
                uid = Uuid.random(),
                creationTimestamp = instant,
                lastUpdated = instant,
                content = "Local prose beside the river.",
                syncVersion = 1,
            )
        var remote =
            local.copy(
                content = "Remote prose beneath the trees.",
                syncVersion = 2,
                lastUpdated = Instant.fromEpochMilliseconds(2000),
            )
        var canonical = local
        val metadata = fakeSyncMetadataService()
        private val initiallyPending = pending
        private var prepared = false

        suspend fun replay() {
            inbox.stage(
                "NOTE",
                remote.syncVersion,
                listOf(WireDownload(local.uid.toString(), remote.syncVersion, false, "encrypted-synthetic-record")),
            )
        }

        suspend fun download(): SyncResult {
            if (!prepared) {
                if (initiallyPending) metadata.enqueuePending(local.uid.toString(), EntityType.NOTE, PendingOperation.UPDATE)
                inbox.stage("NOTE", 2, listOf(WireDownload(local.uid.toString(), 2, false, "encrypted-synthetic-record")))
                prepared = true
            }
            return SyncDownloadEngine(
                db,
                metadata,
                KeyValueSyncConflictStore(guardedStorage),
                { SyncResult(false) },
                { _, _ -> SyncResult(false) },
                downloadInbox = inbox,
            ).download(
                DownloadStrategy<JournalNote>(
                    entityType = EntityType.NOTE,
                    logLabel = "note",
                    fetchChanges = {
                        _,
                        _,
                        ->
                        Result.success(ChangesPage(inbox.pending("NOTE").map { remote }, emptyList(), remote.lastUpdated, false))
                    },
                    localItem = { id -> canonical.takeIf { it.uid == id } },
                    idOf = { it.uid },
                    syncVersionOf = { it.syncVersion },
                    lastUpdatedOf = { it.lastUpdated },
                    conflictResolver = JournalNoteConflictResolver(),
                    applyCreate = { canonical = it as JournalNote.Text },
                    applyReplace = { _, next -> canonical = next as JournalNote.Text },
                    applyDelete = { error("Unexpected deletion") },
                    appliesRemoteOverUnchangedLocal = personalSpace,
                    settle =
                        if (personalSpace) {
                            { _: Uuid, outcome: RemoteOutcome -> events += "settle:$outcome" }
                        } else {
                            null
                        },
                ),
                "synthetic-token",
                Instant.fromEpochMilliseconds(0),
            )
        }
    }
}
