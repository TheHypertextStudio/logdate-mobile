package app.logdate.client.data.people

import app.logdate.client.data.fakes.FakeAudioNoteDao
import app.logdate.client.data.fakes.FakeEventDao
import app.logdate.client.data.fakes.FakeTextNoteDao
import app.logdate.client.data.transcription.FakeTranscriptionDao
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.TranscriptionEntity
import app.logdate.client.database.entities.TranscriptionStatus
import app.logdate.client.database.entities.people.InferredPersonClusterEntity
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.shared.model.InferredPersonClusterStatus
import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A journal held in memory, with the store that looks for names in it. Writes run in place: the
 * rollback of an interrupted write is covered against the real database.
 */
internal abstract class PeopleInferenceJournal {
    protected val daos = FakePeopleDaos()
    protected val textNotes = FakeTextNoteDao()
    protected val audioNotes = FakeAudioNoteDao()
    protected val transcriptions = FakeTranscriptionDao()
    protected val events = FakeEventDao()
    protected val firstDay = Instant.parse("2026-03-02T09:00:00Z")

    protected fun TestScope.store(readsEvents: Boolean = false): PeopleInferenceStore =
        PeopleInferenceStore(
            personDao = daos.personDao,
            clusterDao = daos.clusterDao,
            evidenceDao = daos.evidenceDao,
            linkDao = daos.linkDao,
            decisionDao = daos.decisionDao,
            textNoteDao = textNotes,
            audioNoteDao = audioNotes,
            transcriptionDao = transcriptions,
            eventDao = events,
            transactions = WritesInPlace,
            dispatcher = StandardTestDispatcher(testScheduler),
            readsEvents = readsEvents,
        )

    protected fun openSuggestions(): List<InferredPersonClusterEntity> = daos.clustersWithStatus(InferredPersonClusterStatus.OPEN.name)

    protected fun onlyPerson(): PersonEntity = daos.people.values.single()

    protected suspend fun addNote(
        content: String,
        created: Instant = firstDay,
        deletedAt: Instant? = null,
    ): Uuid {
        val note = TextNoteEntity(content = content, lastUpdated = created, created = created, deletedAt = deletedAt)
        textNotes.addNote(note)
        return note.uid
    }

    protected suspend fun addVoiceNote(
        transcript: String,
        deletedAt: Instant? = null,
    ): Uuid {
        val note = AudioNoteEntity(contentUri = "file:///voice-note.m4a", lastUpdated = firstDay, created = firstDay, deletedAt = deletedAt)
        audioNotes.addNote(note)
        transcriptions.insertTranscription(
            TranscriptionEntity(
                noteId = note.uid,
                text = transcript,
                status = TranscriptionStatus.COMPLETED,
                created = firstDay,
                lastUpdated = firstDay,
            ),
        )
        return note.uid
    }

    protected fun person(
        name: String,
        aliases: List<String> = emptyList(),
    ): PersonEntity =
        PersonEntity(
            id = Uuid.random(),
            name = name,
            aliases = aliases,
            origin = PersonOrigin.MANUAL.name,
            created = firstDay,
            lastUpdated = firstDay,
        )
}

private object WritesInPlace : SyncTransactionManager {
    override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
}
