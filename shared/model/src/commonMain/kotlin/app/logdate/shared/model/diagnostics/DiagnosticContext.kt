package app.logdate.shared.model.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticPlatform { UNKNOWN, ANDROID, IOS, DESKTOP, SERVER }

@Serializable
enum class DiagnosticProtocol { SYNC_V1, RICH_DRAFTS_V1, MEDIA_V2, DIAGNOSTICS_V1 }

@Serializable
enum class DiagnosticNetworkState { UNKNOWN, OFFLINE, ONLINE }

@Serializable
enum class DiagnosticSchedulerState { UNKNOWN, RUNNING, WAITING }

@Serializable
enum class DiagnosticCode {
    PHASE_TRANSITION,
    WORK_QUEUED,
    ATTEMPT_STARTED,
    REQUEST_COMPLETED,
    PAGE_PERSISTED,
    RECORD_APPLIED,
    RETRY_SCHEDULED,
    INTERRUPTED,
    CONFLICT,
    COMPLETED,
    FAILED,
    USER_RETRY,
}

@Serializable
enum class DiagnosticComponent { SYNC, MEDIA, ARCHIVE, STORAGE, NETWORK, SERVER }

/** Numeric versions and finite categories cannot carry hostnames, paths, or device identifiers. */
@Serializable
data class DiagnosticContext(
    val appBuild: Int? = null,
    val appVersion: List<Int> = emptyList(),
    val serverBuild: String? = null,
    val platform: DiagnosticPlatform = DiagnosticPlatform.UNKNOWN,
    val osVersion: List<Int> = emptyList(),
    val protocols: List<DiagnosticProtocol> = emptyList(),
    val network: DiagnosticNetworkState = DiagnosticNetworkState.UNKNOWN,
    val scheduler: DiagnosticSchedulerState = DiagnosticSchedulerState.UNKNOWN,
)

/** No class names, method names, file names, exception types, or exception messages. */
@Serializable
data class DiagnosticFrame(
    val component: DiagnosticComponent,
    val line: Int? = null,
)

fun diagnosticCode(event: SyncDiagnosticEvent): DiagnosticCode =
    when {
        event.code != DiagnosticCode.PHASE_TRANSITION -> event.code
        event.outcome == DiagnosticOutcome.INTERRUPTED -> DiagnosticCode.INTERRUPTED
        event.outcome == DiagnosticOutcome.CONFLICT -> DiagnosticCode.CONFLICT
        event.outcome == DiagnosticOutcome.RETRY_SCHEDULED -> DiagnosticCode.RETRY_SCHEDULED
        event.outcome == DiagnosticOutcome.STARTED -> DiagnosticCode.ATTEMPT_STARTED
        event.outcome == DiagnosticOutcome.QUEUED -> DiagnosticCode.WORK_QUEUED
        event.requestId != null -> DiagnosticCode.REQUEST_COMPLETED
        event.outcome == DiagnosticOutcome.FAILED -> DiagnosticCode.FAILED
        event.phase == DiagnosticPhase.PERSIST && event.outcome == DiagnosticOutcome.SUCCEEDED -> DiagnosticCode.PAGE_PERSISTED
        event.phase == DiagnosticPhase.APPLY && event.outcome == DiagnosticOutcome.SUCCEEDED -> DiagnosticCode.RECORD_APPLIED
        event.outcome == DiagnosticOutcome.SUCCEEDED -> DiagnosticCode.COMPLETED
        else -> DiagnosticCode.PHASE_TRANSITION
    }

private val DIAGNOSTIC_VERSION = Regex("[0-9]{1,5}(\\.[0-9]{1,5}){0,3}")

fun diagnosticVersion(value: String?): List<Int> {
    if (value == null || !DIAGNOSTIC_VERSION.matches(value)) return emptyList()
    return value.split('.').map { it.toInt() }
}
