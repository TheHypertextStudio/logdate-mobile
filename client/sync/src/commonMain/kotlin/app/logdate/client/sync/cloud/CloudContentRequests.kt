package app.logdate.client.sync.cloud

import io.github.aakira.napier.Napier
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

internal suspend fun LogDateCloudApiClient.requestUploadContent(
    accessToken: String,
    content: ContentUploadRequest,
): Result<ContentUploadResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.put("$baseUrl/contents/${content.id}") {
                headers.append("Authorization", "Bearer $accessToken")
                headers.append(HttpHeaders.IfNoneMatch, "*")
                contentType(ContentType.Application.Json)
                setBody(content)
            }

        when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> {
                val responseBody = response.body<ContentUploadResponse>()
                Result.success(responseBody)
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to upload content")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to upload content",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestGetContentChanges(
    accessToken: String,
    since: Long,
    limit: Int?,
): Result<ContentChangesResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val limitParam = limit?.let { "&limit=$it" }.orEmpty()
        val response =
            transport.get("$baseUrl/contents?since=$since$limitParam") {
                headers.append("Authorization", "Bearer $accessToken")
            }

        when (response.status) {
            HttpStatusCode.OK -> {
                val responseBody = response.body<ContentChangesResponse>()
                Result.success(responseBody)
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to get content changes")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to get content changes",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestUpdateContent(
    accessToken: String,
    contentId: String,
    content: ContentUpdateRequest,
): Result<ContentUpdateResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.patch("$baseUrl/contents/$contentId") {
                headers.append("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(content)
            }

        when (response.status) {
            HttpStatusCode.OK -> {
                val responseBody = response.body<ContentUpdateResponse>()
                Result.success(responseBody)
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to update content")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to update content",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestDeleteContent(
    accessToken: String,
    contentId: String,
): Result<Unit> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.delete("$baseUrl/contents/$contentId") {
                headers.append("Authorization", "Bearer $accessToken")
            }

        when (response.status) {
            HttpStatusCode.NoContent -> Result.success(Unit)
            else -> handleApiError(response)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to delete content")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to delete content",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestUploadDraft(
    accessToken: String,
    draft: DraftUploadRequest,
): Result<DraftUploadResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.put("$baseUrl/drafts/${draft.id}") {
                headers.append("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(draft)
            }
        when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created -> {
                Result.success(response.body<DraftUploadResponse>())
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to upload draft")
        Result.failure(CloudApiException("NETWORK_ERROR", "Failed to upload draft", cause = e))
    }

internal suspend fun LogDateCloudApiClient.requestGetDraftChanges(
    accessToken: String,
    since: Long,
    limit: Int?,
): Result<DraftChangesResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.get("$baseUrl/drafts/changes") {
                headers.append("Authorization", "Bearer $accessToken")
                parameter("since", since)
                limit?.let { parameter("limit", it) }
            }
        when (response.status) {
            HttpStatusCode.OK -> {
                Result.success(response.body<DraftChangesResponse>())
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to get draft changes")
        Result.failure(CloudApiException("NETWORK_ERROR", "Failed to get draft changes", cause = e))
    }

internal suspend fun LogDateCloudApiClient.requestDeleteDraft(
    accessToken: String,
    draftId: String,
): Result<Unit> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.delete("$baseUrl/drafts/$draftId") {
                headers.append("Authorization", "Bearer $accessToken")
            }
        when (response.status) {
            HttpStatusCode.NoContent -> Result.success(Unit)
            else -> handleApiError(response)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to delete draft")
        Result.failure(CloudApiException("NETWORK_ERROR", "Failed to delete draft", cause = e))
    }
