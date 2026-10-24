@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes.sync

import app.logdate.server.auth.TokenService
import app.logdate.server.diagnostics.DiagnosticReportService
import app.logdate.server.diagnostics.DiagnosticSaveResult
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.DiagnosticReportDocs
import app.logdate.shared.model.diagnostics.DiagnosticReportCodec
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

@Serializable
internal data class DiagnosticReportReference(
    val reportId: String,
)

@Serializable
internal data class DiagnosticReportListing(
    val reports: List<DiagnosticReportItem>,
)

@Serializable
internal data class DiagnosticReportItem(
    val reportId: String,
    val createdAt: Long,
)

/**
 * Mounted on every server so the API is discoverable; a server that has not switched reports on
 * answers `503 DIAGNOSTIC_REPORTS_UNAVAILABLE` after authentication, matching its descriptor.
 */
internal fun Route.diagnosticReportRoutes(
    tokenService: TokenService?,
    service: DiagnosticReportService?,
) {
    route("/diagnostics/reports") {
        post(DiagnosticReportDocs.uploadReport) {
            val owner = call.reportOwner(tokenService) ?: return@post
            val available = call.availableService(service) ?: return@post
            call.createReport(owner, available)
        }

        get(DiagnosticReportDocs.listReports) {
            val owner = call.reportOwner(tokenService) ?: return@get
            val available = call.availableService(service) ?: return@get
            call.listReports(owner, available)
        }

        get("/{reportId}", DiagnosticReportDocs.getReport) {
            val owner = call.reportOwner(tokenService) ?: return@get
            val available = call.availableService(service) ?: return@get
            call.readReport(owner, available)
        }

        delete("/{reportId}", DiagnosticReportDocs.deleteReport) {
            val owner = call.reportOwner(tokenService) ?: return@delete
            val available = call.availableService(service) ?: return@delete
            call.deleteReport(owner, available)
        }

        delete(DiagnosticReportDocs.deleteAllReports) {
            val owner = call.reportOwner(tokenService) ?: return@delete
            val available = call.availableService(service) ?: return@delete
            call.deleteAllReports(owner, available)
        }
    }
}

private suspend fun RoutingCall.reportOwner(tokenService: TokenService?): Uuid? =
    extractUserId(this, tokenService)?.let { Uuid.parse(it.toString()) }

private suspend fun RoutingCall.availableService(service: DiagnosticReportService?): DiagnosticReportService? {
    if (service == null) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            error("DIAGNOSTIC_REPORTS_UNAVAILABLE", "Diagnostic reports are not enabled on this server"),
        )
    }
    return service
}

private suspend fun RoutingCall.createReport(
    owner: Uuid,
    service: DiagnosticReportService,
) {
    val payload = receiveReportPayload() ?: return
    val reportId =
        try {
            Uuid.parse(DiagnosticReportCodec.decode(payload).reportId)
        } catch (_: IllegalArgumentException) {
            return respond(HttpStatusCode.BadRequest, error("INVALID_REPORT", "Invalid diagnostic report"))
        }
    val result =
        try {
            service.create(owner, payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return respond(HttpStatusCode.BadRequest, error("INVALID_REPORT", "Invalid diagnostic report"))
        } catch (_: Exception) {
            return respond(
                HttpStatusCode.InternalServerError,
                error("REPORT_STORAGE_FAILED", "Diagnostic report unavailable"),
            )
        }
    respondToSave(result, reportId)
}

/** Responds and returns null when the body is oversized or not valid UTF-8. */
private suspend fun RoutingCall.receiveReportPayload(): String? {
    val declaredSize = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredSize != null && declaredSize > DiagnosticReportCodec.MAX_REPORT_BYTES) {
        respond(HttpStatusCode.PayloadTooLarge, error("REPORT_TOO_LARGE", "Diagnostic report too large"))
        return null
    }
    val bytes = receiveChannel().readRemaining((DiagnosticReportCodec.MAX_REPORT_BYTES + 1).toLong()).readByteArray()
    if (bytes.size > DiagnosticReportCodec.MAX_REPORT_BYTES) {
        respond(HttpStatusCode.PayloadTooLarge, error("REPORT_TOO_LARGE", "Diagnostic report too large"))
        return null
    }
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        respond(HttpStatusCode.BadRequest, error("INVALID_REPORT", "Invalid diagnostic report"))
        null
    }
}

private suspend fun RoutingCall.respondToSave(
    result: DiagnosticSaveResult,
    reportId: Uuid,
) {
    when (result) {
        DiagnosticSaveResult.CREATED -> {
            response.header(HttpHeaders.Location, "/api/v1/diagnostics/reports/$reportId")
            respond(HttpStatusCode.Created, DiagnosticReportReference(reportId.toString()))
        }

        DiagnosticSaveResult.EXISTING -> {
            respond(HttpStatusCode.OK, DiagnosticReportReference(reportId.toString()))
        }

        DiagnosticSaveResult.CONFLICT -> {
            respond(
                HttpStatusCode.Conflict,
                error("REPORT_ID_CONFLICT", "Report reference already used"),
            )
        }

        DiagnosticSaveResult.DAILY_LIMIT -> {
            respond(
                HttpStatusCode.TooManyRequests,
                error("REPORT_RATE_LIMITED", "Daily report limit reached"),
            )
        }
    }
}

private suspend fun RoutingCall.listReports(
    owner: Uuid,
    service: DiagnosticReportService,
) {
    val reports =
        try {
            service.list(owner)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return respond(
                HttpStatusCode.InternalServerError,
                error("REPORT_STORAGE_FAILED", "Diagnostic reports unavailable"),
            )
        }
    respond(DiagnosticReportListing(reports.map { DiagnosticReportItem(it.reportId.toString(), it.createdAt) }))
}

private suspend fun RoutingCall.readReport(
    owner: Uuid,
    service: DiagnosticReportService,
) {
    val reportId =
        reportIdParameter()
            ?: return respond(HttpStatusCode.NotFound, error("REPORT_NOT_FOUND", "Report not found"))
    val report =
        try {
            service.read(owner, reportId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return respond(
                HttpStatusCode.InternalServerError,
                error("REPORT_STORAGE_FAILED", "Diagnostic report unavailable"),
            )
        }
    if (report == null) {
        respond(HttpStatusCode.NotFound, error("REPORT_NOT_FOUND", "Report not found"))
    } else {
        respond(report)
    }
}

private suspend fun RoutingCall.deleteReport(
    owner: Uuid,
    service: DiagnosticReportService,
) {
    val reportId =
        reportIdParameter()
            ?: return respond(HttpStatusCode.NotFound, error("REPORT_NOT_FOUND", "Report not found"))
    val deleted =
        try {
            service.delete(owner, reportId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return respond(
                HttpStatusCode.InternalServerError,
                error("REPORT_STORAGE_FAILED", "Diagnostic report unavailable"),
            )
        }
    if (deleted) {
        respond(HttpStatusCode.NoContent)
    } else {
        respond(HttpStatusCode.NotFound, error("REPORT_NOT_FOUND", "Report not found"))
    }
}

private suspend fun RoutingCall.deleteAllReports(
    owner: Uuid,
    service: DiagnosticReportService,
) {
    try {
        service.deleteAll(owner)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return respond(
            HttpStatusCode.InternalServerError,
            error("REPORT_STORAGE_FAILED", "Diagnostic reports unavailable"),
        )
    }
    respond(HttpStatusCode.NoContent)
}

private fun RoutingCall.reportIdParameter(): Uuid? =
    parameters["reportId"]?.takeIf(DiagnosticReportCodec::isCorrelationId)?.let(Uuid::parse)
