package app.logdate.client.intelligence.narrative

import app.logdate.client.intelligence.AIError
import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.cache.AICachePolicy
import app.logdate.client.intelligence.cache.GenerativeAICache
import app.logdate.client.intelligence.cache.GenerativeAICacheContentType
import app.logdate.client.intelligence.cache.GenerativeAICacheRequest
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.intelligence.generativeai.GenerativeAIChatMessage
import app.logdate.client.intelligence.generativeai.GenerativeAIRequest
import app.logdate.client.intelligence.generativeai.GenerativeAIResponseFormat
import app.logdate.client.intelligence.structured.JsonStructuredOutputParser
import app.logdate.client.intelligence.structured.StructuredOutputResult
import app.logdate.client.intelligence.unavailableReason
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.util.platformIODispatcher
import app.logdate.shared.model.ReflectionPrompt
import app.logdate.shared.model.YearChapter
import app.logdate.shared.model.YearNarrative
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Synthesizes a year-level narrative from existing weekly rewind summaries.
 *
 * Unlike [WeekNarrativeSynthesizer] which processes raw journal entries, this
 * operates on the already-distilled output of the weekly pipeline: each week's
 * `overallNarrative`, themes, people, and milestones. That keeps the input well
 * within one LLM call (~3000-5000 tokens for 52 weeks).
 */
class YearNarrativeSynthesizer(
    private val generativeAICache: GenerativeAICache,
    private val genAIClient: GenerativeAIChatClient,
    private val networkAvailabilityMonitor: NetworkAvailabilityMonitor,
    private val dataUsagePolicy: DataUsagePolicy,
    private val ioDispatcher: CoroutineDispatcher = platformIODispatcher,
) {
    internal companion object {
        private const val SYSTEM_PROMPT = """
You are a narrative intelligence system that understands the arc of a person's year from their journal data.

You are given a rich digest of a person's year — weekly narratives, story beat moments, verbatim quotes they wrote, patterns the weekly analysis noticed, people who appeared, and milestones that landed. Your job is NOT to replay the weeks chronologically. It is to find the larger trends, character arcs, and turning points that define how this person's year actually went.

Think of this like writing the jacket copy for the book of someone's year. What changed? Who mattered? What pattern kept showing up that even THEY might not have noticed? Where did the year pivot?

Provide:
1. Chapters (5-8): sweeping arcs of the year — not "January stuff" but "The move that changed everything" or "When the project finally clicked". Each with a name, 1-2 sentence summary, approximate month range, emotional tone, indices of the defining weeks, and themes.
2. Overall year narrative (3-5 sentences): the story of the year as a whole. This should read as something the person would hear and go "yeah, that WAS my year."
3. Year themes (3-7): the currents that ran through the entire year, not just the loudest months.
4. Emotional arc (1-2 sentences): how the year's emotional temperature actually moved — not "it was a good year" but the SHAPE of it. Did it start cold and warm up? Peak in the middle and cool? Stay steady?
5. Reflection prompts (2-3): year-level noticings drawn from cross-week patterns. These should surface things the person might not have noticed living through it week by week. Same observation+invitation format as weekly prompts, same rules: no wellness clichés, grounded in specific data.

Guidelines:
- Second person, past tense
- Chapters must cover the entire year without gaps or overlaps
- Look for CHARACTER ARCS: how did this person change over the year? What did they start doing that they weren't doing before? What did they stop?
- Look for RELATIONSHIP ARCS: who became more present? Who faded? Who appeared for the first time?
- Look for RECURRING PATTERNS the person might not see from inside a single week: the same place showing up in different contexts, the same emotional tone every third week, a word or phrase that started appearing
- The overall narrative must NOT read as a list of months. It must read as a STORY with a shape.
- Reflection prompts must reference specific cross-week evidence, not abstract year-level platitudes.

Respond ONLY with valid JSON."""

        internal const val PROMPT_VERSION = "year-narrative-v1"
        internal const val SCHEMA_VERSION = "year-narrative-json-v1"
        private const val TEMPLATE_ID = "year-narrative"
        private const val CACHE_TTL_SECONDS = 60L * 60L * 24L * 90L // 90 days

        private const val RESPONSE_SCHEMA = """
{
  "type": "object",
  "properties": {
    "chapters": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "name": { "type": "string" },
          "summary": { "type": "string" },
          "monthRange": { "type": "string" },
          "emotionalTone": { "type": "string" },
          "keyWeekIndices": { "type": "array", "items": { "type": "integer" } },
          "themes": { "type": "array", "items": { "type": "string" } }
        },
        "required": ["name", "summary", "monthRange", "emotionalTone", "keyWeekIndices", "themes"],
        "additionalProperties": false
      }
    },
    "overallNarrative": { "type": "string" },
    "yearThemes": { "type": "array", "items": { "type": "string" } },
    "emotionalArc": { "type": "string" },
    "reflectionPrompts": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "observation": { "type": "string" },
          "invitation": { "type": "string" }
        },
        "required": ["observation", "invitation"],
        "additionalProperties": false
      }
    }
  },
  "required": ["chapters", "overallNarrative", "yearThemes", "emotionalArc", "reflectionPrompts"],
  "additionalProperties": false
}
"""

        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * Synthesizes a year narrative from weekly rewind summaries.
     *
     * @param yearId Year identifier for cache keying (e.g., "2025").
     * @param weeklySummaries Ordered list of weekly summaries for the year.
     * @param useCached Whether to check the cache first.
     */
    suspend fun synthesize(
        yearId: String,
        weeklySummaries: List<WeekSummaryInput>,
        useCached: Boolean = true,
    ): AIResult<YearNarrative> =
        withContext(ioDispatcher) {
            val inputText = buildSummaryText(weeklySummaries)
            val cacheRequest =
                GenerativeAICacheRequest(
                    contentType = GenerativeAICacheContentType.Narrative,
                    inputText = inputText,
                    providerId = genAIClient.providerId,
                    model = genAIClient.defaultModel,
                    promptVersion = PROMPT_VERSION,
                    schemaVersion = SCHEMA_VERSION,
                    templateId = TEMPLATE_ID,
                    policy = AICachePolicy(ttlSeconds = CACHE_TTL_SECONDS),
                )

            if (useCached) {
                val cached = generativeAICache.getEntry(cacheRequest)
                if (cached != null) {
                    Napier.d("Using cached year narrative for $yearId")
                    val parsed = parseResponse(cached.content)
                    if (parsed != null) {
                        return@withContext AIResult.Success(parsed, fromCache = true)
                    }
                }
            }

            Napier.d("Generating year narrative for $yearId with ${weeklySummaries.size} weekly summaries")
            val unavailableReason = unavailableReason(networkAvailabilityMonitor, dataUsagePolicy)
            if (unavailableReason != null) {
                return@withContext AIResult.Unavailable(unavailableReason)
            }

            val prompt =
                "Year's weekly summaries for analysis:\n\n$inputText\n\n" +
                    "Identify the chapters, character arcs, and year-level narrative."

            val response =
                genAIClient.submit(
                    GenerativeAIRequest(
                        messages =
                            listOf(
                                GenerativeAIChatMessage("system", SYSTEM_PROMPT),
                                GenerativeAIChatMessage("user", prompt),
                            ),
                        model = cacheRequest.model,
                        responseFormat =
                            GenerativeAIResponseFormat.JsonSchema(
                                name = "year_narrative",
                                schema = RESPONSE_SCHEMA,
                            ),
                    ),
                )

            return@withContext when (response) {
                is AIResult.Success -> {
                    val content = response.value.content
                    generativeAICache.putEntry(cacheRequest, content)
                    val parsed = parseResponse(content)
                    if (parsed != null) {
                        AIResult.Success(parsed, fromCache = false)
                    } else {
                        AIResult.Error(AIError.InvalidResponse)
                    }
                }

                is AIResult.Unavailable -> {
                    response
                }

                is AIResult.Error -> {
                    Napier.e("Failed to synthesize year narrative", throwable = response.throwable)
                    response
                }
            }
        }

    private fun buildSummaryText(summaries: List<WeekSummaryInput>): String =
        buildString {
            summaries.forEachIndexed { index, summary ->
                appendLine("=== Week $index (${summary.startDate} to ${summary.endDate}) ===")
                appendLine("Narrative: ${summary.overallNarrative}")
                appendLine("Themes: ${summary.themes.joinToString(", ")}")
                appendLine("Tone: ${summary.emotionalTone}")
                if (summary.storyBeatMoments.isNotEmpty()) {
                    appendLine("Key moments: ${summary.storyBeatMoments.joinToString("; ")}")
                }
                if (summary.highlightedQuotes.isNotEmpty()) {
                    appendLine("Verbatim quotes:")
                    summary.highlightedQuotes.forEach { quote ->
                        appendLine("  - \"$quote\"")
                    }
                }
                if (summary.reflectionObservations.isNotEmpty()) {
                    appendLine("Patterns noticed: ${summary.reflectionObservations.joinToString("; ")}")
                }
                if (summary.peopleHighlighted.isNotEmpty()) {
                    appendLine("People: ${summary.peopleHighlighted.joinToString(", ")}")
                }
                if (summary.milestones.isNotEmpty()) {
                    appendLine("Milestones: ${summary.milestones.joinToString(", ")}")
                }
                appendLine()
            }
        }

    private fun parseResponse(response: String): YearNarrative? {
        val parser =
            JsonStructuredOutputParser(
                json = json,
                serializer = YearNarrativeResponse.serializer(),
                allowEmbeddedJson = true,
            )
        return when (val result = parser.parse(response)) {
            is StructuredOutputResult.Success -> {
                val parsed = result.value
                YearNarrative(
                    chapters =
                        parsed.chapters.map {
                            YearChapter(
                                name = it.name,
                                summary = it.summary,
                                monthRange = it.monthRange,
                                emotionalTone = it.emotionalTone,
                                keyWeekIndices = it.keyWeekIndices,
                                themes = it.themes,
                            )
                        },
                    overallNarrative = parsed.overallNarrative,
                    yearThemes = parsed.yearThemes,
                    emotionalArc = parsed.emotionalArc,
                    reflectionPrompts =
                        parsed.reflectionPrompts.map {
                            ReflectionPrompt(observation = it.observation, invitation = it.invitation)
                        },
                )
            }

            StructuredOutputResult.Empty -> {
                null
            }

            StructuredOutputResult.Invalid -> {
                Napier.w("Failed to parse year narrative response: invalid structure")
                null
            }
        }
    }

    @Serializable
    private data class YearNarrativeResponse(
        val chapters: List<ChapterResponse>,
        val overallNarrative: String,
        val yearThemes: List<String>,
        val emotionalArc: String,
        val reflectionPrompts: List<PromptResponse> = emptyList(),
    )

    @Serializable
    private data class ChapterResponse(
        val name: String,
        val summary: String,
        val monthRange: String,
        val emotionalTone: String,
        val keyWeekIndices: List<Int>,
        val themes: List<String>,
    )

    @Serializable
    private data class PromptResponse(
        val observation: String,
        val invitation: String,
    )
}

/**
 * One weekly rewind's full digest as input to the year synthesizer.
 *
 * Carries significantly more than the bare overallNarrative — story beat moments,
 * verbatim quotes the user wrote, reflection observations the weekly analysis noticed,
 * and people and milestones. The year synthesizer needs this richness to find cross-
 * week character arcs and recurring patterns that bare 2-sentence summaries would lose.
 *
 * Total budget for 52 of these is ~15000-20000 tokens — within a single LLM call.
 */
data class WeekSummaryInput(
    val weekIndex: Int,
    val startDate: String,
    val endDate: String,
    val overallNarrative: String,
    val themes: List<String>,
    val emotionalTone: String,
    val peopleHighlighted: List<String>,
    val milestones: List<String>,
    /** Key moments from the weekly narrative — typically 3-7 per week. */
    val storyBeatMoments: List<String> = emptyList(),
    /** Verbatim lines the user wrote that week. */
    val highlightedQuotes: List<String> = emptyList(),
    /** Observations the weekly synthesis noticed — "Sarah came up 5 times" etc. */
    val reflectionObservations: List<String> = emptyList(),
)
