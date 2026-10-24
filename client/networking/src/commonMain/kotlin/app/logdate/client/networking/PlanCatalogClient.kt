package app.logdate.client.networking

import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.PlanCatalogResponse
import app.logdate.shared.model.PlanOption
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json

/**
 * Reads the plans a server offers.
 *
 * Unauthenticated on purpose: the catalog is shown while someone is still deciding whether to
 * create an account, so it has to be readable before there is one.
 */
interface PlanCatalogClient {
    /** Reads the catalog from the server the app is currently pointed at. */
    suspend fun fetchPlans(): Result<List<PlanOption>>

    /** Reads the catalog from a specific server, for previewing one before switching to it. */
    suspend fun fetchPlans(serverOrigin: String): Result<List<PlanOption>>
}

class DefaultPlanCatalogClient(
    private val httpClient: HttpClient,
    private val configRepository: LogDateConfigRepository,
) : PlanCatalogClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchPlans(): Result<List<PlanOption>> = fetchPlans(configRepository.getCurrentBackendUrl())

    override suspend fun fetchPlans(serverOrigin: String): Result<List<PlanOption>> =
        try {
            val normalizedOrigin = normalizeOrigin(serverOrigin)
            val response = httpClient.get("$normalizedOrigin/api/v1/plans")
            when {
                // A server predating this endpoint, or one that has it switched off, is not a
                // failure — it simply sells nothing, and the plan step is skipped.
                response.status == HttpStatusCode.NotFound -> {
                    Result.success(emptyList())
                }

                response.status.isSuccess() -> {
                    val payload = json.decodeFromString<PlanCatalogResponse>(response.bodyAsText())
                    Result.success(payload.data)
                }

                else -> {
                    Result.failure(
                        PlanCatalogException("Plan catalog request failed with ${response.status}"),
                    )
                }
            }
        } catch (e: Exception) {
            Napier.e("Plan catalog lookup failed", e)
            Result.failure(PlanCatalogException("Failed to read plans: ${e.message}", e))
        }

    private fun normalizeOrigin(serverOrigin: String): String {
        val trimmed = serverOrigin.trim().trimEnd('/')
        return if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

class PlanCatalogException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
