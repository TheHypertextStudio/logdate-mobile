package app.logdate.client.intelligence.generativeai.openai

import app.logdate.client.intelligence.AIError
import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.intelligence.generativeai.GenerativeAIChatMessage
import app.logdate.client.intelligence.generativeai.GenerativeAIRequest
import app.logdate.client.intelligence.generativeai.GenerativeAIResponse
import app.logdate.client.intelligence.generativeai.GenerativeAIResponseFormat
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A generative AI client that uses the OpenAI API to generate responses.
 */
class OpenAiClient(
    private val apiKey: String,
    private val httpClient: HttpClient,
) : GenerativeAIChatClient {
    private companion object {
        const val PROVIDER_ID = "openai"
        private const val DEFAULT_MODEL = "gpt-4.1-mini-2025-04-14"
        private const val DEFAULT_TEMPERATURE = 0.7
    }

    override val providerId: String = PROVIDER_ID

    override val defaultModel: String = DEFAULT_MODEL

    override suspend fun submit(request: GenerativeAIRequest): AIResult<GenerativeAIResponse> {
        return try {
            val responseFormat = mapResponseFormat(request.responseFormat)
            if (request.responseFormat is GenerativeAIResponseFormat.JsonSchema && responseFormat == null) {
                return AIResult.Error(AIError.InvalidResponse)
            }
            val response: HttpResponse =
                httpClient.post("https://api.openai.com/v1/chat/completions") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $apiKey")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        OpenAiRequest(
                            // Using gpt-4.1-mini-2025-04-14 for enhanced processing capabilities
                            // while maintaining cost efficiency for journal text analysis
                            model = request.model ?: DEFAULT_MODEL,
                            messages = request.messages.map(GenerativeAIChatMessage::toOpenAiChatMessage),
                            temperature = request.temperature ?: DEFAULT_TEMPERATURE,
                            responseFormat = responseFormat,
                        ),
                    )
                }
            if (response.status.value !in 200..299) {
                return AIResult.Error(mapError(response))
            }
            val openAiResponse: OpenAiResponse = response.body()
            val content =
                openAiResponse.choices
                    .firstOrNull()
                    ?.message
                    ?.content
                    ?.trim()
            if (content.isNullOrBlank()) {
                return AIResult.Error(AIError.InvalidResponse)
            }
            AIResult.Success(
                GenerativeAIResponse(
                    content = content,
                    model = openAiResponse.model,
                ),
            )
        } catch (e: Exception) {
            AIResult.Error(AIError.Unknown, e)
        }
    }

    private fun mapResponseFormat(format: GenerativeAIResponseFormat?): OpenAiResponseFormat? {
        return when (format) {
            null, GenerativeAIResponseFormat.Text -> {
                null
            }

            is GenerativeAIResponseFormat.JsonSchema -> {
                val schema =
                    runCatching { Json.parseToJsonElement(format.schema) }.getOrNull()
                        ?: return null
                OpenAiResponseFormat(
                    type = "json_schema",
                    jsonSchema =
                        OpenAiJsonSchema(
                            name = format.name,
                            schema = schema,
                            strict = format.strict,
                        ),
                )
            }
        }
    }

    private fun mapError(response: HttpResponse): AIError =
        when (response.status.value) {
            401, 403 -> AIError.Unauthorized
            429 -> AIError.RateLimited
            in 500..599 -> AIError.ProviderUnavailable
            else -> AIError.Unknown
        }
}

@Serializable
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    val temperature: Double = 0.7,
    @SerialName("response_format")
    val responseFormat: OpenAiResponseFormat? = null,
)

@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OpenAiResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
)

@Serializable
data class Choice(
    val message: OpenAiChatMessage,
    val index: Int,
    val finish_reason: String,
)

@Serializable
data class OpenAiResponseFormat(
    val type: String,
    @SerialName("json_schema")
    val jsonSchema: OpenAiJsonSchema? = null,
)

@Serializable
data class OpenAiJsonSchema(
    val name: String,
    val schema: JsonElement,
    val strict: Boolean = true,
)

internal fun GenerativeAIChatMessage.toOpenAiChatMessage() = OpenAiChatMessage(role, content)
