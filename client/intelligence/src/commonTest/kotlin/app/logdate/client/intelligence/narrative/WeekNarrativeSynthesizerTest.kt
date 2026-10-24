package app.logdate.client.intelligence.narrative

import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.cache.AICachePolicy
import app.logdate.client.intelligence.cache.GenerativeAICacheContentType
import app.logdate.client.intelligence.cache.GenerativeAICacheRequest
import app.logdate.client.intelligence.fakes.FakeDataUsagePolicy
import app.logdate.client.intelligence.fakes.FakeGenerativeAICache
import app.logdate.client.intelligence.fakes.FakeGenerativeAIChatClient
import app.logdate.client.intelligence.rewind.strategy.AudioEntryWithTranscript
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.shared.model.Person
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests the [WeekNarrativeSynthesizer]'s capacity to aggregate diverse weekly data
 * into a cohesive narrative.
 *
 * These tests simulate various life scenarios (such as vacations or stressful work
 * weeks) to verify that the synthesizer correctly identifies themes, emotional
 * tones, and key story beats from a mix of text entries, media, and social mentions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeekNarrativeSynthesizerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val fakeCache = FakeGenerativeAICache()
    private val fakeAIClient = FakeGenerativeAIChatClient()
    private val fakeNetworkMonitor = TestNetworkAvailabilityMonitor()

    private val fakeDataUsagePolicy = FakeDataUsagePolicy()

    private val synthesizer =
        WeekNarrativeSynthesizer(
            generativeAICache = fakeCache,
            genAIClient = fakeAIClient,
            networkAvailabilityMonitor = fakeNetworkMonitor,
            dataUsagePolicy = fakeDataUsagePolicy,
            ioDispatcher = testDispatcher,
        )

    private fun setup() {
        fakeCache.clear()
        fakeAIClient.clear()
    }

    private fun cacheRequestFor(
        textEntries: List<JournalNote.Text>,
        media: List<IndexedMedia>,
        people: List<Person>,
        audio: List<AudioEntryWithTranscript> = emptyList(),
    ): GenerativeAICacheRequest {
        val summary =
            buildString {
                appendLine("=== TEXT ENTRIES ===")
                textEntries.forEach { entry ->
                    appendLine("\n[${entry.creationTimestamp}] (ID: ${entry.uid})")
                    appendLine(entry.content.take(500))
                    if (entry.content.length > 500) appendLine("... [truncated]")
                }

                appendLine("\n=== MEDIA ===")
                media.forEach { item ->
                    when (item) {
                        is IndexedMedia.Image -> {
                            appendLine("\n[${item.timestamp}] Photo (ID: ${item.uid})")
                            item.caption?.let { appendLine("Caption: $it") }
                        }

                        is IndexedMedia.Video -> {
                            appendLine("\n[${item.timestamp}] Video (ID: ${item.uid}) - Duration: ${item.duration}")
                            item.caption?.let { appendLine("Caption: $it") }
                        }
                    }
                }

                if (audio.isNotEmpty()) {
                    appendLine("\n=== AUDIO TRANSCRIPTS ===")
                    audio.forEach { entry ->
                        val durationSeconds = entry.audio.durationMs / 1000
                        appendLine(
                            "\n[${entry.audio.creationTimestamp}] Audio (ID: ${entry.audio.uid}) - " +
                                "Duration: ${durationSeconds}s",
                        )
                        val transcript = entry.transcriptionText
                        if (transcript != null) {
                            appendLine(transcript.take(500))
                            if (transcript.length > 500) appendLine("... [truncated]")
                        } else {
                            appendLine("[transcript not yet available]")
                        }
                    }
                }

                if (people.isNotEmpty()) {
                    appendLine("\n=== PEOPLE MENTIONED ===")
                    appendLine(people.joinToString(", ") { it.name })
                }

                appendLine("\n=== SUMMARY STATS ===")
                appendLine("Total entries: ${textEntries.size}")
                appendLine("Total media: ${media.size}")
                appendLine("Total audio: ${audio.size}")
                appendLine("People mentioned: ${people.size}")
            }

        return GenerativeAICacheRequest(
            contentType = GenerativeAICacheContentType.Narrative,
            inputText = summary,
            providerId = fakeAIClient.providerId,
            model = fakeAIClient.defaultModel,
            promptVersion = WeekNarrativeSynthesizer.PROMPT_VERSION,
            schemaVersion = WeekNarrativeSynthesizer.SCHEMA_VERSION,
            templateId = WeekNarrativeSynthesizer.TEMPLATE_ID,
            policy = AICachePolicy(ttlSeconds = WeekNarrativeSynthesizer.CACHE_TTL_SECONDS),
        )
    }

    @Test
    fun `synthesize with vacation week generates correct narrative`() =
        runTest(testDispatcher) {
            setup()
            val weekId = "2024-W30"

            val textEntries =
                listOf(
                    JournalNote.Text(
                        uid = Uuid.random(),
                        creationTimestamp = Instant.parse("2024-07-22T10:00:00Z"),
                        lastUpdated = Instant.parse("2024-07-22T10:00:00Z"),
                        content = "Finally found the hidden beach at Point Reyes! It was everything I hoped for.",
                    ),
                    JournalNote.Text(
                        uid = Uuid.random(),
                        creationTimestamp = Instant.parse("2024-07-23T19:00:00Z"),
                        lastUpdated = Instant.parse("2024-07-23T19:00:00Z"),
                        content = "Taco truck tour night 1. This might become a tradition.",
                    ),
                )

            val media =
                listOf(
                    IndexedMedia.Image(
                        uid = Uuid.random(),
                        uri = "file:///beach-sunset.jpg",
                        timestamp = Instant.parse("2024-07-22T18:30:00Z"),
                        caption = "Sunset at the hidden beach",
                    ),
                )

            val vacationNarrativeResponse =
                """
                {
                  "themes": ["vacation", "exploration", "culinary adventure"],
                  "emotionalTone": "excited and refreshed",
                  "storyBeats": [
                    {
                      "moment": "Finally found the hidden beach at Point Reyes",
                      "context": "Long-anticipated goal you'd been searching for",
                      "emotionalWeight": "triumphant",
                      "evidenceIds": ["${textEntries[0].uid}"]
                    },
                    {
                      "moment": "Taco truck tour became the evening ritual",
                      "context": "Spontaneous tradition that defined the trip",
                      "emotionalWeight": "joyful",
                      "evidenceIds": ["${textEntries[1].uid}"]
                    }
                  ],
                  "overallNarrative": "You explored the California coast, finally finding that hidden beach you'd been searching for. Evenings were dedicated to the taco truck tour you'd been planning."
                }
                """.trimIndent()

            fakeAIClient.defaultResponse = vacationNarrativeResponse

            val result =
                synthesizer.synthesize(
                    weekId = weekId,
                    textEntries = textEntries,
                    media = media,
                    people = emptyList(),
                    useCached = false,
                )

            val narrative = assertSuccess(result)
            assertEquals(3, narrative.themes.size)
            assertTrue(narrative.themes.contains("vacation"))
            assertTrue(narrative.themes.contains("exploration"))
            assertTrue(narrative.themes.contains("culinary adventure"))
            assertEquals("excited and refreshed", narrative.emotionalTone)
            assertEquals(2, narrative.storyBeats.size)
            assertEquals("Finally found the hidden beach at Point Reyes", narrative.storyBeats[0].moment)
            assertEquals("triumphant", narrative.storyBeats[0].emotionalWeight)
        }

    @Test
    fun `synthesize with tough week generates correct narrative`() =
        runTest(testDispatcher) {
            setup()
            val weekId = "2024-W31"

            val textEntries =
                listOf(
                    JournalNote.Text(
                        uid = Uuid.random(),
                        creationTimestamp = Instant.parse("2024-07-29T22:00:00Z"),
                        lastUpdated = Instant.parse("2024-07-29T22:00:00Z"),
                        content = "Sarah being out of town is harder than I expected. Missing her a lot.",
                    ),
                    JournalNote.Text(
                        uid = Uuid.random(),
                        creationTimestamp = Instant.parse("2024-07-30T23:30:00Z"),
                        lastUpdated = Instant.parse("2024-07-30T23:30:00Z"),
                        content = "Another late night at the office. This deadline is killing me.",
                    ),
                    JournalNote.Text(
                        uid = Uuid.random(),
                        creationTimestamp = Instant.parse("2024-07-31T01:00:00Z"),
                        lastUpdated = Instant.parse("2024-07-31T01:00:00Z"),
                        content = "WE DID IT! Raid team finally cleared Mythic! Gaming friends are the best.",
                    ),
                )

            val people = listOf(Person(name = "Sarah"))

            val toughWeekResponse =
                """
                {
                  "themes": ["relationship strain", "work pressure", "gaming as therapy"],
                  "emotionalTone": "stressful but resilient",
                  "storyBeats": [
                    {
                      "moment": "Sarah being out of town hit harder than expected",
                      "context": "Unexpected loneliness highlighting relationship importance",
                      "emotionalWeight": "melancholy",
                      "evidenceIds": ["${textEntries[0].uid}"]
                    },
                    {
                      "moment": "Project deadline had you working late every night",
                      "context": "Work stress compounding personal challenges",
                      "emotionalWeight": "exhausted",
                      "evidenceIds": ["${textEntries[1].uid}"]
                    },
                    {
                      "moment": "Raid team finally cleared Mythic",
                      "context": "Gaming friends providing support and victory during tough time",
                      "emotionalWeight": "grateful",
                      "evidenceIds": ["${textEntries[2].uid}"]
                    }
                  ],
                  "overallNarrative": "This week was rough - Sarah being out of town hit harder than expected, and that project deadline had you working late every night. At least the raid team finally cleared Mythic - sometimes gaming friends are the best therapy."
                }
                """.trimIndent()

            fakeAIClient.defaultResponse = toughWeekResponse

            val result =
                synthesizer.synthesize(
                    weekId = weekId,
                    textEntries = textEntries,
                    media = emptyList(),
                    people = people,
                    useCached = false,
                )

            val narrative = assertSuccess(result)
            assertEquals(3, narrative.themes.size)
            assertTrue(narrative.themes.contains("relationship strain"))
            assertEquals("stressful but resilient", narrative.emotionalTone)
            assertEquals(3, narrative.storyBeats.size)
            assertEquals("melancholy", narrative.storyBeats[0].emotionalWeight)
            assertEquals("grateful", narrative.storyBeats[2].emotionalWeight)
        }

    @Test
    fun `synthesize with cached response uses cached narrative`() =
        runTest(testDispatcher) {
            setup()
            val weekId = "2024-W32"

            val cachedResponse =
                """
                {
                  "themes": ["relaxation"],
                  "emotionalTone": "peaceful",
                  "storyBeats": [
                    {
                      "moment": "A quiet week",
                      "context": "Taking it easy",
                      "emotionalWeight": "calm",
                      "evidenceIds": []
                    }
                  ],
                  "overallNarrative": "A peaceful week of rest."
                }
                """.trimIndent()

            val cacheRequest = cacheRequestFor(emptyList(), emptyList(), emptyList())
            fakeCache.setEntry(cacheRequest, cachedResponse)

            val result =
                synthesizer.synthesize(
                    weekId = weekId,
                    textEntries = emptyList(),
                    media = emptyList(),
                    people = emptyList(),
                    useCached = true,
                )

            val narrative = assertSuccess(result)
            assertEquals(listOf("relaxation"), narrative.themes)
            assertEquals("peaceful", narrative.emotionalTone)

            // Verify AI client was not called (cached response used)
            assertEquals(0, fakeAIClient.submissions.size)
        }

    @Test
    fun `synthesize with no content handles gracefully`() =
        runTest(testDispatcher) {
            setup()
            val weekId = "2024-W33"

            val emptyWeekResponse =
                """
                {
                  "themes": ["quiet"],
                  "emotionalTone": "unremarkable",
                  "storyBeats": [],
                  "overallNarrative": "Not much happened this week."
                }
                """.trimIndent()

            fakeAIClient.defaultResponse = emptyWeekResponse

            val result =
                synthesizer.synthesize(
                    weekId = weekId,
                    textEntries = emptyList(),
                    media = emptyList(),
                    people = emptyList(),
                    useCached = false,
                )

            val narrative = assertSuccess(result)
            assertEquals(listOf("quiet"), narrative.themes)
            assertTrue(narrative.storyBeats.isEmpty())
        }

    @Test
    fun `synthesize when offline returns unavailable`() =
        runTest(testDispatcher) {
            setup()
            fakeNetworkMonitor.setAvailable(false)
            val weekId = "2024-W33-offline"

            val result =
                synthesizer.synthesize(
                    weekId = weekId,
                    textEntries = emptyList(),
                    media = emptyList(),
                    people = emptyList(),
                    useCached = false,
                )

            assertTrue(result is AIResult.Unavailable)
            assertEquals(0, fakeAIClient.submissions.size)
        }

    @Test
    fun `synthesize with null ai response returns null`() =
        runTest(testDispatcher) {
            setup()
            val weekId = "2024-W34"

            fakeAIClient.defaultResponse = null

            val result =
                synthesizer.synthesize(
                    weekId = weekId,
                    textEntries =
                        listOf(
                            JournalNote.Text(
                                uid = Uuid.random(),
                                creationTimestamp = Instant.parse("2024-08-19T10:00:00Z"),
                                lastUpdated = Instant.parse("2024-08-19T10:00:00Z"),
                                content = "Test entry",
                            ),
                        ),
                    media = emptyList(),
                    people = emptyList(),
                    useCached = false,
                )

            assertTrue(result is AIResult.Error)
        }

    @Test
    fun `synthesize with invalid json returns null`() =
        runTest(testDispatcher) {
            setup()
            val weekId = "2024-W35"

            fakeAIClient.defaultResponse = "This is not valid JSON"

            val result =
                synthesizer.synthesize(
                    weekId = weekId,
                    textEntries =
                        listOf(
                            JournalNote.Text(
                                uid = Uuid.random(),
                                creationTimestamp = Instant.parse("2024-08-26T10:00:00Z"),
                                lastUpdated = Instant.parse("2024-08-26T10:00:00Z"),
                                content = "Test entry",
                            ),
                        ),
                    media = emptyList(),
                    people = emptyList(),
                    useCached = false,
                )

            assertTrue(result is AIResult.Error)
        }

    @Test
    fun `synthesize includes people in content summary`() =
        runTest(testDispatcher) {
            setup()
            val weekId = "2024-W36"

            val people =
                listOf(
                    Person(name = "Alice"),
                    Person(name = "Bob"),
                    Person(name = "Charlie"),
                )

            val narrativeResponse =
                """
                {
                  "themes": ["social"],
                  "emotionalTone": "connected",
                  "storyBeats": [],
                  "overallNarrative": "A social week with friends."
                }
                """.trimIndent()

            fakeAIClient.defaultResponse = narrativeResponse

            synthesizer.synthesize(
                weekId = weekId,
                textEntries = emptyList(),
                media = emptyList(),
                people = people,
                useCached = false,
            )

            val lastSubmission = fakeAIClient.getLastSubmission()
            assertNotNull(lastSubmission)

            val userMessage = lastSubmission.find { it.role == "user" }
            assertNotNull(userMessage)
            assertTrue(userMessage.content.contains("Alice"))
            assertTrue(userMessage.content.contains("Bob"))
            assertTrue(userMessage.content.contains("Charlie"))
            assertTrue(userMessage.content.contains("PEOPLE MENTIONED"))
        }

    @Test
    fun `synthesize includes media in content summary`() =
        runTest(testDispatcher) {
            setup()
            val weekId = "2024-W37"

            val media =
                listOf(
                    IndexedMedia.Image(
                        uid = Uuid.random(),
                        uri = "file:///photo1.jpg",
                        timestamp = Instant.parse("2024-09-09T14:00:00Z"),
                        caption = "Beautiful sunset",
                    ),
                    IndexedMedia.Video(
                        uid = Uuid.random(),
                        uri = "file:///video1.mp4",
                        timestamp = Instant.parse("2024-09-10T16:00:00Z"),
                        caption = "Cooking adventure",
                        duration = 120.seconds,
                    ),
                )

            val narrativeResponse =
                """
                {
                  "themes": ["photography", "cooking"],
                  "emotionalTone": "creative",
                  "storyBeats": [],
                  "overallNarrative": "A creative week capturing moments."
                }
                """.trimIndent()

            fakeAIClient.defaultResponse = narrativeResponse

            synthesizer.synthesize(
                weekId = weekId,
                textEntries = emptyList(),
                media = media,
                useCached = false,
            )

            val lastSubmission = fakeAIClient.getLastSubmission()
            assertNotNull(lastSubmission)

            val userMessage = lastSubmission.find { it.role == "user" }
            assertNotNull(userMessage)
            assertTrue(userMessage.content.contains("Photo"))
            assertTrue(userMessage.content.contains("Beautiful sunset"))
            assertTrue(userMessage.content.contains("Video"))
            assertTrue(userMessage.content.contains("Cooking adventure"))
            assertTrue(userMessage.content.contains("MEDIA"))
        }

    @Test
    fun `synthesize includes audio transcripts in content summary`() =
        runTest(testDispatcher) {
            setup()
            val weekId = "2024-W38"

            val transcribed =
                AudioEntryWithTranscript(
                    audio =
                        JournalNote.Audio(
                            mediaRef = "file:///recording1.m4a",
                            durationMs = 42_000,
                            creationTimestamp = Instant.parse("2024-09-16T09:00:00Z"),
                            lastUpdated = Instant.parse("2024-09-16T09:00:00Z"),
                        ),
                    transcriptionText = "I finally finished the project I've been putting off.",
                )
            val pending =
                AudioEntryWithTranscript(
                    audio =
                        JournalNote.Audio(
                            mediaRef = "file:///recording2.m4a",
                            durationMs = 15_000,
                            creationTimestamp = Instant.parse("2024-09-17T09:00:00Z"),
                            lastUpdated = Instant.parse("2024-09-17T09:00:00Z"),
                        ),
                    transcriptionText = null,
                )

            val narrativeResponse =
                """
                {
                  "themes": ["reflection"],
                  "emotionalTone": "accomplished",
                  "storyBeats": [],
                  "overallNarrative": "A week of spoken reflection."
                }
                """.trimIndent()

            fakeAIClient.defaultResponse = narrativeResponse

            synthesizer.synthesize(
                weekId = weekId,
                textEntries = emptyList(),
                media = emptyList(),
                audio = listOf(transcribed, pending),
                useCached = false,
            )

            val lastSubmission = fakeAIClient.getLastSubmission()
            assertNotNull(lastSubmission)

            val userMessage = lastSubmission.find { it.role == "user" }
            assertNotNull(userMessage)
            assertTrue(userMessage.content.contains("AUDIO TRANSCRIPTS"))
            assertTrue(userMessage.content.contains("I finally finished the project I've been putting off."))
            // A still-pending transcript is listed too — not silently dropped — just
            // without content to quote.
            assertTrue(userMessage.content.contains("[transcript not yet available]"))
        }

    private fun assertSuccess(result: AIResult<app.logdate.shared.model.WeekNarrative>): app.logdate.shared.model.WeekNarrative {
        assertTrue(result is AIResult.Success)
        return result.value
    }

    /**
     * A test implementation of [NetworkAvailabilityMonitor] for testing.
     */
    private class TestNetworkAvailabilityMonitor(
        private var available: Boolean = true,
    ) : NetworkAvailabilityMonitor {
        override fun isNetworkAvailable(): Boolean = available

        override fun observeNetwork() = throw UnsupportedOperationException("Not used in tests")

        fun setAvailable(isAvailable: Boolean) {
            available = isAvailable
        }
    }
}
