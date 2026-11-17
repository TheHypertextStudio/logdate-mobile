package app.logdate.client.sync.diagnostics

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.sync.SyncTokenRefresher
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.CloudRequestBinding
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.CloudAccountRepository
import app.logdate.shared.model.diagnostics.DiagnosticReportCodec
import app.logdate.shared.model.diagnostics.SyncDiagnosticReport
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareDelete
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

internal class DiagnosticReportGateway(
    private val httpClient: HttpClient,
    private val config: LogDateConfigRepository,
    private val sessions: SessionStorage,
    accounts: CloudAccountRepository,
) {
    private val transport =
        httpClient.config {
            followRedirects = false
            expectSuccess = false
        }

    fun close() = transport.close()

    private val tokens = SyncTokenRefresher(sessions, accounts)

    suspend fun send(
        scope: UploadScope,
        report: SyncDiagnosticReport,
    ): DiagnosticDelivery {
        val body = DiagnosticReportCodec.encode(report)
        val status = request(scope, body) ?: return DiagnosticDelivery.RETRY
        return when (status) {
            in 200..299 -> DiagnosticDelivery.ACCEPTED
            429 -> DiagnosticDelivery.RATE_LIMITED
            in 400..499 -> DiagnosticDelivery.REJECTED
            else -> DiagnosticDelivery.RETRY
        }
    }

    suspend fun deleteAll(scope: UploadScope): Boolean = request(scope, null)?.let { it in 200..299 } == true

    private suspend fun request(
        scope: UploadScope,
        body: String?,
    ): Int? =
        withContext(SuppressDiagnosticReporting) {
            if (config.getCurrentBackendUrl() != scope.serverOrigin ||
                sessions.getSession()?.accountId != scope.ownerId
            ) {
                return@withContext null
            }
            tokens
                .withFreshToken(
                    operationName = "diagnostic report",
                    expectedScope = scope,
                    operation = { token ->
                        try {
                            val bound = requireNotNull(currentCoroutineContext()[CloudRequestBinding])
                            val url = "${bound.location.apiBaseUrl.trimEnd('/')}/diagnostics/reports"
                            val request: HttpRequestBuilder.() -> Unit = {
                                bearerAuth(token)
                                header("X-Request-ID", Uuid.random().toString())
                                if (body != null) {
                                    contentType(ContentType.Application.Json)
                                    setBody(body)
                                }
                            }
                            val statement = if (body == null) transport.prepareDelete(url, request) else transport.preparePost(url, request)
                            statement.execute { response ->
                                if (response.status.value == 401) {
                                    Result.failure(CloudApiException("UNAUTHORIZED", "SIGN_IN_REQUIRED", 401))
                                } else {
                                    Result.success(response.status.value)
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Result.failure(IllegalStateException("DIAGNOSTIC_DELIVERY_FAILED"))
                        }
                    },
                ).getOrNull()
        }
}
