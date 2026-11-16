package app.logdate.client.intelligence.entity.moments

import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.LenientJson
import app.logdate.client.intelligence.cache.GenerativeAICache
import app.logdate.client.intelligence.cache.GenerativeAICacheContentType
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.intelligence.structured.JsonStructuredOutputParser
import app.logdate.client.intelligence.structured.StructuredAIExtractor
import app.logdate.client.intelligence.structured.StructuredOutputResult
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.util.platformIODispatcher
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Extracts semantically coherent moments from a day's journal entries.
 *
 * A moment is a distinct experience — "coffee with Ava", "afternoon at the park".
 * This extractor understands that notes don't correspond 1:1 to memories: a single
 * text note written at 10pm might describe events from 8am, 12pm, and 6pm.
 */
class MomentExtractor(
    generativeAICache: GenerativeAICache,
    generativeAIChatClient: GenerativeAIChatClient,
    networkAvailabilityMonitor: NetworkAvailabilityMonitor,
    dataUsagePolicy: DataUsagePolicy,
    ioDispatcher: CoroutineDispatcher = platformIODispatcher,
) : StructuredAIExtractor<String, List<ExtractedMoment>>(
        generativeAICache = generativeAICache,
        generativeAIChatClient = generativeAIChatClient,
        networkAvailabilityMonitor = networkAvailabilityMonitor,
        dataUsagePolicy = dataUsagePolicy,
        ioDispatcher = ioDispatcher,
    ) {
    override val systemPrompt: String = SYSTEM_PROMPT
    override val responseSchema: String = RESPONSE_SCHEMA
    override val responseFormatName: String = "moment_extraction"
    override val cacheContentType: GenerativeAICacheContentType = GenerativeAICacheContentType.Moments
    override val promptVersion: String = "moments-v1"
    override val schemaVersion: String = "moments-json-v1"
    override val templateId: String = "moment-extractor"
    override val tag: String = TAG

    override fun serializeInput(input: String): String = input

    override fun parseResponse(raw: String): List<ExtractedMoment>? {
        val parser =
            JsonStructuredOutputParser(
                json = json,
                serializer = MomentsResponse.serializer(),
                allowEmbeddedJson = true,
            )
        return when (val result = parser.parse(raw)) {
            is StructuredOutputResult.Success -> result.value.moments
            StructuredOutputResult.Empty -> emptyList()
            StructuredOutputResult.Invalid -> null
        }
    }

    /**
     * Extracts moments from the serialized entries for a day.
     *
     * @param documentId An ID used for log correlation. Not part of the cache key — the
     *   cache hashes [entriesText] directly so two days with identical content share a
     *   cached response.
     * @param entriesText The serialized representation of all entries for the day.
     * @param useCached Whether to use a cached response if available.
     * @return A list of extracted moment data, or an AI error/unavailable result.
     */
    suspend fun extractMoments(
        documentId: String,
        entriesText: String,
        useCached: Boolean = true,
    ): AIResult<List<ExtractedMoment>> {
        val result = extract(entriesText, useCached)
        if (result is AIResult.Success) {
            Napier.d(tag = TAG, message = "Extracted ${result.value.size} moments for $documentId")
        }
        return result
    }

    companion object {
        private const val TAG = "MomentExtractor"
        private const val SYSTEM_PROMPT = """
You are a moment extraction system for a personal journal. You receive a day's journal entries (text, photos, audio, with timestamps and optional location data) and must identify the distinct MOMENTS that occurred during the day.

CRITICAL: A note's creation timestamp is NOT necessarily when the described event occurred. A text note written at 10pm might describe events from 8am, 12pm, and 6pm. You must parse the CONTENT to determine when events actually happened.

For each moment, provide:
1. A contextual label (e.g., "At Blue Bottle Coffee", "Morning run", "That evening")
   - Prefer place-based labels when location data is available
   - Fall back to time-of-day labels when no place context exists
   - Use activity-based labels when the content describes a specific activity
2. Estimated time range (when the moment actually occurred, not when it was written down)
3. Which notes (by ID) provide evidence for this moment
4. Any text fragments from notes that belong to this moment
5. People mentioned in this moment

Guidelines:
- A single note CAN be split across multiple moments if it describes different experiences
- Multiple notes at the same place/time should be merged into one moment
- Photos taken at the same location within a short window are one moment
- An audio note recorded while doing something IS the moment
- Order moments chronologically by when they occurred, not by note creation time
- Keep labels concise but descriptive
- If a note has no clear temporal clue, use its creation timestamp as the estimated time
"""
        private const val RESPONSE_SCHEMA = """
{
  "type": "object",
  "properties": {
    "moments": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "label": { "type": "string" },
          "estimatedStartHour": { "type": "number" },
          "estimatedStartMinute": { "type": "number" },
          "estimatedEndHour": { "type": "number" },
          "estimatedEndMinute": { "type": "number" },
          "sourceNoteIds": {
            "type": "array",
            "items": { "type": "string" }
          },
          "textFragments": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "text": { "type": "string" },
                "sourceNoteId": { "type": "string" }
              },
              "required": ["text", "sourceNoteId"]
            }
          },
          "peopleMentioned": {
            "type": "array",
            "items": { "type": "string" }
          }
        },
        "required": ["label", "estimatedStartHour", "estimatedStartMinute", "estimatedEndHour", "estimatedEndMinute", "sourceNoteIds", "textFragments", "peopleMentioned"]
      }
    }
  },
  "required": ["moments"],
  "additionalProperties": false
}
"""

        private val json = LenientJson
    }
}

@Serializable
private data class MomentsResponse(
    val moments: List<ExtractedMoment>,
)

/**
 * Raw moment data as returned by the AI extractor.
 *
 * Time fields are hours and minutes within the day (0-23, 0-59).
 * These are combined with the day's date to produce full [Instant] values
 * in [InferMomentsUseCase].
 */
@Serializable
data class ExtractedMoment(
    val label: String,
    val estimatedStartHour: Int,
    val estimatedStartMinute: Int = 0,
    val estimatedEndHour: Int,
    val estimatedEndMinute: Int = 0,
    val sourceNoteIds: List<String>,
    val textFragments: List<ExtractedTextFragment> = emptyList(),
    @SerialName("peopleMentioned")
    val people: List<String> = emptyList(),
)

@Serializable
data class ExtractedTextFragment(
    val text: String,
    val sourceNoteId: String,
)
