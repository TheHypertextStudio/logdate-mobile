package app.logdate.client.intelligence.events

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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.Serializable

/**
 * Suggests a short, human-friendly name and description for an inferred event cluster.
 *
 * The on-device inference pipeline groups location stops, photo bursts, and notes into
 * candidate events. Each cluster has structural facts (where, when, how many signals) but
 * no name. This extractor turns those facts into the kind of label a person would write
 * in their journal — "Afternoon at Stubb's", "Saturday market run" — by passing the
 * cluster summary through the existing chat client and parsing a structured JSON response.
 *
 * Failures fall back to a heuristic name in [InferEventsUseCase], so this extractor is
 * intentionally narrow: serialize a cluster, ask the model, parse, return.
 */
class EventNamingExtractor(
    generativeAICache: GenerativeAICache,
    generativeAIChatClient: GenerativeAIChatClient,
    networkAvailabilityMonitor: NetworkAvailabilityMonitor,
    dataUsagePolicy: DataUsagePolicy,
    ioDispatcher: CoroutineDispatcher = platformIODispatcher,
) : StructuredAIExtractor<EventCluster, EventName>(
        generativeAICache = generativeAICache,
        generativeAIChatClient = generativeAIChatClient,
        networkAvailabilityMonitor = networkAvailabilityMonitor,
        dataUsagePolicy = dataUsagePolicy,
        ioDispatcher = ioDispatcher,
    ) {
    override val systemPrompt: String = SYSTEM_PROMPT
    override val responseSchema: String = RESPONSE_SCHEMA
    override val responseFormatName: String = "event_naming"
    override val cacheContentType: GenerativeAICacheContentType = GenerativeAICacheContentType.Events
    override val promptVersion: String = "events-v1"
    override val schemaVersion: String = "events-json-v1"
    override val templateId: String = "event-naming-extractor"
    override val tag: String = "EventNamingExtractor"

    override fun serializeInput(input: EventCluster): String = input.toPromptSummary()

    override fun parseResponse(raw: String): EventName? {
        val parser =
            JsonStructuredOutputParser(
                json = json,
                serializer = EventName.serializer(),
                allowEmbeddedJson = true,
            )
        return when (val result = parser.parse(raw)) {
            is StructuredOutputResult.Success -> result.value
            StructuredOutputResult.Empty -> null
            StructuredOutputResult.Invalid -> null
        }
    }

    /**
     * Suggests a name and description for [cluster]. Thin wrapper over [extract] that
     * keeps the public method name expressive at call sites.
     */
    suspend fun suggestName(
        cluster: EventCluster,
        useCached: Boolean = true,
    ): AIResult<EventName> = extract(cluster, useCached)

    companion object {
        private const val SYSTEM_PROMPT = """
You are a naming assistant for a personal journal. You receive a single CLUSTER of signals from one continuous experience the user had: a place name, a time-of-day window, how many photos they took, and a short text snippet from any note they wrote during that window. Your job is to produce a short, human-friendly TITLE and a one-line DESCRIPTION that the user would recognize as that experience in their timeline.

Guidelines:
- Title: 2 to 6 words. No punctuation at the end. Prefer concrete, evocative phrasing the user would write themselves ("Coffee at Stumptown", "Saturday market run", "Evening at the park") over generic labels ("Outing", "Visit").
- Use the place name when it is meaningful and specific. If the place is generic ("Restaurant", "Park") or missing, use a time-of-day phrase instead ("Saturday morning", "Late afternoon").
- Description: one sentence, 8 to 18 words, written in third person. Mention the place if known and gesture at what made the moment distinct (a meal, a walk, photos taken). Do not invent details that aren't in the input.
- Never mention the cluster, the model, or that this is auto-generated. Write as if the user is reading their own journal.
- Never include hashtags, emoji, or quotation marks.
- If the input is too thin to name confidently, still produce the best title you can from the place and time of day.
"""
        private const val RESPONSE_SCHEMA = """
{
  "type": "object",
  "properties": {
    "title": { "type": "string" },
    "description": { "type": "string" }
  },
  "required": ["title", "description"],
  "additionalProperties": false
}
"""

        private val json = LenientJson
    }
}

/**
 * Structured input for [EventNamingExtractor]. The clustering use case fills these fields
 * from location stops, indexed media, and notes; the extractor turns the bundle into a
 * compact prompt via [toPromptSummary].
 *
 * Keep field values short and human-readable — they end up verbatim in the model prompt.
 */
data class EventCluster(
    /** The place the cluster happened at, or null if no place was resolved. */
    val placeName: String?,
    /** Coarse time-of-day phrase ("Saturday morning", "Tuesday evening", "Late afternoon"). */
    val timeOfDay: String,
    /** Number of photos / videos captured during the window. */
    val mediaCount: Int,
    /** Number of text or voice notes captured during the window. */
    val noteCount: Int,
    /**
     * A short snippet from the first note in the window, used to give the model
     * something concrete to anchor on. Truncate before passing to keep prompts tight.
     */
    val firstNoteSnippet: String?,
) {
    fun toPromptSummary(): String =
        buildString {
            append("Place: ").append(placeName ?: "(unknown)").append('\n')
            append("Time of day: ").append(timeOfDay).append('\n')
            append("Photos: ").append(mediaCount).append('\n')
            append("Notes: ").append(noteCount).append('\n')
            if (!firstNoteSnippet.isNullOrBlank()) {
                append("First note: ").append(firstNoteSnippet)
            }
        }
}

/** Structured output from [EventNamingExtractor]. */
@Serializable
data class EventName(
    val title: String,
    val description: String,
)
