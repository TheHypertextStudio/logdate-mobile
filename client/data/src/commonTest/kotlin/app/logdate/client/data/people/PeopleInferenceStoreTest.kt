package app.logdate.client.data.people

import app.logdate.client.database.entities.EventEntity
import app.logdate.shared.model.PersonLinkProvenance
import app.logdate.shared.model.PersonLinkTargetType
import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

internal class PeopleInferenceStoreTest : PeopleInferenceJournal() {
    @Test
    fun `a name mentioned in one entry is not suggested`() =
        runTest {
            addNote("Lunch with Sarah downtown")

            store().refresh()

            assertEquals(emptyList(), openSuggestions())
        }

    @Test
    fun `a name mentioned on several days is suggested and never added to the directory`() =
        runTest {
            addNote("Lunch with Sarah downtown", firstDay)
            addNote("Called Sarah about the trip", firstDay + 1.days)
            addNote("Hiked with Sarah and her dog", firstDay + 2.days)

            store().refresh()

            val suggestion = openSuggestions().single()
            assertEquals("Sarah", suggestion.displayNameHint)
            assertEquals(3, daos.evidence.values.count { it.clusterId == suggestion.id })
            assertTrue(daos.people.isEmpty())
            assertTrue(daos.links.isEmpty())
        }

    @Test
    fun `ordinary capitalized words are not suggested`() =
        runTest {
            repeat(2) {
                addNote("Today I had lunch with Sarah at Blue Bottle. The weather was great.")
                addNote("We drove to Santa Cruz in December. My sister called.")
            }

            store().refresh()

            val suggestedNames = openSuggestions().map { it.displayNameHint }
            assertTrue("Sarah" in suggestedNames)
            listOf("Today", "The", "We", "My", "December").forEach { word ->
                assertFalse(word in suggestedNames, "$word must not be suggested")
            }
        }

    @Test
    fun `a suggestion shows the text around the name`() =
        runTest {
            val filler = "we walked for a long while and talked about nothing in particular ".repeat(4)
            addNote(filler + "until Priya called from the station " + filler)
            addNote("coffee with Priya")

            store().refresh()

            val previews = daos.evidence.values.mapNotNull { it.label }
            assertEquals(2, previews.size)
            assertTrue(previews.all { "Priya" in it })
        }

    @Test
    fun `a rejected name is not suggested again`() =
        runTest {
            addNote("Coffee at Blue Bottle")
            addNote("Back to Blue Bottle for a meeting")
            val store = store()
            store.refresh()

            store.rejectCluster(openSuggestions().single().id)
            addNote("Morning walk to Blue Bottle")
            store.refresh()

            assertEquals(emptyList(), openSuggestions())
            assertTrue(daos.people.isEmpty())
        }

    @Test
    fun `confirming a suggestion adds the person with a link to every mention`() =
        runTest {
            val first = addNote("Dinner with Maya")
            val second = addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()

            store.confirmClusterAsPerson(openSuggestions().single().id)

            val person = onlyPerson()
            assertEquals("Maya", person.name)
            assertEquals(PersonOrigin.INFERRED.name, person.origin)
            assertEquals(setOf(first, second), daos.linksFor(person.id).map { it.targetId }.toSet())
            assertEquals(emptyList(), openSuggestions())
        }

    @Test
    fun `links made by confirming survive a later refresh`() =
        runTest {
            addNote("Dinner with Maya")
            addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()
            store.confirmClusterAsPerson(openSuggestions().single().id)
            val confirmedLinks = daos.links.values.toList()

            store.refresh()

            assertEquals(confirmedLinks, daos.links.values.toList())
            assertTrue(confirmedLinks.all { it.provenance == PersonLinkProvenance.EXPLICIT.name })
            assertEquals(1, daos.people.size)
        }

    @Test
    fun `a confirmed link survives when the name is down to one mention`() =
        runTest {
            val kept = addNote("Dinner with Maya")
            val removed = addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()
            store.confirmClusterAsPerson(openSuggestions().single().id)

            textNotes.removeNote(removed)
            store.refresh()

            val person = onlyPerson()
            assertTrue(daos.linksFor(person.id).any { it.targetId == kept })
        }

    @Test
    fun `a later mention of a confirmed person is linked without replacing confirmed links`() =
        runTest {
            addNote("Dinner with Maya")
            addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()
            store.confirmClusterAsPerson(openSuggestions().single().id)

            val later = addNote("Picnic with Maya by the lake")
            store.refresh()

            val links = daos.linksFor(onlyPerson().id)
            assertEquals(3, links.size)
            assertEquals(PersonLinkProvenance.INFERRED.name, links.single { it.targetId == later }.provenance)
            assertEquals(2, links.count { it.provenance == PersonLinkProvenance.EXPLICIT.name })
            assertEquals(emptyList(), openSuggestions())
        }

    @Test
    fun `transcripts and events count as mentions and are linked on confirmation`() =
        runTest {
            val audioNoteId = addVoiceNote("we talked with Priya for an hour")
            val eventId = Uuid.random()
            events.insert(
                EventEntity(
                    id = eventId,
                    title = "Dinner with Priya",
                    startTime = firstDay,
                    created = firstDay,
                    lastUpdated = firstDay,
                ),
            )
            val store = store(readsEvents = true)
            store.refresh()

            store.confirmClusterAsPerson(openSuggestions().single().id)

            val targets = daos.links.values.associate { it.targetId to it.targetType }
            assertEquals(
                mapOf(audioNoteId to PersonLinkTargetType.ENTRY.name, eventId to PersonLinkTargetType.EVENT.name),
                targets,
            )
        }

    @Test
    fun `events are not read for names while Events has not launched`() =
        runTest {
            addNote("Lunch with Priya downtown")
            events.insert(
                EventEntity(
                    id = Uuid.random(),
                    title = "Dinner with Priya",
                    startTime = firstDay,
                    created = firstDay,
                    lastUpdated = firstDay,
                ),
            )

            store(readsEvents = false).refresh()

            assertEquals(emptyList(), openSuggestions())
        }

    @Test
    fun `a refresh keeps the identifier of an open suggestion`() =
        runTest {
            addNote("Dinner with Maya")
            addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()
            val suggestionId = openSuggestions().single().id

            store.refresh()
            store.confirmClusterAsPerson(suggestionId)

            assertEquals("Maya", onlyPerson().name)
        }

    @Test
    fun `refreshes that start together leave one suggestion for a name`() =
        runTest {
            addNote("Dinner with Maya")
            addNote("Long call with Maya tonight")
            val store = store()

            List(3) { launch { store.refresh() } }.joinAll()

            assertEquals(1, openSuggestions().size)
            assertEquals(2, daos.evidence.size)
        }

    @Test
    fun `removing a person hides them and stops their name being suggested`() =
        runTest {
            addNote("Dinner with Maya")
            addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()
            store.confirmClusterAsPerson(openSuggestions().single().id)
            val personId = onlyPerson().id

            store.removePerson(personId)
            addNote("Picnic with Maya by the lake")
            store.refresh()

            assertNull(daos.personDao.getById(personId))
            assertEquals(emptyList(), daos.linksFor(personId))
            assertEquals(emptyList(), openSuggestions())
        }

    @Test
    fun `removing a person who is not in the directory does nothing`() =
        runTest {
            store().removePerson(Uuid.random())

            assertTrue(daos.people.isEmpty())
            assertTrue(daos.decisions.isEmpty())
        }

    @Test
    fun `a person in the directory is linked only where their name is capitalized`() =
        runTest {
            val will = person("Will")
            daos.personDao.insert(will)
            addNote("I will call the bank tomorrow")
            addNote("we will see how it goes")
            val lunch = addNote("Lunch with Will near the office")
            val walk = addNote("Evening walk with Will")

            store().refresh()

            assertEquals(setOf(lunch, walk), daos.linksFor(will.id).map { it.targetId }.toSet())
        }

    @Test
    fun `a person in the directory is not suggested again under their name or alias`() =
        runTest {
            daos.personDao.insert(person("Maya Okafor", aliases = listOf("Maya")))
            addNote("Dinner with Maya")
            addNote("Long call with Maya Okafor tonight")
            addNote("Picnic with Maya by the lake")

            store().refresh()

            assertEquals(emptyList(), openSuggestions())
        }

    @Test
    fun `deleted entries are not read`() =
        runTest {
            addNote("Dinner with Maya")
            addNote("Long call with Maya tonight", deletedAt = firstDay + 1.days)

            store().refresh()

            assertEquals(emptyList(), openSuggestions())
        }
}
