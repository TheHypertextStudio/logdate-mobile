package app.logdate.client.networking

import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.BillingPage
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.CloudSubscription
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The server's billing endpoints: an account's LogDate Cloud plan, and buying, changing and
 * cancelling it. Every call returns a [Result]; a failure is always a [CloudBillingException].
 */
interface CloudBillingApiClientContract {
    suspend fun subscription(accessToken: String): Result<CloudSubscription>

    /** Has the server read the plan back from the payment provider, for after a checkout. */
    suspend fun refreshSubscription(accessToken: String): Result<CloudSubscription>

    /** Opens a checkout for an account that pays for nothing yet. */
    suspend fun startCheckout(
        accessToken: String,
        selection: CloudPlanSelection,
    ): Result<BillingPage>

    suspend fun changePlan(
        accessToken: String,
        selection: CloudPlanSelection,
    ): Result<CloudSubscription>

    suspend fun cancel(accessToken: String): Result<CloudSubscription>

    /** Undoes [cancel] while the plan has not yet stopped. */
    suspend fun keep(accessToken: String): Result<CloudSubscription>

    /** The payment provider's page for payment methods and receipts. */
    suspend fun paymentDetails(accessToken: String): Result<BillingPage>
}

/**
 * Why a billing call failed.
 *
 * [code] is the server's stable error code, such as `PLANS_NOT_ON_SALE` or `STORAGE_EXCEEDS_PLAN`,
 * and [details] carries the numbers that go with it. A failure to reach the server at all has the
 * code [NETWORK]. The server's own message is never shown; the app words each code itself.
 */
class CloudBillingException(
    val code: String,
    val status: Int? = null,
    val details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : Exception("Billing request failed: $code", cause) {
    /** The server has billing switched off, as every self-hosted server does. */
    val billingUnavailable: Boolean get() = code == UNAVAILABLE

    companion object {
        const val NETWORK: String = "NETWORK_ERROR"
        const val UNAVAILABLE: String = "BILLING_UNAVAILABLE"
        const val UNKNOWN: String = "UNKNOWN_ERROR"
    }
}

class CloudBillingApiClient(
    private val httpClient: HttpClient,
    private val configRepository: LogDateConfigRepository,
) : CloudBillingApiClientContract {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    override suspend fun subscription(accessToken: String): Result<CloudSubscription> =
        call { base -> httpClient.get("$base/billing/subscription") { bearer(accessToken) } }

    override suspend fun refreshSubscription(accessToken: String): Result<CloudSubscription> =
        call { base -> httpClient.post("$base/billing/subscription/refresh") { bearer(accessToken) } }

    override suspend fun startCheckout(
        accessToken: String,
        selection: CloudPlanSelection,
    ): Result<BillingPage> = call { base -> httpClient.post("$base/billing/checkout") { bearer(accessToken).plan(selection) } }

    override suspend fun changePlan(
        accessToken: String,
        selection: CloudPlanSelection,
    ): Result<CloudSubscription> =
        call { base -> httpClient.post("$base/billing/subscription/plan") { bearer(accessToken).plan(selection) } }

    override suspend fun cancel(accessToken: String): Result<CloudSubscription> =
        call { base -> httpClient.post("$base/billing/subscription/cancel") { bearer(accessToken) } }

    override suspend fun keep(accessToken: String): Result<CloudSubscription> =
        call { base -> httpClient.post("$base/billing/subscription/keep") { bearer(accessToken) } }

    override suspend fun paymentDetails(accessToken: String): Result<BillingPage> =
        call { base -> httpClient.post("$base/billing/payment-details") { bearer(accessToken) } }

    private fun HttpRequestBuilder.bearer(accessToken: String): HttpRequestBuilder =
        apply {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }

    private fun HttpRequestBuilder.plan(selection: CloudPlanSelection) {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(CloudPlanSelection.serializer(), selection))
    }

    private suspend inline fun <reified T> call(request: (apiBaseUrl: String) -> HttpResponse): Result<T> =
        try {
            val response = request(configRepository.apiBaseUrl.first())
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<T>(body))
            } else {
                Result.failure(failure(response.status, body))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // The body is never logged: it comes from the network and may carry account details.
            Napier.w("Billing request failed: ${e::class.simpleName}")
            Result.failure(CloudBillingException(CloudBillingException.NETWORK, cause = e))
        }

    private fun failure(
        status: HttpStatusCode,
        body: String,
    ): CloudBillingException {
        val error = runCatching { json.decodeFromString<BillingErrorBody>(body) }.getOrNull()
        val code =
            error?.code
                // A server from before billing has no such route at all.
                ?: if (status == HttpStatusCode.NotFound) CloudBillingException.UNAVAILABLE else CloudBillingException.UNKNOWN
        return CloudBillingException(code = code, status = status.value, details = error?.details.orEmpty())
    }
}

@Serializable
private data class BillingErrorBody(
    val code: String,
    val details: Map<String, String> = emptyMap(),
)
