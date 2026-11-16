package app.logdate.client.data.people

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeopleNameExtractionTest {
    @Test
    fun `sentence openers are not treated as names`() {
        val names = namesIn("Today I had lunch with Sarah at Blue Bottle. The weather was great.")

        assertTrue("Sarah" in names)
        assertFalse("Today" in names)
        assertFalse("The" in names)
    }

    @Test
    fun `pronouns and months are not treated as names`() {
        val names = namesIn("We drove to Santa Cruz in December. My sister called.")

        assertFalse("We" in names)
        assertFalse("My" in names)
        assertFalse("December" in names)
    }

    @Test
    fun `weekdays in the middle of a sentence are not treated as names`() {
        assertEquals(listOf("Priya"), namesIn("we met Priya on Tuesday and again on Friday"))
    }

    @Test
    fun `the first word of a sentence is not a name on its own`() {
        assertEquals(emptyList(), namesIn("Painting took all afternoon. Laundry is still not done."))
    }

    @Test
    fun `the first word of a sentence is a name when it is also capitalized inside a sentence`() {
        assertEquals(listOf("Maya"), namesIn("Maya came over. Later we walked with Maya to the pier."))
    }

    @Test
    fun `a full name at the start of a sentence is kept whole when its first word recurs`() {
        assertEquals(
            listOf("Maya Okafor", "Maya"),
            namesIn("Maya Okafor called. I told Maya about the trip."),
        )
    }

    @Test
    fun `a name before a line break is found`() {
        assertEquals(listOf("Maya"), namesIn("Dinner with Maya\nThen a movie"))
    }

    @Test
    fun `names on both sides of a line break are found separately`() {
        assertEquals(listOf("Maya", "Jonah"), namesIn("- coffee with Maya\n- call with Jonah\r\n- gym"))
    }

    @Test
    fun `an ignored word is never joined to the name after it`() {
        assertEquals(listOf("Sarah"), namesIn("After Sarah left we cleaned up"))
        assertEquals(listOf("Sarah"), namesIn("we saw that on Monday Sarah was away"))
    }

    @Test
    fun `an ignored word is never joined to the name before it`() {
        assertEquals(listOf("Sarah"), namesIn("we are seeing Sarah Sunday"))
    }

    @Test
    fun `names with accented letters are found`() {
        assertEquals(listOf("José", "Zoë"), namesIn("Coffee with José and Zoë."))
    }

    @Test
    fun `a possessive is reported without its ending`() {
        assertEquals(listOf("Sarah"), namesIn("we went to Sarah's place"))
        assertEquals(listOf("Sarah", "Portland"), namesIn("we went to Sarah’s Portland flat"))
    }

    @Test
    fun `contractions are not treated as names`() {
        assertEquals(emptyList(), namesIn("so I'm sure that Don't and I've were typed with capitals"))
    }

    @Test
    fun `a name after a title is found`() {
        assertEquals(listOf("Patel"), namesIn("Dr. Patel said the results were fine"))
        assertEquals(listOf("Okafor"), namesIn("we met Mrs. Okafor at the school"))
    }

    @Test
    fun `hyphenated and apostrophe names are kept whole`() {
        assertEquals(listOf("Jean-Luc", "O'Brien"), namesIn("we met Jean-Luc and then O'Brien"))
    }

    @Test
    fun `words in capitals and mixed case are not treated as names`() {
        assertEquals(emptyList(), namesIn("we used LogDate on the NASA tour with an iPhone and YouTube"))
    }

    @Test
    fun `names with a second capital after a name prefix are found`() {
        assertEquals(
            listOf("McKenzie", "DeShawn", "LeBron", "MacDonald"),
            namesIn("we met McKenzie, then DeShawn, then LeBron and Mrs. MacDonald"),
        )
    }

    @Test
    fun `holidays nationalities and network names are not treated as names`() {
        assertEquals(emptyList(), namesIn("we spent Christmas and Thanksgiving eating Italian and Thai food on bad Wi-Fi"))
    }

    @Test
    fun `a nationality is never joined to the name before it`() {
        assertEquals(listOf("Sam"), namesIn("dinner with Sam Italian place downtown"))
    }

    @Test
    fun `a month that is also a given name is a name right after a word that precedes a person`() {
        assertEquals(listOf("April"), namesIn("lunch with April by the river"))
        assertEquals(listOf("June", "May"), namesIn("we met June and later called May"))
    }

    @Test
    fun `a month is not a name where it is a date`() {
        assertEquals(emptyList(), namesIn("we moved in April and visited again this May"))
        assertEquals(emptyList(), namesIn("we met April 5 and left by June"))
        assertEquals(emptyList(), namesIn("lunch with December plans"))
    }

    @Test
    fun `the first word of a sentence counts when the journal has it inside a sentence elsewhere`() {
        val elsewhere = wordsCapitalizedInsideSentence(tokenizeWords("Saw Sarah Chen at lunch."))

        val names = findPotentialPersonMentions(tokenizeWords("Sarah Chen texted me."), elsewhere).map(NameMention::name)

        assertEquals(listOf("Sarah Chen"), names)
    }

    @Test
    fun `a given name that is also an ordinary word is not a name at the start of a sentence`() {
        val elsewhere = wordsCapitalizedInsideSentence(tokenizeWords("lunch with Will"))

        val names = findPotentialPersonMentions(tokenizeWords("Will we ever finish?"), elsewhere).map(NameMention::name)

        assertEquals(emptyList(), names)
    }

    @Test
    fun `a run of more than three capitalized words is not treated as a name`() {
        assertEquals(emptyList(), namesIn("we painted it Blue Green Yellow Purple together"))
    }

    @Test
    fun `a name is reported once per text with the position of its first mention`() {
        val text = "lunch with Sarah, then Sarah drove home"

        val mention = findPotentialPersonMentions(tokenizeWords(text)).single()

        assertEquals("Sarah", mention.name)
        assertEquals("Sarah", text.substring(mention.start, mention.endExclusive))
        assertEquals(text.indexOf("Sarah"), mention.start)
    }

    @Test
    fun `the preview of a short text is the whole text on one line`() {
        val text = "Dinner with Maya\n\nThen a movie"
        val mention = findPotentialPersonMentions(tokenizeWords(text)).single()

        assertEquals("Dinner with Maya Then a movie", previewEvidenceLabel(text, mention))
    }

    @Test
    fun `the preview of a long text is built around the name`() {
        val text = "word ".repeat(80) + "and then we met Sarah at the station " + "after ".repeat(80)
        val mention = findPotentialPersonMentions(tokenizeWords(text)).single()

        val preview = previewEvidenceLabel(text, mention)

        assertTrue("met Sarah at" in preview)
        assertTrue(preview.startsWith("…"))
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.length <= 122)
    }

    @Test
    fun `the preview does not cut a word in half`() {
        val text = "alpha ".repeat(40) + "Sarah " + "omega ".repeat(40)
        val mention = findPotentialPersonMentions(tokenizeWords(text)).single()

        val words = previewEvidenceLabel(text, mention).trim('…').split(' ')

        assertTrue(words.all { it == "alpha" || it == "Sarah" || it == "omega" })
    }

    private fun namesIn(text: String): List<String> = findPotentialPersonMentions(tokenizeWords(text)).map(NameMention::name)
}
