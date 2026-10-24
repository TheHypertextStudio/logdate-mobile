package app.logdate.client.domain.onboarding

import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.intelligence.generativeai.GenerativeAIChatMessage
import app.logdate.client.intelligence.generativeai.GenerativeAIRequest
import app.logdate.client.intelligence.generativeai.GenerativeAIResponseFormat
import app.logdate.client.intelligence.structured.JsonStructuredOutputParser
import app.logdate.client.intelligence.structured.StructuredOutputResult
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.shared.model.profile.LogDateProfile
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Use case for processing personal introduction during onboarding.
 *
 * This use case handles the complete flow of collecting user's name and bio,
 * processing it with LLM for a friendly response, and saving to the local profile.
 */
class ProcessPersonalIntroductionUseCase(
    private val profileRepository: ProfileRepository,
    private val generativeAiClient: GenerativeAIChatClient,
    private val networkAvailabilityMonitor: NetworkAvailabilityMonitor,
) {
    private companion object {
        private const val RESPONSE_SCHEMA = """
{
  "type": "object",
  "properties": {
    "message": { "type": "string" }
  },
  "required": ["message"],
  "additionalProperties": false
}
"""
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * Process the personal introduction with name, bio, and LLM response.
     *
     * @param name The user's display name
     * @param bio The user's bio/self-description
     * @return Result containing the processed introduction data
     */
    suspend operator fun invoke(
        name: String,
        bio: String,
    ): Result {
        return try {
            Napier.d("Processing personal introduction for user: $name")

            // Generate friendly LLM response
            val llmResponse = generateFriendlyResponse(name, bio)

            // Save display name to profile
            val nameResult = profileRepository.updateDisplayName(name.trim())
            if (nameResult.isFailure) {
                val exception = nameResult.exceptionOrNull()
                Napier.e("Failed to save display name", exception)
                return Result.Error(ErrorReason.SaveDisplayNameFailed)
            }

            // Save bio to profile (both LLM-processed and original)
            val bioResult =
                profileRepository.updateBio(
                    bio = llmResponse,
                    originalBio = bio.trim(),
                )
            if (bioResult.isFailure) {
                val exception = bioResult.exceptionOrNull()
                Napier.e("Failed to save bio", exception)
                return Result.Error(ErrorReason.SaveBioFailed)
            }

            val updatedProfile =
                bioResult.getOrNull()
                    ?: return Result.Error(ErrorReason.ProfileUpdateFailed)

            Napier.d("Personal introduction processed successfully")
            Result.Success(
                ProcessedIntroduction(
                    profile = updatedProfile,
                    llmResponse = llmResponse,
                    originalBio = bio.trim(),
                ),
            )
        } catch (e: Exception) {
            Napier.e("Unexpected error processing personal introduction", e)
            Result.Error(ErrorReason.UnexpectedFailure)
        }
    }

    /**
     * Generate a friendly, reflective response using the LLM.
     */
    private suspend fun generateFriendlyResponse(
        name: String,
        bio: String,
    ): String {
        return try {
            if (!networkAvailabilityMonitor.isNetworkAvailable()) {
                return createFallbackResponse(name)
            }
            val systemMessage =
                GenerativeAIChatMessage(
                    role = "system",
                    content = """You are a friendly, warm AI assistant helping someone set up their personal journal app called LogDate. 

Your role is to respond as a supportive friend who's excited to get to know them after they share their name and a bit about themselves.

Guidelines:
- Keep responses to 2-3 sentences maximum
- Be encouraging and personal
- Reflect back what they shared in a warm, genuine way
- Express enthusiasm about their journaling journey
- Don't ask questions - just acknowledge and encourage
- Use their name naturally in your response

Return a JSON object with a "message" field that contains the response.""",
                )

            val userMessage =
                GenerativeAIChatMessage(
                    role = "user",
                    content = "My name is $name and here's a bit about me: $bio",
                )

            val response =
                generativeAiClient.submit(
                    GenerativeAIRequest(
                        messages = listOf(systemMessage, userMessage),
                        model = generativeAiClient.defaultModel,
                        responseFormat =
                            GenerativeAIResponseFormat.JsonSchema(
                                name = "personal_introduction",
                                schema = RESPONSE_SCHEMA,
                            ),
                    ),
                )
            when (response) {
                is app.logdate.client.intelligence.AIResult.Success -> {
                    parseResponse(response.value.content)
                        ?: createFallbackResponse(name)
                }

                else -> {
                    createFallbackResponse(name)
                }
            }
        } catch (e: Exception) {
            Napier.w("LLM processing failed, using fallback response", e)
            createFallbackResponse(name)
        }
    }

    private fun parseResponse(raw: String): String? {
        val parser =
            JsonStructuredOutputParser(
                json = json,
                serializer = FriendlyResponse.serializer(),
                allowEmbeddedJson = true,
            )
        return when (val result = parser.parse(raw)) {
            is StructuredOutputResult.Success -> {
                result.value.message
                    .trim()
                    .takeIf { it.isNotBlank() }
            }

            StructuredOutputResult.Empty -> {
                null
            }

            StructuredOutputResult.Invalid -> {
                null
            }
        }
    }

    /**
     * Create a fallback response when LLM processing fails.
     */
    private fun createFallbackResponse(name: String): String =
        "It's wonderful to meet you, $name! I can tell you have some great stories to share, " +
            "and I'm excited to help you capture all your memories in LogDate."

    /**
     * Result sealed class for the use case operation.
     */
    sealed interface Result {
        data class Success(
            val data: ProcessedIntroduction,
        ) : Result

        data class Error(
            val reason: ErrorReason,
        ) : Result
    }

    sealed interface ErrorReason {
        data object SaveDisplayNameFailed : ErrorReason

        data object SaveBioFailed : ErrorReason

        data object ProfileUpdateFailed : ErrorReason

        data object UnexpectedFailure : ErrorReason
    }

    /**
     * Data class representing the processed introduction result.
     */
    data class ProcessedIntroduction(
        val profile: LogDateProfile,
        val llmResponse: String,
        val originalBio: String,
    )

    @Serializable
    private data class FriendlyResponse(
        val message: String,
    )
}
