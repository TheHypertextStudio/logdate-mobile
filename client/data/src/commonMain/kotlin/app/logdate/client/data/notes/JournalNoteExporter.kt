package app.logdate.client.data.notes

import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.ImageNoteDao
import app.logdate.client.database.dao.MediaCaptionDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.VideoNoteDao
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.transcription.TranscriptionRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Exports notes and their journal memberships within a time window to a JSON backup file.
 */
internal class JournalNoteExporter(
    private val textNoteDao: TextNoteDao,
    private val imageNoteDao: ImageNoteDao,
    private val audioNoteDao: AudioNoteDao,
    private val videoNoteDao: VideoNoteDao,
    private val mediaCaptionDao: MediaCaptionDao,
    private val journalRepository: JournalRepository,
    private val transcriptionRepository: TranscriptionRepository?,
    private val queries: JournalNoteQueries,
) {
    suspend fun exportContentToFile(
        destination: String,
        overwrite: Boolean,
        startTimestamp: Instant,
        endTimestamp: Instant,
    ) {
        val allNotes = notesInRange(startTimestamp, endTimestamp)
        val journalToNotesMap = journalMemberships()

        // Create export structure
        val backup =
            JournalContentBackup(
                notes = allNotes,
                journalToNotesMap = journalToNotesMap,
                generated = Clock.System.now(),
            )

        // Serialize to JSON
        val json =
            Json {
                prettyPrint = true
                encodeDefaults = true
            }
        val jsonContent = json.encodeToString(backup)

        writeExportFile(destination, jsonContent, overwrite)
    }

    private suspend fun notesInRange(
        startTimestamp: Instant,
        endTimestamp: Instant,
    ): List<JournalNote> {
        // Get all notes within the time range
        val textNotes =
            textNoteDao
                .getNotesInRange(
                    startTimestamp.toEpochMilliseconds(),
                    endTimestamp.toEpochMilliseconds(),
                ).first()
                .map { it.toModel() }

        val imageNotes =
            imageNoteDao
                .getNotesInRange(
                    startTimestamp.toEpochMilliseconds(),
                    endTimestamp.toEpochMilliseconds(),
                ).first()
                .map { it.toModel() }

        val audioNotes =
            audioNoteDao
                .getNotesInRange(
                    startTimestamp.toEpochMilliseconds(),
                    endTimestamp.toEpochMilliseconds(),
                ).first()
                .map { it.toModel() }

        val videoNotes =
            videoNoteDao
                .getNotesInRange(
                    startTimestamp.toEpochMilliseconds(),
                    endTimestamp.toEpochMilliseconds(),
                ).first()
                .map { it.toModel() }

        // Combine all notes
        val captionMap = mediaCaptionDao.observeAll().first().associate { it.noteId to it.caption }
        return (textNotes + imageNotes + audioNotes + videoNotes).map {
            it.withCaption(captionMap).withTranscript(transcriptionRepository)
        }
    }

    private suspend fun journalMemberships(): Map<Uuid, List<Uuid>> {
        // Get all journals
        val journals = journalRepository.allJournalsObserved.first()

        // Create a map of journal ID to note IDs
        val journalToNotesMap = mutableMapOf<Uuid, List<Uuid>>()

        // For each journal, get all its notes and add them to the map
        journals.forEach { journal ->
            // Get notes for this journal
            val journalNotes = queries.observeNotesInJournal(journal.id).first()

            // Map to just the IDs
            val noteIds = journalNotes.map { it.uid }

            // Only add to map if there are notes
            if (noteIds.isNotEmpty()) {
                journalToNotesMap[journal.id] = noteIds
            }
        }

        return journalToNotesMap
    }
}
