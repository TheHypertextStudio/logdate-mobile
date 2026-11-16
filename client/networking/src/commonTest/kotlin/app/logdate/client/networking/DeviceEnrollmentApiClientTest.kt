package app.logdate.client.networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceEnrollmentApiClientTest {
    private val id = "4d90d8dc-49b2-4ba1-8c20-529b80c77542"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        DeviceEnrollmentApiClient(HttpClient(MockEngine(handler)), TestConfigRepository())

    @Test
    fun `phone reads account bound request and submits only an opaque envelope`() =
        runTest {
            val client =
                client { request ->
                    assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
                    if (request.method.value == "GET") {
                        assertEquals("/api/v1/device-enrollments/$id", request.url.encodedPath)
                        respond(
                            """{"id":"$id","deviceName":"Alice's Mac","publicKey":"public-key","confirmationCode":"413827","expiresAt":9999999999999,"status":"pending"}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    } else {
                        assertEquals("/api/v1/device-enrollments/$id/approve", request.url.encodedPath)
                        respond("", HttpStatusCode.OK)
                    }
                }

            val request = client.get(id, "access")
            assertEquals("Alice's Mac", request.deviceName)
            assertEquals("413827", request.confirmationCode)
            client.approve(id, "413827", "opaque-envelope", "access")
        }

    @Test
    fun `device session is minted with the approving account's bearer`() =
        runTest {
            val client =
                client { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/api/v1/device-enrollments/$id/session", request.url.encodedPath)
                    assertEquals("Bearer phone-access", request.headers[HttpHeaders.Authorization])
                    respond("""{"accessToken":"new-access","refreshToken":"new-refresh"}""", HttpStatusCode.OK, jsonHeaders)
                }

            val tokens = client.createDeviceSession(id, "phone-access").getOrThrow()

            assertEquals(DeviceSessionTokens("new-access", "new-refresh"), tokens)
            assertFalse("new-access" in tokens.toString())
            assertFalse("new-refresh" in tokens.toString())
        }

    @Test
    fun `missing or expired request fails the device session with 404`() =
        runTest {
            val client =
                client {
                    respond("""{"code":"NOT_FOUND","message":"Enrollment unavailable"}""", HttpStatusCode.NotFound, jsonHeaders)
                }

            val failure = client.createDeviceSession(id, "phone-access").exceptionOrNull()

            assertIs<DeviceEnrollmentApiException>(failure)
            assertEquals(404, failure.status)
            assertEquals("NOT_FOUND", failure.code)
        }

    @Test
    fun `second device session for the same request reports the issued code`() =
        runTest {
            val client =
                client {
                    respond(
                        """{"code":"ENROLLMENT_SESSION_ISSUED","message":"Session already issued"}""",
                        HttpStatusCode.Conflict,
                        jsonHeaders,
                    )
                }

            val result = client.createDeviceSession(id, "phone-access")

            assertTrue(result.isFailure)
            val failure = assertIs<DeviceEnrollmentApiException>(result.exceptionOrNull())
            assertEquals(409, failure.status)
            assertEquals(DeviceEnrollmentApiException.SESSION_ALREADY_ISSUED, failure.code)
        }

    @Test
    fun `error without a JSON body still reports the status`() =
        runTest {
            val client = client { respond("upstream unavailable", HttpStatusCode.BadGateway) }

            val failure = client.createDeviceSession(id, "phone-access").exceptionOrNull()

            assertIs<DeviceEnrollmentApiException>(failure)
            assertEquals(502, failure.status)
            assertEquals(null, failure.code)
        }
}
