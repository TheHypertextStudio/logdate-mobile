package app.logdate.client.sync.conflict

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.TextConflictVersions
import app.logdate.client.sync.metadata.UploadScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SyncReviewInventoryTest {
    private val scope = UploadScope("synthetic-owner", "https://sync.example")
    private val metadata = SyncConflictRecord("old", "NOTE", "entry", 1, null, 1, null, "conflict", 1)
    private val note =
        JournalNote.Text(
            Uuid.random(),
            Instant.fromEpochMilliseconds(1),
            Instant.fromEpochMilliseconds(1),
            "Synthetic writing",
        )
    private val edit = metadata.copy(id = "edit", scope = scope, deletedEdit = DeletedEdit(note = note))

    @Test
    fun `metadata backlog is preserved separately from edits needing a choice`() {
        val records = (1..1268).map { metadata.copy(id = "old-$it") } + edit
        val inventory = syncReviewInventory(records, scope)
        assertEquals(listOf(edit), inventory.edits)
        assertEquals(1268, inventory.earlierIssues)
        assertEquals(1269, records.size)
    }

    @Test
    fun `resolved records and another account do not become current review work`() {
        val records = listOf(edit.copy(recovered = true), edit.copy(scope = scope.copy(ownerId = "another-owner")))
        assertEquals(SyncReviewInventory(), syncReviewInventory(records, scope))
        assertEquals(SyncReviewInventory(), syncReviewInventory(listOf(edit), null))
    }

    @Test
    fun `exact replay duplicates do not inflate the visible count or list`() {
        assertEquals(SyncReviewInventory(listOf(edit), 1), syncReviewInventory(listOf(edit, edit, metadata, metadata), scope))
    }

    @Test
    fun `different saved versions remain separate review work`() {
        val pair = TextConflictVersions(note, note.copy(content = "Different synthetic writing"), "local", "remote")
        val first = metadata.copy(id = "pair", scope = scope, textVersions = pair)
        val second = first.copy(textVersions = pair.copy(remote = pair.remote.copy(content = "Later writing")))
        assertEquals(listOf(first, second), syncReviewInventory(listOf(first, second), scope).edits)
    }
}
