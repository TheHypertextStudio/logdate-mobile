package app.logdate.client.domain.assistants

import app.logdate.client.repository.assistants.AssistantProtocolLimits
import app.logdate.client.repository.assistants.AssistantSearchRequest
import app.logdate.client.repository.assistants.AssistantSourceReadRequest
import kotlinx.datetime.LocalDate

enum class AssistantInputError {
    INVALID_QUERY,
    INVALID_LIMIT,
    INVALID_DATE,
    INVALID_CURSOR,
}

sealed interface AssistantInputDecision {
    data object Valid : AssistantInputDecision

    data class Invalid(
        val reason: AssistantInputError,
    ) : AssistantInputDecision
}

class AssistantInputValidation {
    fun search(request: AssistantSearchRequest): AssistantInputDecision {
        if (!validText(request.query, AssistantProtocolLimits.QUERY_CODE_POINTS)) return invalid(AssistantInputError.INVALID_QUERY)
        if (request.limit !in 1..AssistantProtocolLimits.SEARCH_SOURCES) return invalid(AssistantInputError.INVALID_LIMIT)
        if (!validCursor(request.cursor)) return invalid(AssistantInputError.INVALID_CURSOR)
        val from = request.fromDate?.let(::date)
        val through = request.throughDate?.let(::date)
        if ((request.fromDate != null && from == null) ||
            (request.throughDate != null && through == null) ||
            (from != null && through != null && from > through)
        ) {
            return invalid(AssistantInputError.INVALID_DATE)
        }
        return AssistantInputDecision.Valid
    }

    fun sourceRead(request: AssistantSourceReadRequest): AssistantInputDecision {
        if (request.limit !in 1..AssistantProtocolLimits.SOURCE_CODE_POINTS) return invalid(AssistantInputError.INVALID_LIMIT)
        if (!validCursor(request.cursor)) return invalid(AssistantInputError.INVALID_CURSOR)
        return AssistantInputDecision.Valid
    }

    private fun validCursor(value: String?): Boolean = value == null || validOpaqueId(value, AssistantProtocolLimits.CURSOR_LENGTH)

    private fun validOpaqueId(
        value: String,
        maximum: Int,
    ): Boolean = AssistantProtocolLimits.opaqueIdFits(value, maximum)

    private fun date(value: String): LocalDate? =
        try {
            if (value.length in 1..32) LocalDate.parse(value) else null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun validText(
        value: String,
        maximum: Int,
    ): Boolean = value.isNotBlank() && AssistantProtocolLimits.textFits(value, maximum)

    private fun invalid(reason: AssistantInputError) = AssistantInputDecision.Invalid(reason)
}
