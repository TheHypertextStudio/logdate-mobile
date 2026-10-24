package app.logdate.client.sync.cloud

import io.github.aakira.napier.Napier
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

internal suspend fun LogDateCloudApiClient.requestUploadJournal(
    accessToken: String,
    journal: JournalUploadRequest,
): Result<JournalUploadResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.put("$baseUrl/journals/${journal.id}") {
                headers.append("Authorization", "Bearer $accessToken")
                headers.append(HttpHeaders.IfNoneMatch, "*")
                contentType(ContentType.Application.Json)
                setBody(journal)
            }

        when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> {
                val responseBody = response.body<JournalUploadResponse>()
                Result.success(responseBody)
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to upload journal")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to upload journal",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestGetJournalChanges(
    accessToken: String,
    since: Long,
    limit: Int?,
): Result<JournalChangesResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val limitParam = limit?.let { "&limit=$it" }.orEmpty()
        val response =
            transport.get("$baseUrl/journals?since=$since$limitParam") {
                headers.append("Authorization", "Bearer $accessToken")
            }

        when (response.status) {
            HttpStatusCode.OK -> {
                val responseBody = response.body<JournalChangesResponse>()
                Result.success(responseBody)
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to get journal changes")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to get journal changes",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestUpdateJournal(
    accessToken: String,
    journalId: String,
    journal: JournalUpdateRequest,
): Result<JournalUpdateResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.patch("$baseUrl/journals/$journalId") {
                headers.append("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(journal)
            }

        when (response.status) {
            HttpStatusCode.OK -> {
                val responseBody = response.body<JournalUpdateResponse>()
                Result.success(responseBody)
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to update journal")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to update journal",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestDeleteJournal(
    accessToken: String,
    journalId: String,
): Result<Unit> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.delete("$baseUrl/journals/$journalId") {
                headers.append("Authorization", "Bearer $accessToken")
            }

        when (response.status) {
            HttpStatusCode.NoContent -> Result.success(Unit)
            else -> handleApiError(response)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to delete journal")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to delete journal",
            ),
        )
    }

// Association Sync Operations
internal suspend fun LogDateCloudApiClient.requestUploadAssociations(
    accessToken: String,
    associations: AssociationUploadRequest,
): Result<AssociationUploadResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.post("$baseUrl/associations") {
                headers.append("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(associations)
            }

        when (response.status) {
            HttpStatusCode.OK -> {
                val responseBody = response.body<AssociationUploadResponse>()
                Result.success(responseBody)
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to upload associations")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to upload associations",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestGetAssociationChanges(
    accessToken: String,
    since: Long,
    limit: Int?,
): Result<AssociationChangesResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val limitParam = limit?.let { "&limit=$it" }.orEmpty()
        val response =
            transport.get("$baseUrl/associations?since=$since$limitParam") {
                headers.append("Authorization", "Bearer $accessToken")
            }

        when (response.status) {
            HttpStatusCode.OK -> {
                val responseBody = response.body<AssociationChangesResponse>()
                Result.success(responseBody)
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to get association changes")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to get association changes",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestDeleteAssociations(
    accessToken: String,
    associations: AssociationDeleteRequest,
): Result<Unit> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.delete("$baseUrl/associations") {
                headers.append("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(associations)
            }

        when (response.status) {
            HttpStatusCode.NoContent -> Result.success(Unit)
            else -> handleApiError(response)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to delete associations")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to delete associations",
            ),
        )
    }
