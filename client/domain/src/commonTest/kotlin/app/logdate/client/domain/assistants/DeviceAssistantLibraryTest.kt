package app.logdate.client.domain.assistants

import app.logdate.client.domain.fakes.FakeJournalRepository
import app.logdate.client.domain.fakes.FakeSearchRepository
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantContentScope
import app.logdate.client.repository.assistants.AssistantSourceKind
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.shared.model.Journal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class DeviceAssistantLibraryTest {
    private val travel = Journal(id = id(1), title = "Travel")
    private val work = Journal(id = id(2), title = "Work")
    private val travelEntry = result(10, "canal walk in Utrecht", SearchContentType.TEXT_NOTE)
    private val workEntry = result(11, "canal project notes", SearchContentType.TEXT_NOTE)
    private val unfiledEntry = result(12, "canal at night", SearchContentType.TEXT_NOTE)
    private val travelVoiceNote = result(13, "canal boats were loud", SearchContentType.TRANSCRIPTION)
    private val place = result(20, "Canal House cafe", SearchContentType.PLACE)
    private val person = result(21, "Sam from the canal club", SearchContentType.PERSON)
    private val rewind = result(30, "Your canal week", SearchContentType.REWIND)
    private val caption = result(31, "canal at sunset", SearchContentType.MEDIA_CAPTION)
    private val travelJournal = result(1, "Travel canal trips", SearchContentType.JOURNAL)
    private val links =
        listOf(
            travel.id to travelEntry.uid,
            travel.id to travelVoiceNote.uid,
            work.id to workEntry.uid,
        )

    private val search =
        FakeSearchRepository(
            listOf(rewind, caption, workEntry, travelEntry, unfiledEntry, travelVoiceNote, place, person, travelJournal),
        )
    private val library = DeviceAssistantLibrary(search, FakeJournalRepository(listOf(travel, work)), { links })

    @Test
    fun `nothing is found until the person allows it`() =
        runTest {
            assertTrue(library.search(DeviceAssistantAccess(), "canal", 10).isEmpty())
            assertTrue(library.journals(DeviceAssistantAccess()).isEmpty())
        }

    @Test
    fun `entries and voice notes come only from shared journals`() =
        runTest {
            val access = entries(AssistantContentScope(setOf(travel.id)))
            val found = library.search(access, "canal", 10)
            assertEquals(listOf(travelEntry.uid, travelVoiceNote.uid, travelJournal.uid), found.map { it.id })
            assertEquals(
                listOf(AssistantSourceKind.TEXT, AssistantSourceKind.TRANSCRIPT, AssistantSourceKind.JOURNAL),
                found.map { it.kind },
            )
        }

    @Test
    fun `entries outside any journal need their own consent`() =
        runTest {
            val withUnfiled = entries(AssistantContentScope(emptySet(), includeUnfiled = true))
            assertEquals(listOf(unfiledEntry.uid), library.search(withUnfiled, "canal", 10).map { it.id })
        }

    @Test
    fun `places and people follow their own permissions`() =
        runTest {
            val places = DeviceAssistantAccess(capabilities = setOf(AssistantCapability.READ_PLACES))
            val people = DeviceAssistantAccess(capabilities = setOf(AssistantCapability.READ_PEOPLE))
            assertEquals(listOf(place.uid), library.search(places, "canal", 10).map { it.id })
            assertEquals(listOf(person.uid), library.search(people, "canal", 10).map { it.id })
        }

    @Test
    fun `rewinds captions and other generated content are never shared`() =
        runTest {
            val everything =
                DeviceAssistantAccess(
                    capabilities = AssistantCapability.entries.toSet(),
                    contentScope = AssistantContentScope(setOf(travel.id, work.id), includeUnfiled = true),
                )
            val ids = library.search(everything, "canal", 50).map { it.id }
            assertTrue(rewind.uid !in ids && caption.uid !in ids)
        }

    @Test
    fun `the limit counts only what the person shared`() =
        runTest {
            // The rewind, caption and work entry rank first but are not shared.
            val access = entries(AssistantContentScope(setOf(travel.id)))
            assertEquals(listOf(travelEntry.uid, travelVoiceNote.uid), library.search(access, "canal", 2).map { it.id })
        }

    @Test
    fun `only shared journals are listed`() =
        runTest {
            assertEquals(listOf(travel), library.journals(entries(AssistantContentScope(setOf(travel.id)))))
            val placesOnly = DeviceAssistantAccess(capabilities = setOf(AssistantCapability.READ_PLACES))
            assertTrue(library.journals(placesOnly).isEmpty())
        }

    private fun entries(scope: AssistantContentScope) =
        DeviceAssistantAccess(capabilities = setOf(AssistantCapability.READ_MEMORIES), contentScope = scope)

    private fun result(
        number: Int,
        content: String,
        type: SearchContentType,
    ) = SearchResult(uid = id(number), content = content, created = Instant.parse("2026-10-01T12:00:00Z"), contentType = type)

    private fun id(number: Int) = Uuid.parse("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
}
