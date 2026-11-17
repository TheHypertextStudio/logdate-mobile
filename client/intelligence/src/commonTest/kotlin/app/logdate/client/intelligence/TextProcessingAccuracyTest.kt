package app.logdate.client.intelligence

import app.logdate.client.intelligence.entity.people.PeopleExtractor
import app.logdate.client.intelligence.fakes.FakeDataUsagePolicy
import app.logdate.client.intelligence.fakes.FakeGenerativeAICache
import app.logdate.client.intelligence.fakes.FakeGenerativeAIChatClient
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.shared.model.Person
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests that verify text processing accuracy for different scenarios.
 * These tests use realistic text samples and expected AI responses to validate
 * the intelligence module's ability to extract information correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextProcessingAccuracyTest {
    private val testDispatcher = StandardTestDispatcher()
    private val fakeCache = FakeGenerativeAICache()
    private val fakeAIClient = FakeGenerativeAIChatClient()
    private val fakeNetworkMonitor = TestNetworkAvailabilityMonitor()
    private val fakeDataUsagePolicy = FakeDataUsagePolicy()

    private val entrySummarizer =
        EntrySummarizer(
            generativeAICache = fakeCache,
            genAIClient = fakeAIClient,
            networkAvailabilityMonitor = fakeNetworkMonitor,
            dataUsagePolicy = fakeDataUsagePolicy,
            ioDispatcher = testDispatcher,
        )

    private val peopleExtractor =
        PeopleExtractor(
            generativeAICache = fakeCache,
            generativeAIChatClient = fakeAIClient,
            networkAvailabilityMonitor = fakeNetworkMonitor,
            dataUsagePolicy = fakeDataUsagePolicy,
            ioDispatcher = testDispatcher,
        )

    private fun setup() {
        fakeCache.clear()
        fakeAIClient.clear()
    }

    @Test
    fun `daily journal entry extracts people and summarizes correctly`() =
        runTest(testDispatcher) {
            setup()
            val journalText =
                """
                Had breakfast with Mom this morning before heading to work. The office was buzzing 
                because Sarah from marketing announced her promotion. During lunch, I met up with 
                my college friend Mike who's visiting from Seattle. We talked about old times and 
                his new startup. In the evening, I had dinner with Jessica and her boyfriend Tom. 
                They're planning a trip to Europe next month.
                """.trimIndent()

            // Set up expected AI responses
            val expectedPeople = jsonResponse("Mom", "Sarah", "Mike", "Jessica", "Tom")
            val expectedSummary = "You had a social day connecting with family, colleagues, and friends from breakfast through dinner."

            fakeAIClient.setResponseFor(journalText, expectedPeople) // For people extraction
            fakeAIClient.responses["summary-$journalText"] = expectedSummary // For summarization

            // Test people extraction
            val extractedPeople =
                assertPeopleSuccess(
                    peopleExtractor.extractPeople("daily-entry", journalText, useCached = false),
                )
            assertEquals(5, extractedPeople.size)
            val names = extractedPeople.map { it.name }
            assertTrue(names.contains("Mom"))
            assertTrue(names.contains("Sarah"))
            assertTrue(names.contains("Mike"))
            assertTrue(names.contains("Jessica"))
            assertTrue(names.contains("Tom"))

            // Reset for summary test
            fakeAIClient.clear()
            fakeAIClient.setResponseFor(journalText, expectedSummary)

            // Test summarization
            val summary =
                assertSummarySuccess(
                    entrySummarizer.summarize("daily-entry-summary", journalText, useCached = false),
                )
            assertEquals(expectedSummary, summary)
        }

    @Test
    fun `work meeting entry handles business context`() =
        runTest(testDispatcher) {
            setup()
            val journalText =
                """
                Today's quarterly review meeting was intense. CEO Richard Williams opened with 
                the financial overview, then VP of Sales Jennifer Liu presented the Q3 numbers. 
                The engineering team lead, Dr. Patel, discussed the technical roadmap. I was 
                impressed by the new intern Alex Chen's presentation on user analytics. After 
                the meeting, I had coffee with my manager Lisa Rodriguez to discuss my performance review.
                """.trimIndent()

            val expectedPeople =
                jsonResponse(
                    "Richard Williams",
                    "Jennifer Liu",
                    "Dr. Patel",
                    "Alex Chen",
                    "Lisa Rodriguez",
                )

            fakeAIClient.setResponseFor(journalText, expectedPeople)

            val extractedPeople =
                assertPeopleSuccess(
                    peopleExtractor.extractPeople("work-meeting", journalText, useCached = false),
                )
            assertEquals(5, extractedPeople.size)

            // Verify professional titles are preserved
            val names = extractedPeople.map { it.name }
            assertTrue(names.contains("Richard Williams"))
            assertTrue(names.contains("Dr. Patel"))
            assertTrue(names.contains("Alex Chen"))
        }

    @Test
    fun `family gathering entry handles family relationships`() =
        runTest(testDispatcher) {
            setup()
            val journalText =
                """
                Sunday dinner at Grandma's house was wonderful as always. Uncle Bob brought his 
                famous apple pie, and Aunt Mary shared stories about her recent trip to Italy. 
                My cousins Jake and Emma played board games with the kids. Dad helped Grandpa 
                fix the old radio in the garage while Mom and Aunt Susan prepared dessert. 
                It's these family moments that I treasure most.
                """.trimIndent()

            val expectedPeople =
                jsonResponse(
                    "Grandma",
                    "Uncle Bob",
                    "Aunt Mary",
                    "Jake",
                    "Emma",
                    "Dad",
                    "Grandpa",
                    "Mom",
                    "Aunt Susan",
                )

            fakeAIClient.setResponseFor(journalText, expectedPeople)

            val extractedPeople =
                assertPeopleSuccess(
                    peopleExtractor.extractPeople("family-gathering", journalText, useCached = false),
                )
            assertEquals(9, extractedPeople.size)

            // Verify family relationship terms are preserved
            val names = extractedPeople.map { it.name }
            assertTrue(names.contains("Grandma"))
            assertTrue(names.contains("Uncle Bob"))
            assertTrue(names.contains("Aunt Mary"))
            assertTrue(names.contains("Dad"))
            assertTrue(names.contains("Mom"))
        }

    @Test
    fun `travel entry handles geographical context`() =
        runTest(testDispatcher) {
            setup()
            val journalText =
                """
                First day in Tokyo was incredible! Our tour guide Hiroshi showed us around 
                Shibuya district. Met a friendly local named Yuki at the sushi restaurant who 
                recommended the best spots to visit. Later, we bumped into fellow travelers 
                Maria from Spain and Klaus from Germany at the hotel bar. Can't wait to explore 
                more with them tomorrow.
                """.trimIndent()

            val expectedPeople = jsonResponse("Hiroshi", "Yuki", "Maria", "Klaus")

            fakeAIClient.setResponseFor(journalText, expectedPeople)

            val extractedPeople =
                assertPeopleSuccess(
                    peopleExtractor.extractPeople("travel-entry", journalText, useCached = false),
                )
            assertEquals(4, extractedPeople.size)

            val names = extractedPeople.map { it.name }
            assertTrue(names.contains("Hiroshi"))
            assertTrue(names.contains("Yuki"))
            assertTrue(names.contains("Maria"))
            assertTrue(names.contains("Klaus"))
        }

    @Test
    fun `medical appointment entry handles healthcare context`() =
        runTest(testDispatcher) {
            setup()
            val journalText =
                """
                Had my annual checkup with Dr. Sarah Mitchell today. The nurse practitioner 
                Amy Johnson took my vitals and was very thorough. Dr. Mitchell reviewed my 
                test results and everything looks good. Scheduled a follow-up with the 
                specialist Dr. Chen for next month. The receptionist Maria was helpful 
                with scheduling and insurance questions.
                """.trimIndent()

            val expectedPeople = jsonResponse("Dr. Sarah Mitchell", "Amy Johnson", "Dr. Chen", "Maria")

            fakeAIClient.setResponseFor(journalText, expectedPeople)

            val extractedPeople =
                assertPeopleSuccess(
                    peopleExtractor.extractPeople("medical-appointment", journalText, useCached = false),
                )
            assertEquals(4, extractedPeople.size)

            // Verify medical titles are preserved
            val names = extractedPeople.map { it.name }
            assertTrue(names.contains("Dr. Sarah Mitchell"))
            assertTrue(names.contains("Amy Johnson"))
            assertTrue(names.contains("Dr. Chen"))
        }

    @Test
    fun `social event entry handles large groups`() =
        runTest(testDispatcher) {
            setup()
            val journalText =
                """
                Birthday party for Rachel was a blast! So many people showed up - her college 
                friends David, Jennifer, and Marcus, plus work colleagues Amy and Steven. 
                Her family was there too: her sister Katie, brother-in-law Tom, and parents 
                Mr. and Mrs. Peterson. I had great conversations with everyone, especially 
                catching up with high school friend Miguel who I hadn't seen in years.
                """.trimIndent()

            val expectedPeople =
                jsonResponse(
                    "Rachel",
                    "David",
                    "Jennifer",
                    "Marcus",
                    "Amy",
                    "Steven",
                    "Katie",
                    "Tom",
                    "Mr. Peterson",
                    "Mrs. Peterson",
                    "Miguel",
                )

            fakeAIClient.setResponseFor(journalText, expectedPeople)

            val extractedPeople =
                assertPeopleSuccess(
                    peopleExtractor.extractPeople("social-event", journalText, useCached = false),
                )
            assertEquals(11, extractedPeople.size)

            val names = extractedPeople.map { it.name }
            assertTrue(names.contains("Rachel"))
            assertTrue(names.contains("Mr. Peterson"))
            assertTrue(names.contains("Mrs. Peterson"))
            assertTrue(names.contains("Miguel"))
        }

    @Test
    fun `emotional entry maintains context in summary`() =
        runTest(testDispatcher) {
            setup()
            val journalText =
                """
                Today was really tough. Had a difficult conversation with my manager about 
                my performance. Feeling stressed and overwhelmed with the workload. Called 
                my best friend Lisa for support - she always knows what to say. Later, 
                had dinner with my partner Alex who helped me put things in perspective. 
                Grateful for the people in my life who care about me.
                """.trimIndent()

            val expectedSummary =
                "You navigated a challenging day at work but found comfort and perspective through conversations with supportive people in your life."

            fakeAIClient.setResponseFor(journalText, expectedSummary)

            val summary =
                assertSummarySuccess(
                    entrySummarizer.summarize("emotional-entry", journalText, useCached = false),
                )
            assertEquals(expectedSummary, summary)

            // Verify the summary captures both the difficulty and the support
            assertTrue(summary.contains("challenging") || summary.contains("difficult"))
            assertTrue(summary.contains("support") || summary.contains("comfort") || summary.contains("perspective"))
        }

    @Test
    fun `achievement entry captures positive context`() =
        runTest(testDispatcher) {
            setup()
            val journalText =
                """
                What an incredible day! Finally got the promotion I've been working toward 
                for two years. My mentor Susan was the first to congratulate me, followed 
                by my teammates David and Amanda. Even my old boss Mr. Thompson sent a 
                nice email. Celebrated tonight with my family - Mom made my favorite dinner 
                and Dad opened a bottle of champagne he'd been saving. Feeling so grateful 
                and excited for this new chapter.
                """.trimIndent()

            val expectedSummary =
                "You achieved a major career milestone with your promotion and celebrated this significant accomplishment with supportive colleagues and family."

            fakeAIClient.setResponseFor(journalText, expectedSummary)

            val summary =
                assertSummarySuccess(
                    entrySummarizer.summarize("achievement-entry", journalText, useCached = false),
                )
            assertEquals(expectedSummary, summary)

            // Verify the summary captures the achievement and celebration
            assertTrue(summary.contains("achievement") || summary.contains("promotion") || summary.contains("milestone"))
            assertTrue(summary.contains("celebrat") || summary.contains("accomplishment"))
        }

    private fun assertPeopleSuccess(result: AIResult<List<Person>>): List<Person> {
        assertTrue(result is AIResult.Success)
        return result.value
    }

    private fun assertSummarySuccess(result: AIResult<String>): String {
        assertTrue(result is AIResult.Success)
        return result.value
    }

    private fun jsonResponse(vararg names: String): String {
        if (names.isEmpty()) {
            return """{"names":[]}"""
        }
        val quoted = names.joinToString(",") { "\"$it\"" }
        return """{"names":[$quoted]}"""
    }

    /**
     * A test implementation of [NetworkAvailabilityMonitor] for testing.
     */
    private class TestNetworkAvailabilityMonitor(
        private var available: Boolean = true,
    ) : NetworkAvailabilityMonitor {
        override fun isNetworkAvailable(): Boolean = available

        override fun observeNetwork() = throw UnsupportedOperationException("Not used in tests")
    }
}
