package app.logdate.client.intelligence.entity.people

import app.logdate.client.intelligence.AIError
import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.AIUnavailableReason
import app.logdate.client.intelligence.LenientJson
import app.logdate.client.intelligence.MAX_AI_INPUT_CHARACTERS
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
import app.logdate.shared.model.Person
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * A utility to extract people's names from text.
 */
@OptIn(ExperimentalUuidApi::class)
class PeopleExtractor(
    private val generativeAICache: GenerativeAICache,
    private val generativeAIChatClient: GenerativeAIChatClient,
    private val networkAvailabilityMonitor: NetworkAvailabilityMonitor,
    private val dataUsagePolicy: DataUsagePolicy,
    private val ioDispatcher: CoroutineDispatcher = platformIODispatcher,
) {
    companion object {
        // TODO: Use additional logic to extract text fragments that could be resolved to people
        private const val ADVANCED_EXTRACTION_PROMPT = """
You are a system utility that extracts the names of likely humans mentioned in text.
Return a JSON object with a "names" array containing the literal names from the text.
Include references to noun-adjective pairings that could be names.
        """
        private const val EXTRACTION_PROMPT = """
You are a system utility that extracts the names of likely humans mentioned in text.
Return a JSON object with a "names" array containing the literal names from the text.
If no names are present, return an empty array.
        """
        private const val PROMPT_VERSION = "people-v1"
        private const val SCHEMA_VERSION = "people-json-v1"
        private const val TEMPLATE_ID = "people-extractor"
        private const val CACHE_TTL_SECONDS = 60L * 60L * 24L * 30L
        private const val RESPONSE_SCHEMA = """
{
  "type": "object",
  "properties": {
    "names": {
      "type": "array",
      "items": { "type": "string" }
    }
  },
  "required": ["names"],
  "additionalProperties": false
}
"""

        private val json = LenientJson
    }

    /**
     * Extracts people's names from the given text.
     *
     * @param documentId An ID used for log correlation.
     * @param text The text to extract people's names from.
     * @param useCached Whether to use a cached response if available. If false, the response will
     * be generated from scratch.
     *
     * @return A list of people's names extracted from the text.
     */
    suspend fun extractPeople(
        documentId: String,
        text: String,
        useCached: Boolean = true,
    ): AIResult<List<Person>> =
        withContext(ioDispatcher) {
            if (text.length > MAX_AI_INPUT_CHARACTERS) return@withContext AIResult.Unavailable(AIUnavailableReason.InputTooLarge)
            val cacheRequest =
                GenerativeAICacheRequest(
                    contentType = GenerativeAICacheContentType.People,
                    inputText = text,
                    providerId = generativeAIChatClient.providerId,
                    model = generativeAIChatClient.defaultModel,
                    promptVersion = PROMPT_VERSION,
                    schemaVersion = SCHEMA_VERSION,
                    templateId = TEMPLATE_ID,
                    policy = AICachePolicy(ttlSeconds = CACHE_TTL_SECONDS),
                )
            if (useCached) {
                val cachedResponse = generativeAICache.getEntry(cacheRequest)
                if (cachedResponse != null) {
                    val parsed = parsePeopleResponse(cachedResponse.content)
                    if (parsed != null) {
                        return@withContext AIResult.Success(parsed, fromCache = true)
                    }
                    Napier.w(tag = "PeopleExtractor", message = "Cached people response was invalid for $documentId")
                }
            }
            val unavailableReason = unavailableReason(networkAvailabilityMonitor, dataUsagePolicy)
            if (unavailableReason != null) {
                return@withContext AIResult.Unavailable(unavailableReason)
            }
            val prompts =
                listOf(
                    GenerativeAIChatMessage("system", EXTRACTION_PROMPT),
                    GenerativeAIChatMessage("user", text),
                )
            val response =
                generativeAIChatClient.submit(
                    GenerativeAIRequest(
                        messages = prompts,
                        model = cacheRequest.model,
                        responseFormat =
                            GenerativeAIResponseFormat.JsonSchema(
                                name = "people_extraction",
                                schema = RESPONSE_SCHEMA,
                            ),
                    ),
                )
            when (response) {
                is AIResult.Success -> {
                    val content = response.value.content
                    val people =
                        parsePeopleResponse(content)
                            ?: return@withContext AIResult.Error(AIError.InvalidResponse)
                    Napier.d(tag = "PeopleExtractor", message = "Caching response for:\n$text")
                    Napier.d(tag = "PeopleExtractor") { "Response: ${content.trim()}" }
                    generativeAICache.putEntry(cacheRequest, content.trim())
                    AIResult.Success(people, fromCache = false)
                }
                is AIResult.Unavailable -> response
                is AIResult.Error -> {
                    Napier.e(
                        tag = "PeopleExtractor",
                        message = "Failed to extract people",
                        throwable = response.throwable,
                    )
                    response
                }
            }
        }

    private fun parsePeopleResponse(raw: String): List<Person>? {
        val parser =
            JsonStructuredOutputParser(
                json = json,
                serializer = PeopleResponse.serializer(),
                allowEmbeddedJson = true,
            )
        return when (val result = parser.parse(raw)) {
            is StructuredOutputResult.Success ->
                result.value.names
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { Person(name = it) }
            StructuredOutputResult.Empty -> emptyList()
            StructuredOutputResult.Invalid -> null
        }
    }

    @Serializable
    private data class PeopleResponse(
        val names: List<String>,
    )
}
