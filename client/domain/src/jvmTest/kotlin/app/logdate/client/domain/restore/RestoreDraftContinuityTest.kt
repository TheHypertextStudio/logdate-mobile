package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportDraft
import app.logdate.client.domain.export.ExportMetadata
import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.ExportStats
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableTextBlock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RestoreDraftContinuityTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val drafts = mutableMapOf<Uuid, EditorDraft>()
    private val repository = mockk<JournalRepository>(relaxed = true)
    private val notes = mockk<app.logdate.client.repository.journals.JournalNotesRepository>(relaxed = true)
    private val importer = mockk<MediaImporter>(relaxed = true)
    private val restore =
        RestoreUserDataUseCase(
            repository,
            notes,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            RestoreTestOwnerProvider(),
            RestoreTestDeviceIdProvider(),
        )

    @BeforeTest
    fun setup() {
        coEvery { repository.getDraftRecoveryScope() } returns null
        coEvery { repository.getJournalRecoveryScope() } returns null
        coEvery { notes.getNoteRecoveryScope() } returns null
        coEvery { repository.getDraft(any()) } answers { drafts[firstArg()] }
        coEvery { repository.getDraftForRestore(any()) } answers { drafts[firstArg()] }
        coEvery { repository.saveDraft(any()) } answers { drafts[firstArg<EditorDraft>().id] = firstArg() }
        coEvery { repository.deleteDraft(any()) } answers {
            drafts.remove(firstArg())
        }
        coEvery { repository.compareAndSetDraftForRestore(any(), any(), any(), any(), true, any()) } coAnswers {
            currentCoroutineContext().ensureActive()
            val id = firstArg<Uuid>()
            if (drafts[id] != secondArg<EditorDraft?>()) {
                false
            } else {
                val replacement = thirdArg<EditorDraft?>()
                if (replacement == null) drafts.remove(id) else drafts[id] = replacement
                true
            }
        }
    }

    @Test
    fun `retry preserves a newer local draft before importing its old media`() =
        runTest {
            val local = localDraft()
            drafts[local.id] = local
            val incoming = exportDraft(local.id, now - 1.days).copy(mediaReferences = listOf("media/old-photo.jpg"))
            val result = restore.restore(bundle(incoming), mediaImporter = importer)
            assertEquals(local, drafts[local.id])
            assertEquals(0, result.draftsImported)
            coVerify(exactly = 0) { repository.saveDraft(any()) }
            coVerify(exactly = 0) { importer.importMedia(any()) }
        }

    @Test
    fun `replaying the same archive does not rewrite an already imported draft`() =
        runTest {
            val incoming = exportDraft(Uuid.random(), now)
            val archive = bundle(incoming)
            assertEquals(1, restore.restore(archive).draftsImported)
            val first = drafts[Uuid.parse(incoming.id)]
            assertEquals(0, restore.restore(archive).draftsImported)
            assertEquals(first, drafts[Uuid.parse(incoming.id)])
            coVerify(exactly = 1) { repository.compareAndSetDraftForRestore(any(), any(), any(), any(), true, any()) }
        }

    @Test
    fun `a later failure keeps drafts already restored`() =
        runTest {
            val local = localDraft()
            drafts[local.id] = local
            val created = Uuid.random()
            assertFailsWith<IllegalStateException> {
                restore.restore(
                    bundle(exportDraft(local.id, now + 1.days), exportDraft(created, now)),
                    RestoreOptions(strategy = RestoreStrategy.REPLACE_EXISTING),
                    onProgress = { if (it == RestoreProgressPhase.COMPLETED) error("Interrupted archive import") },
                )
            }
            assertEquals(now + 1.days, drafts.getValue(local.id).lastModifiedAt)
            assertEquals(now, drafts.getValue(created).lastModifiedAt)
            coVerify(exactly = 0) { repository.deleteDraft(local.id) }
        }

    @Test
    fun `rollback keeps an editor change accepted after the archive write`() =
        runTest {
            val local = localDraft()
            drafts[local.id] = local
            val accepted = local.copy(lastModifiedAt = now + 3.days)
            assertFailsWith<IllegalStateException> {
                restore.restore(
                    bundle(exportDraft(local.id, now + 1.days)),
                    onProgress = {
                        if (it == RestoreProgressPhase.COMPLETED) {
                            drafts[local.id] = accepted
                            error("Interrupted after an editor save")
                        }
                    },
                )
            }
            assertEquals(accepted, drafts[local.id])
        }

    @Test
    fun `a later failure keeps the last accepted duplicate snapshot`() =
        runTest {
            val local = localDraft()
            drafts[local.id] = local
            assertFailsWith<IllegalStateException> {
                restore.restore(
                    bundle(exportDraft(local.id, now + 1.days), exportDraft(local.id, now + 2.days)),
                    onProgress = { if (it == RestoreProgressPhase.COMPLETED) error("Interrupted after duplicate ID") },
                )
            }
            assertEquals(now + 2.days, drafts.getValue(local.id).lastModifiedAt)
        }

    @Test
    fun `cancellation retains a draft already written`() =
        runTest {
            val local = localDraft()
            drafts[local.id] = local
            val imported = CompletableDeferred<Unit>()
            val job =
                launch {
                    restore.restore(
                        bundle(exportDraft(local.id, now + 1.days)),
                        onProgress = {
                            if (it == RestoreProgressPhase.COMPLETED) {
                                imported.complete(Unit)
                                awaitCancellation()
                            }
                        },
                    )
                }
            imported.await()
            job.cancelAndJoin()
            assertEquals(now + 1.days, drafts.getValue(local.id).lastModifiedAt)
        }

    @Test
    fun `malformed draft IDs are skipped instead of creating another draft on each retry`() =
        runTest {
            val archive = bundle(exportDraft(Uuid.random(), now).copy(id = "invalid-draft-id"))
            repeat(2) {
                val result = restore.restore(archive)
                assertEquals(0, result.draftsImported)
                assertEquals(listOf("Skipped draft with an invalid ID"), result.warnings)
            }
            assertEquals(emptyMap(), drafts)
        }

    @Test
    fun `a failed duplicate write keeps an earlier successful write`() =
        runTest {
            val local = localDraft()
            drafts[local.id] = local
            coEvery {
                repository.compareAndSetDraftForRestore(local.id, any(), match { it?.lastModifiedAt == now + 2.days }, any(), any(), any())
            } throws IllegalStateException("Second draft write failed")
            assertFailsWith<IllegalStateException> {
                restore.restore(bundle(exportDraft(local.id, now + 1.days), exportDraft(local.id, now + 2.days)))
            }
            assertEquals(now + 1.days, drafts.getValue(local.id).lastModifiedAt)
        }

    @Test
    fun `duplicate archive records cannot overwrite an editor save between imports`() =
        runTest {
            val local = localDraft()
            drafts[local.id] = local
            val edit = local.copy(lastModifiedAt = now + 1.days)
            var reads = 0
            coEvery { repository.getDraftForRestore(local.id) } answers {
                if (++reads == 2) drafts[local.id] = edit
                drafts[local.id]
            }
            restore.restore(bundle(exportDraft(local.id, now + 1.days), exportDraft(local.id, now + 2.days)))
            assertEquals(edit, drafts[local.id])
        }

    private fun localDraft() =
        EditorDraft(
            id = Uuid.random(),
            blocks = listOf(SerializableTextBlock(Uuid.random(), now, content = "Edited on this phone")),
            selectedJournalIds = emptyList(),
            createdAt = now - 2.days,
            lastModifiedAt = now,
        )

    private fun exportDraft(
        id: Uuid,
        updated: Instant,
    ) = ExportDraft(id = id.toString(), content = "Older archive draft", createdAt = now - 2.days, updatedAt = updated)

    private fun bundle(vararg drafts: ExportDraft): RestoreBundle {
        val metadata =
            ExportMetadata(
                version = ExportSchemaVersion.V1_2,
                exportDate = now,
                userId = "retained-owner",
                deviceId = "previous-installation",
                appVersion = "1.0.0",
                stats = ExportStats(0, 0, drafts.size, 0),
            )
        return RestoreBundle(
            metadataJson = Json.encodeToString(metadata),
            journalsJson = "{\"journals\":[]}",
            notesJson = "{\"notes\":[]}",
            journalNotesJson = "{\"journal_notes\":[]}",
            draftsJson = Json.encodeToString(mapOf("drafts" to drafts.toList())),
        )
    }
}
