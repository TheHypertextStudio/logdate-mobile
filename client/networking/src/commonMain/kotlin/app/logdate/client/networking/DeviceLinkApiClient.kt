package app.logdate.client.networking

import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.devices.DeviceLinkCodeResponse
import app.logdate.shared.model.devices.DeviceLinkPlatform
import app.logdate.shared.model.devices.DeviceLinkRedeemRequest
import app.logdate.shared.model.devices.DeviceLinkRedeemResponse
import app.logdate.shared.model.devices.LinkedDevice
import app.logdate.shared.model.devices.LinkedDeviceListResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** A non-success answer from the device linking API. [code] is the server's error code when it sent one. */
class DeviceLinkApiException(
    val status: Int,
    val code: String? = null,
) : Exception("Device link request failed (HTTP $status${code?.let { ", $it" } ?: ""})")

/** `/auth/devices`: a phone links, lists and removes devices; a linked device redeems a code and can unlink itself. */
interface DeviceLinkApiClientContract {
    /** Mints a one-time link code with the phone's full-access [accessToken]. */
    suspend fun mintLinkCode(accessToken: String): Result<DeviceLinkCodeResponse>

    /** Exchanges [code] for this device's own sync-only session. Sends no `Authorization` header. */
    suspend fun redeem(
        code: String,
        deviceName: String,
        platform: String = DeviceLinkPlatform.WEAR_OS,
    ): Result<DeviceLinkRedeemResponse>

    /** The account's active linked devices; the caller's own entry has `current = true`. */
    suspend fun listDevices(accessToken: String): Result<List<LinkedDevice>>

    /** Removes linked device [deviceId] with the phone's full-access [accessToken]. */
    suspend fun revoke(
        deviceId: String,
        accessToken: String,
    ): Result<Unit>

    /** Ends the calling linked device's own session. */
    suspend fun unlinkSelf(accessToken: String): Result<Unit>
}

class DeviceLinkApiClient(
    private val httpClient: HttpClient,
    private val configRepository: LogDateConfigRepository,
) : DeviceLinkApiClientContract {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun mintLinkCode(accessToken: String): Result<DeviceLinkCodeResponse> =
        request {
            val response = httpClient.post("${devicesUrl()}/link") { header(HttpHeaders.Authorization, "Bearer $accessToken") }
            json.decodeFromString<DeviceLinkCodeResponse>(successBody(response))
        }

    override suspend fun redeem(
        code: String,
        deviceName: String,
        platform: String,
    ): Result<DeviceLinkRedeemResponse> =
        request {
            val body = DeviceLinkRedeemRequest(code, deviceName, platform)
            val response =
                httpClient.post("${devicesUrl()}/link/redeem") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(DeviceLinkRedeemRequest.serializer(), body))
                }
            json.decodeFromString<DeviceLinkRedeemResponse>(successBody(response))
        }

    override suspend fun listDevices(accessToken: String): Result<List<LinkedDevice>> =
        request {
            val response = httpClient.get(devicesUrl()) { header(HttpHeaders.Authorization, "Bearer $accessToken") }
            json.decodeFromString<LinkedDeviceListResponse>(successBody(response)).devices
        }

    override suspend fun revoke(
        deviceId: String,
        accessToken: String,
    ): Result<Unit> =
        request {
            successBody(httpClient.delete("${devicesUrl()}/$deviceId") { header(HttpHeaders.Authorization, "Bearer $accessToken") })
        }

    override suspend fun unlinkSelf(accessToken: String): Result<Unit> =
        request {
            successBody(httpClient.delete("${devicesUrl()}/self") { header(HttpHeaders.Authorization, "Bearer $accessToken") })
        }

    private suspend fun devicesUrl(): String = "${configRepository.apiBaseUrl.first()}/auth/devices"

    private suspend fun successBody(response: HttpResponse): String {
        val body = response.bodyAsText()
        if (response.status.value in 200..299) return body
        val code = runCatching { json.decodeFromString<ErrorEnvelope>(body).error?.code }.getOrNull()
        throw DeviceLinkApiException(response.status.value, code)
    }

    private suspend fun <T> request(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    @Serializable
    private data class ErrorEnvelope(
        val error: ErrorDetail? = null,
    )

    @Serializable
    private data class ErrorDetail(
        val code: String? = null,
    )
}
