package app.logdate.client.data.people

import androidx.room.Room
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.getRoomDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val journalDay: Instant = Instant.parse("2026-03-02T09:00:00Z")

/** Runs [block] against a real database in a temporary file, then deletes the file. */
internal fun withJournalDatabase(block: suspend (LogDateDatabase) -> Unit) =
    runBlocking {
        val file = Files.createTempFile("logdate-people-", ".db")
        val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
        try {
            block(database)
        } finally {
            database.close()
            Files.deleteIfExists(file)
            Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
            Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
        }
    }

internal suspend fun LogDateDatabase.addTextEntry(content: String): Uuid {
    val note = TextNoteEntity(content = content, lastUpdated = journalDay, created = journalDay)
    textNoteDao().addNote(note)
    return note.uid
}
