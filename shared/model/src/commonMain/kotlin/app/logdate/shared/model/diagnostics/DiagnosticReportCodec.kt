package app.logdate.shared.model.diagnostics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val SERVER_BUILD = Regex("[0-9a-f]{7,64}")

/** Reject unknown fields rather than allowing a newer or malicious producer to smuggle content. */
object DiagnosticReportCodec {
    const val MAX_REPORT_BYTES = 256 * 1024
    const val MAX_EVENTS = 512
    private val json =
        Json {
            encodeDefaults = true
            coerceInputValues = true
        }

    fun decode(value: String): SyncDiagnosticReport {
        require(value.length <= MAX_REPORT_BYTES && value.encodeToByteArray().size <= MAX_REPORT_BYTES) { "Diagnostic report too large" }
        return try {
            json.decodeFromString<SyncDiagnosticReport>(value).also(::validate)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid diagnostic report")
        }
    }

    fun encode(report: SyncDiagnosticReport): String {
        validate(report)
        return json.encodeToString(report).also {
            require(it.encodeToByteArray().size <= MAX_REPORT_BYTES) { "Diagnostic report too large" }
        }
    }

    fun validate(report: SyncDiagnosticReport) {
        require(report.schemaVersion == 1 && report.droppedEvents >= 0) { "Unsupported diagnostic report" }
        require(isCorrelationId(report.reportId)) { "Invalid report reference" }
        require(report.events.size <= MAX_EVENTS) { "Too many diagnostic events" }
        report.events.forEach(::validateEvent)
    }

    fun validateContext(context: DiagnosticContext) {
        require(context.appBuild == null || context.appBuild >= 0) { "Invalid build number" }
        require(
            listOf(context.appVersion, context.osVersion).all { parts ->
                parts.size <= 4 && parts.all { it in 0..99999 }
            },
        ) { "Invalid diagnostic version" }
        require(context.serverBuild == null || SERVER_BUILD.matches(context.serverBuild)) { "Invalid server build" }
        require(context.protocols.size <= DiagnosticProtocol.entries.size && context.protocols.distinct().size == context.protocols.size) {
            "Invalid protocol versions"
        }
    }

    fun validateEvent(event: SyncDiagnosticEvent) {
        require(event.schemaVersion == 1) { "Unsupported event version" }
        event.context?.let(::validateContext)
        require(event.frames.size <= 8 && event.frames.all { it.line == null || it.line in 1..100000 }) { "Invalid frame metadata" }
        require(
            listOf(event.runId, event.operationId, event.attemptId, event.requestId, event.recordAlias).all {
                it == null ||
                    isCorrelationId(it)
            },
        ) {
            "Invalid diagnostic reference"
        }
        require(event.elapsedMs >= 0 && event.durationMs >= 0 && event.bytes >= 0 && event.attemptCount >= 0 && event.pendingCount >= 0) {
            "Invalid diagnostic counter"
        }
        require(event.httpStatus == null || event.httpStatus in 100..599) { "Invalid response status" }
    }

    fun isCorrelationId(value: String): Boolean =
        value.length == 36 &&
            value[14] == '4' &&
            value[19] in "89ab" &&
            runCatching { Uuid.parse(value).toString() == value }.getOrDefault(false)
}
