package app.logdate.client.domain.export

import app.logdate.client.domain.export.archive.ArchiveExportOptions
import app.logdate.client.domain.export.archive.ArchiveExportProgress
import app.logdate.client.domain.export.archive.support.ArchiveExportFixture
import app.logdate.client.domain.export.archive.support.InMemoryArchiveContainer
import app.logdate.client.domain.export.support.RoundTripJournalContentRepository
import app.logdate.client.domain.export.support.RoundTripJournalNotesRepository
import app.logdate.client.domain.export.support.RoundTripJournalRepository
import app.logdate.client.domain.export.support.RoundTripLocationHistoryRepository
import app.logdate.client.domain.export.support.RoundTripProfileRepository
import app.logdate.client.domain.export.support.RoundTripUserPlacesRepository
import app.logdate.client.domain.restore.MediaImporter
import app.logdate.client.domain.restore.RestoreArchiveBundle
import app.logdate.client.domain.restore.RestoreArchiveReader
import app.logdate.client.domain.restore.RestoreTestDeviceIdProvider
import app.logdate.client.domain.restore.RestoreTestOwnerProvider
import app.logdate.client.domain.restore.RestoreUserDataUseCase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class ArchiveNoteMetadataRoundTripTest : ArchiveExportFixture() {
    @Test
    fun knownEmptyMediaTextIsExplicitInTheArchiveAndRemainsEmptyAfterRestore() =
        runTest {
            val cases = listOf(imageNote.copy(caption = ""), videoNote.copy(caption = ""), audioNote.copy(caption = "", transcription = ""))
            cases.forEach { note ->
                notesRepository.testNotes = listOf(note)
                val archive = InMemoryArchiveContainer()
                assertIs<ArchiveExportProgress.Completed>(useCase().export(ArchiveExportOptions(), archive).toList().last())
                val file = "data/notes.json"
                val encoded =
                    Json
                        .parseToJsonElement(archive.text(file))
                        .jsonObject
                        .getValue("notes")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("", encoded.getValue("caption").jsonPrimitive.content)
                if (note is JournalNote.Audio) assertEquals("", encoded.getValue("transcription").jsonPrimitive.content)
                val restored = roundTrip(note)
                when (restored) {
                    is JournalNote.Image -> {
                        assertEquals("", restored.caption)
                    }

                    is JournalNote.Video -> {
                        assertEquals("", restored.caption)
                    }

                    is JournalNote.Audio -> {
                        assertEquals("", restored.caption)
                        assertEquals("", restored.transcription)
                    }

                    is JournalNote.Text, is JournalNote.Handwriting -> {
                        error("Media note became a non-media note")
                    }
                }
            }
        }

    @Test
    fun videoDurationSurvivesAnUnavailableOriginal() =
        runTest {
            val source = videoNote.copy(durationMs = 12_345L)
            assertEquals(source.durationMs, assertIs<JournalNote.Video>(roundTrip(source)).durationMs)
        }

    @Test
    fun unknownVideoDurationRemainsUnknown() =
        runTest {
            assertEquals(null, assertIs<JournalNote.Video>(roundTrip(videoNote)).durationMs)
        }

    @Test
    fun rawCoordinatesAndSemanticPlaceRemainIndependent() =
        runTest {
            val location =
                NoteLocation(
                    coordinates = NoteCoordinates(36.1699, -115.1398, altitude = 610.25, accuracy = 3.5f),
                    place = NotePlace(Uuid.parse("d7042fbb-485c-44f1-8f74-7083b7074394"), "Home", 36.17, -115.14),
                )
            assertEquals(location, roundTrip(textNote.copy(location = location)).location)
        }

    @Test
    fun unresolvedPlaceIdentitySurvivesWithoutInventedCoordinates() =
        runTest {
            val location = NoteLocation(place = NotePlace(Uuid.parse("d7042fbb-485c-44f1-8f74-7083b7074394")))
            assertEquals(location, roundTrip(textNote.copy(location = location)).location)
        }

    @Test
    fun emptyLocationRemainsEmptyWithoutAnInventedFix() =
        runTest {
            assertEquals(NoteLocation(), roundTrip(textNote.copy(location = NoteLocation())).location)
        }

    @Test
    fun coordinateBoundariesAndZeroAccuracyRemainValid() =
        runTest {
            val location =
                NoteLocation(
                    coordinates = NoteCoordinates(-90.0, 180.0, altitude = -430.0, accuracy = 0.0f),
                    place = NotePlace(Uuid.parse("d7042fbb-485c-44f1-8f74-7083b7074394"), latitude = 90.0, longitude = -180.0),
                )
            assertEquals(location, roundTrip(textNote.copy(location = location)).location)
        }

    private suspend fun roundTrip(note: JournalNote): JournalNote {
        notesRepository.testNotes = listOf(note)
        val archive = InMemoryArchiveContainer()
        val progress = useCase().export(ArchiveExportOptions(), archive).toList()
        assertIs<ArchiveExportProgress.Completed>(progress.last())
        val bundle = RestoreArchiveReader.read(archive.paths) { path -> if (archive.has(path)) archive.text(path) else null }
        assertIs<RestoreArchiveBundle.V2>(bundle)
        val notes = RoundTripJournalNotesRepository()
        val restore =
            RestoreUserDataUseCase(
                RoundTripJournalRepository(),
                notes,
                RoundTripJournalContentRepository(),
                RoundTripProfileRepository(),
                RoundTripUserPlacesRepository(),
                RoundTripLocationHistoryRepository(),
                RestoreTestOwnerProvider(),
                RestoreTestDeviceIdProvider(),
            )
        val result =
            restore.restore(
                bundle,
                mediaImporter =
                    object : MediaImporter {
                        override suspend fun importMedia(exportPath: String): String? =
                            if (archive.has(exportPath)) "file:///restored/${exportPath.substringAfterLast('/')}" else null
                    },
            )
        assertEquals(1, result.notesImported)
        return assertNotNull(notes.getNoteById(note.uid))
    }
}
