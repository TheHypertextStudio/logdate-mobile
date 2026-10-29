@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route

private const val METRIC_AUTH_METRICS = "auth.metrics"
private const val METRIC_AUTH_METRICS_PROM = "auth.metrics.prometheus"

internal fun Route.registerMetricsRoute(
    accountRepository: AccountRepository,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    get("/metrics", { hidden = true }) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            if (resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics) == null) {
                return@get
            }
            call.respond(HttpStatusCode.OK, AuthMetricsResponse(success = true, data = metrics.snapshot()))
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to render auth metrics", e)
            call.respondForRequestException(e, "Failed to render auth metrics", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_METRICS, System.currentTimeMillis() - start, success)
        }
    }
}

internal fun Route.registerMetricsPrometheusRoute(
    accountRepository: AccountRepository,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
) {
    get("/metrics/prometheus", { hidden = true }) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            if (resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics) == null) {
                return@get
            }
            call.respondText(metrics.snapshot().toPrometheus(), ContentType.Text.Plain)
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to render auth metrics in Prometheus format", e)
            call.respondForRequestException(e, "Failed to render auth metrics", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_METRICS_PROM, System.currentTimeMillis() - start, success)
        }
    }
}

private fun app.logdate.server.auth.AuthMetricsSnapshot.toPrometheus(): String {
    val builder = StringBuilder()
    builder.appendLine("# HELP logdate_auth_operation_success_total Successful auth operations by type.")
    builder.appendLine("# TYPE logdate_auth_operation_success_total counter")
    builder.appendLine("# HELP logdate_auth_operation_error_total Failed auth operations by type.")
    builder.appendLine("# TYPE logdate_auth_operation_error_total counter")
    builder.appendLine("# HELP logdate_auth_operation_duration_ms_total Total auth operation duration by type.")
    builder.appendLine("# TYPE logdate_auth_operation_duration_ms_total counter")
    builder.appendLine("# HELP logdate_auth_error_total Auth API errors by error code.")
    builder.appendLine("# TYPE logdate_auth_error_total counter")
    builder.appendLine("# HELP logdate_auth_rate_limit_total Auth rate-limited requests by operation.")
    builder.appendLine("# TYPE logdate_auth_rate_limit_total counter")

    operations.forEach { operation ->
        val label = escapeAuthMetricLabel(operation.name)
        builder.appendLine("logdate_auth_operation_success_total{operation=\"$label\"} ${operation.successCount}")
        builder.appendLine("logdate_auth_operation_error_total{operation=\"$label\"} ${operation.errorCount}")
        builder.appendLine("logdate_auth_operation_duration_ms_total{operation=\"$label\"} ${operation.totalDurationMs}")
    }
    errorsByCode.forEach { (code, count) ->
        val label = escapeAuthMetricLabel(code)
        builder.appendLine("logdate_auth_error_total{code=\"$label\"} $count")
    }
    rateLimitedByOperation.forEach { (operation, count) ->
        val label = escapeAuthMetricLabel(operation)
        builder.appendLine("logdate_auth_rate_limit_total{operation=\"$label\"} $count")
    }

    return builder.toString()
}

private fun escapeAuthMetricLabel(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
