package app.logdate.client.intelligence.structured

import app.logdate.client.intelligence.AIError
import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.AIUnavailableReason
import app.logdate.client.intelligence.MAX_AI_INPUT_CHARACTERS
import app.logdate.client.intelligence.cache.AICachePolicy
import app.logdate.client.intelligence.cache.GenerativeAICache
import app.logdate.client.intelligence.cache.GenerativeAICacheContentType
import app.logdate.client.intelligence.cache.GenerativeAICacheRequest
import app.logdate.client.intelligence.entity.moments.MomentExtractor
import app.logdate.client.intelligence.events.EventNamingExtractor
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.intelligence.generativeai.GenerativeAIChatMessage
import app.logdate.client.intelligence.generativeai.GenerativeAIRequest
import app.logdate.client.intelligence.generativeai.GenerativeAIResponseFormat
import app.logdate.client.intelligence.unavailableReason
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.util.platformIODispatcher
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Shared cache → unavailability check → submit → parse → cache write skeleton for the
 * project's structured-output AI extractors.
 *
 * Both [MomentExtractor] and [EventNamingExtractor] used to copy the same
 * sixty-line dance: build a cache request, try the cache, gate on network/data-usage,
 * call the chat client with a JSON-schema response format, parse the JSON, write the
 * cache, return. Only the input serialization, the system prompt, and the parser
 * differed. This base owns the dance; subclasses fill in the differences.
 *
 * Type parameters:
 * - [Input]: caller-supplied input. Subclasses serialize it to a string for both the
 *   cache key and the user message.
 * - [Output]: structured response the parser produces.
 *
 * Subclasses must override [serializeInput] and [parseResponse], plus the metadata
 * properties (system prompt, schema, response format name, content type, version
 * markers, log tag). The [extract] entry point handles everything else.
 */
abstract class StructuredAIExtractor<Input, Output>(
    protected val generativeAICache: GenerativeAICache,
    protected val generativeAIChatClient: GenerativeAIChatClient,
    protected val networkAvailabilityMonitor: NetworkAvailabilityMonitor,
    protected val dataUsagePolicy: DataUsagePolicy,
    protected val ioDispatcher: CoroutineDispatcher = platformIODispatcher,
) {
    protected abstract val systemPrompt: String

    protected abstract val responseSchema: String

    protected abstract val responseFormatName: String

    protected abstract val cacheContentType: GenerativeAICacheContentType

    protected abstract val promptVersion: String

    protected abstract val schemaVersion: String

    protected abstract val templateId: String

    protected abstract val tag: String

    /** Cache TTL in seconds. Defaults to seven days; override for shorter-lived results. */
    protected open val cacheTtlSeconds: Long = DEFAULT_CACHE_TTL_SECONDS

    /**
     * Turn an [Input] into the string that will be both the cache key and the chat
     * client's user message. Should be deterministic — two equivalent inputs must
     * produce the same string or the cache will miss.
     */
    protected abstract fun serializeInput(input: Input): String

    /**
     * Parse a model response (or a cached one) into [Output]. Return `null` for any
     * malformed payload; [extract] handles logging and surfacing the error.
     */
    protected abstract fun parseResponse(raw: String): Output?

    /**
     * Run the full extraction pipeline for [input]. Honors the cache, the network and
     * data-usage gates, and translates every failure into the appropriate [AIResult].
     *
     * @param input The structured input to send.
     * @param useCached Whether a cached response for the same serialized input may be
     *   reused. Set `false` to force a fresh model call.
     * @return [AIResult.Success] with the parsed output (cached or fresh),
     *   [AIResult.Unavailable] when network or data-usage policy disallows the call, or
     *   [AIResult.Error] when the model or parser failed.
     */
    suspend fun extract(
        input: Input,
        useCached: Boolean = true,
    ): AIResult<Output> =
        withContext(ioDispatcher) {
            val serializedInput = serializeInput(input)
            if (serializedInput.length > MAX_AI_INPUT_CHARACTERS) return@withContext AIResult.Unavailable(AIUnavailableReason.InputTooLarge)
            val cacheRequest =
                GenerativeAICacheRequest(
                    contentType = cacheContentType,
                    inputText = serializedInput,
                    providerId = generativeAIChatClient.providerId,
                    model = generativeAIChatClient.defaultModel,
                    promptVersion = promptVersion,
                    schemaVersion = schemaVersion,
                    templateId = templateId,
                    policy = AICachePolicy(ttlSeconds = cacheTtlSeconds),
                )
            if (useCached) {
                val cachedResponse = generativeAICache.getEntry(cacheRequest)
                if (cachedResponse != null) {
                    val parsed = parseResponse(cachedResponse.content)
                    if (parsed != null) {
                        return@withContext AIResult.Success(parsed, fromCache = true)
                    }
                    Napier.w(tag = tag, message = "Cached response was invalid, falling through to a fresh call")
                }
            }
            val unavailableReason = unavailableReason(networkAvailabilityMonitor, dataUsagePolicy)
            if (unavailableReason != null) {
                return@withContext AIResult.Unavailable(unavailableReason)
            }
            val prompts =
                listOf(
                    GenerativeAIChatMessage("system", systemPrompt),
                    GenerativeAIChatMessage("user", serializedInput),
                )
            val response =
                generativeAIChatClient.submit(
                    GenerativeAIRequest(
                        messages = prompts,
                        model = cacheRequest.model,
                        responseFormat =
                            GenerativeAIResponseFormat.JsonSchema(
                                name = responseFormatName,
                                schema = responseSchema,
                            ),
                    ),
                )
            when (response) {
                is AIResult.Success -> {
                    val content = response.value.content
                    val parsed =
                        parseResponse(content)
                            ?: return@withContext AIResult.Error(AIError.InvalidResponse)
                    generativeAICache.putEntry(cacheRequest, content.trim())
                    AIResult.Success(parsed, fromCache = false)
                }

                is AIResult.Unavailable -> {
                    response
                }

                is AIResult.Error -> {
                    Napier.e(
                        tag = tag,
                        message = "Structured extraction failed",
                        throwable = response.throwable,
                    )
                    response
                }
            }
        }

    companion object {
        private const val DEFAULT_CACHE_TTL_SECONDS = 60L * 60L * 24L * 7L // 7 days
    }
}
