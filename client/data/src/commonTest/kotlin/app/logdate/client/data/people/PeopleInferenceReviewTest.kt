package app.logdate.client.data.people

import app.logdate.shared.model.InferredPersonClusterStatus
import app.logdate.shared.model.PersonLinkProvenance
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * What counts as a mention, and what a confirmation, a dismissal and a removal leave behind when
 * they repeat, overlap a refresh, or meet a person who has more than one name.
 */
internal class PeopleInferenceReviewTest : PeopleInferenceJournal() {
    @Test
    fun `a name written twice in one entry is not suggested`() =
        runTest {
            addNote("lunch with Sarah, then Sarah drove home")

            store().refresh()

            assertEquals(emptyList(), openSuggestions())
        }

    @Test
    fun `a suggestion goes away when an entry behind it is deleted`() =
        runTest {
            addNote("Dinner with Maya")
            val removed = addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()
            assertEquals(1, openSuggestions().size)

            textNotes.removeNote(removed)
            store.refresh()

            assertEquals(emptyList(), openSuggestions())
            assertTrue(daos.evidence.isEmpty())
        }

    @Test
    fun `a full name that starts a sentence is kept whole when another entry has it inside a sentence`() =
        runTest {
            addNote("Sarah Chen texted me about Friday.")
            addNote("Sarah Chen came over.")
            addNote("Saw Sarah Chen at lunch.")

            store().refresh()

            val suggestion = openSuggestions().single()
            assertEquals("Sarah Chen", suggestion.displayNameHint)
            assertEquals(3, daos.evidence.values.count { it.clusterId == suggestion.id })
        }

    @Test
    fun `the transcript of a deleted voice note is not read`() =
        runTest {
            addNote("Dinner with Dana")
            addVoiceNote("I told Dana about the move", deletedAt = firstDay + 1.days)

            store().refresh()

            assertEquals(emptyList(), openSuggestions())
        }

    @Test
    fun `confirming or dismissing a suggestion that was already confirmed changes nothing`() =
        runTest {
            addNote("Dinner with Maya")
            addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()
            val suggestionId = openSuggestions().single().id
            store.confirmClusterAsPerson(suggestionId)
            val person = onlyPerson()
            val links = daos.links.values.toList()

            store.confirmClusterAsPerson(suggestionId)
            store.rejectCluster(suggestionId)

            assertEquals(person, onlyPerson())
            assertEquals(links, daos.links.values.toList())
            assertEquals("CONFIRM", daos.decisions.getValue("maya").action)
            assertEquals(InferredPersonClusterStatus.RESOLVED.name, daos.clusters.getValue(suggestionId).status)
        }

    @Test
    fun `confirming a name that a person in the directory already has links that person`() =
        runTest {
            val first = addNote("Dinner with Maya")
            val second = addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()
            val existing = person("Maya Okafor", aliases = listOf("Maya"))
            daos.personDao.insert(existing)

            store.confirmClusterAsPerson(openSuggestions().single().id)

            assertEquals(existing, onlyPerson())
            assertEquals(setOf(first, second), daos.linksFor(existing.id).map { it.targetId }.toSet())
        }

    @Test
    fun `a confirmation that overlaps a refresh keeps its person and links`() =
        runTest {
            val first = addNote("Dinner with Maya")
            val second = addNote("Long call with Maya tonight")
            val store = store()
            store.refresh()
            val suggestionId = openSuggestions().single().id

            listOf(
                launch { store.refresh() },
                launch { store.confirmClusterAsPerson(suggestionId) },
                launch { store.refresh() },
            ).joinAll()

            val person = onlyPerson()
            val links = daos.linksFor(person.id)
            assertEquals(setOf(first, second), links.map { it.targetId }.toSet())
            assertTrue(links.all { it.provenance == PersonLinkProvenance.EXPLICIT.name })
            assertEquals(emptyList(), openSuggestions())
        }

    @Test
    fun `removing a person stops their other names being suggested`() =
        runTest {
            val samantha = person("Samantha Lee", aliases = listOf("Sam"))
            daos.personDao.insert(samantha)
            val store = store()

            store.removePerson(samantha.id)
            addNote("texted Sam about the weekend")
            addNote("coffee with Sam")
            addNote("Dinner with Samantha Lee")
            addNote("Called Samantha Lee back")
            store.refresh()

            assertEquals(emptyList(), openSuggestions())
            assertEquals(setOf("sam", "samantha lee"), daos.decisions.keys)
        }

    @Test
    fun `a one-word name that is also an ordinary word is not linked at the start of a sentence`() =
        runTest {
            val will = person("Will")
            daos.personDao.insert(will)
            addNote("Will we ever finish the kitchen?")
            addNote("Will it rain tomorrow?")

            store().refresh()

            assertEquals(emptyList(), daos.linksFor(will.id))
        }

    @Test
    fun `a person named after a month is linked only where the word means the person`() =
        runTest {
            val april = person("April")
            daos.personDao.insert(april)
            addNote("We moved in April and it rained")
            addNote("The lease ends this April")
            val lunch = addNote("Lunch with April near the office")
            val call = addNote("I called April about the trip")

            store().refresh()

            assertEquals(setOf(lunch, call), daos.linksFor(april.id).map { it.targetId }.toSet())
        }
}
