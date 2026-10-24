package app.logdate.client.domain.timeline

import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.AIUnavailableReason
import app.logdate.client.intelligence.EntrySummarizer
import app.logdate.client.intelligence.MAX_AI_INPUT_CHARACTERS
import app.logdate.client.repository.journals.JournalNote
import io.github.aakira.napier.Napier
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A use case that summarizes journal entries.
 *
 * This relies on [EntrySummarizer], which returns cached summaries while offline and reports
 * [SummarizeJournalEntriesResult.NetworkUnavailable] when no cached summary is available.
 */
class SummarizeJournalEntriesUseCase(
    private val summarizer: EntrySummarizer,
) {
    suspend operator fun invoke(entries: List<JournalNote>): SummarizeJournalEntriesResult {
        if (entries.isEmpty()) {
            return SummarizeJournalEntriesResult.SummaryUnavailable
        }
        // Sort entries by creation timestamp to ensure chronological order
        val sortedEntries =
            entries
                .filterIsInstance<JournalNote.Text>()
                .sortedBy { it.creationTimestamp }

        if (sortedEntries.sumOf { it.content.length.toLong() + 64 } > MAX_AI_INPUT_CHARACTERS) {
            return SummarizeJournalEntriesResult.SummaryUnavailable
        }

        val summaryPrompt =
            sortedEntries
                .joinToString("\n") {
                    """
Date: ${it.creationTimestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date}
Content:
${it.content}
                    """.trimIndent()
                }

        // Create a fixed-length key by hashing the entry UIDs
        val summaryKey = generateHashKey(entries.map { it.uid.toString() })

        return when (val result = summarizer.summarize(summaryKey, summaryPrompt)) {
            is AIResult.Success -> {
                SummarizeJournalEntriesResult.Success(result.value)
            }

            is AIResult.Unavailable -> {
                when (result.reason) {
                    AIUnavailableReason.NoNetwork -> SummarizeJournalEntriesResult.NetworkUnavailable
                    else -> SummarizeJournalEntriesResult.SummaryUnavailable
                }
            }

            is AIResult.Error -> {
                Napier.e(
                    tag = "SummarizeJournalEntriesUseCase",
                    throwable = result.throwable,
                    message = "Could not summarize journal entries: ${result.error}",
                )
                SummarizeJournalEntriesResult.SummaryUnavailable
            }
        }
    }

    /**
     * Generates a fixed-length hash key from a list of strings using a simple
     * multiplatform-compatible algorithm.
     *
     * @param keys List of string keys to be hashed
     * @return A fixed-length string representation of the hash
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun generateHashKey(keys: List<String>): String {
        val combinedKey = keys.sorted().joinToString("_")

        return try {
            // Simple FNV-1a hash implementation (multiplatform compatible)
            val hash = fnv1aHash(combinedKey)

            // Convert to Base64 for a compact string representation
            val hashBytes =
                ByteArray(4) { i ->
                    ((hash shr (i * 8)) and 0xFF).toByte()
                }

            val base64 = Base64.encode(hashBytes)

            // Replace any non-alphanumeric characters for cache key safety
            base64.replace(Regex("[^a-zA-Z0-9]"), "")
        } catch (e: Exception) {
            Napier.e(
                tag = "SummarizeJournalEntriesUseCase",
                throwable = e,
                message = "Failed to generate hash key, falling back to shortened key",
            )
            // Fallback to a truncated version of the original key if hashing fails
            combinedKey.take(32)
        }
    }

    /**
     * Simple FNV-1a hash implementation that works across platforms.
     * Modified to avoid overflow issues by using a 32-bit implementation.
     *
     * @param input String to hash
     * @return 32-bit hash value as Int
     */
    private fun fnv1aHash(input: String): Int {
        // FNV-1a hash parameters for 32-bit
        val fnvPrime = 16777619
        var hash = 2166136261u.toInt()

        for (byte in input.encodeToByteArray()) {
            hash = hash xor byte.toInt()
            hash = ((hash * fnvPrime) and 0xFFFFFFFF.toInt())
        }

        return hash
    }
}

sealed interface SummarizeJournalEntriesResult {
    /**
     * A summary could not be generated because the client was not connected to the internet.
     */
    data object NetworkUnavailable : SummarizeJournalEntriesResult

    /**
     * A summary was unavailable because the request was invalid.
     *
     * This may be returned if there was not enough or no entries available to be summarized.
     */
    data object SummaryUnavailable : SummarizeJournalEntriesResult

    /**
     * Journal entries were able to be summarized.
     */
    data class Success(
        val summary: String,
    ) : SummarizeJournalEntriesResult
}
