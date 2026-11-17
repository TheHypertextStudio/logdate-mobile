package app.logdate.client.networking

import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceLinkApiClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val sessionId = "8b0f5a4e-2c1d-4f3a-9e8b-7c6d5e4f3a2b"

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        DeviceLinkApiClient(HttpClient(MockEngine(handler)), TestConfigRepository())

    @Test
    fun `the phone mints a code with its bearer token`() =
        runTest {
            val client =
                client { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/api/v1/auth/devices/link", request.url.encodedPath)
                    assertEquals("Bearer phone-access", request.headers[HttpHeaders.Authorization])
                    respond("""{"code":"one-time-code","expiresAt":1789221911412}""", HttpStatusCode.OK, jsonHeaders)
                }

            val code = client.mintLinkCode("phone-access").getOrThrow()

            assertEquals("one-time-code", code.code)
            assertEquals(1_789_221_911_412, code.expiresAt)
            assertFalse("one-time-code" in code.toString())
        }

    @Test
    fun `the watch redeems a code without an Authorization header`() =
        runTest {
            val client =
                client { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/api/v1/auth/devices/link/redeem", request.url.encodedPath)
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertEquals("""{"code":"one-time-code","deviceName":"Pixel Watch 3"}""", (request.body as TextContent).text)
                    val tokens = """{"accessToken":"watch-access","refreshToken":"watch-refresh"}"""
                    respond("""{"accountId":"account-1","sessionId":"$sessionId","tokens":$tokens}""", HttpStatusCode.OK, jsonHeaders)
                }

            val linked = client.redeem("one-time-code", "Pixel Watch 3").getOrThrow()

            assertEquals("account-1", linked.accountId)
            assertEquals(sessionId, linked.sessionId)
            assertEquals("watch-access", linked.tokens.accessToken)
            assertFalse("watch-access" in linked.toString())
        }

    @Test
    fun `a spent code reports the server error code`() =
        runTest {
            val client =
                client {
                    respond(
                        """{"error":{"code":"LINK_CODE_INVALID","message":"The link code is invalid, expired, or already used"}}""",
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                }

            val failure = assertIs<DeviceLinkApiException>(client.redeem("spent", "Pixel Watch 3").exceptionOrNull())

            assertEquals(400, failure.status)
            assertEquals(DeviceLinkErrorCodes.LINK_CODE_INVALID, failure.code)
            assertFalse(failure.isSessionRevoked)
        }

    @Test
    fun `the device list decodes and an absent current flag means another device`() =
        runTest {
            val client =
                client { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("/api/v1/auth/devices", request.url.encodedPath)
                    val pixel =
                        """{"id":"a","deviceName":"Pixel Watch 3","platform":"wear_os",""" +
                            """"createdAt":1,"lastRefreshedAt":2,"expiresAt":3}"""
                    val galaxy =
                        """{"id":"b","deviceName":"Galaxy Watch","platform":"wear_os",""" +
                            """"createdAt":4,"lastRefreshedAt":5,"expiresAt":6,"current":true}"""
                    respond("""{"devices":[$pixel,$galaxy]}""", HttpStatusCode.OK, jsonHeaders)
                }

            val devices = client.listDevices("watch-access").getOrThrow()

            assertEquals(listOf("Pixel Watch 3", "Galaxy Watch"), devices.map { it.deviceName })
            assertEquals(listOf(false, true), devices.map { it.current })
        }

    @Test
    fun `removing a device and unlinking this device send DELETE`() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                client { request ->
                    assertEquals(HttpMethod.Delete, request.method)
                    paths += request.url.encodedPath
                    respond("", HttpStatusCode.NoContent)
                }

            client.revoke(sessionId, "phone-access").getOrThrow()
            client.unlinkSelf("watch-access").getOrThrow()

            assertEquals(listOf("/api/v1/auth/devices/$sessionId", "/api/v1/auth/devices/self"), paths)
        }

    @Test
    fun `a removed device learns its session was revoked`() =
        runTest {
            val client =
                client {
                    respond(
                        """{"error":{"code":"DEVICE_SESSION_REVOKED","message":"This device was removed from the account"}}""",
                        HttpStatusCode.Unauthorized,
                        jsonHeaders,
                    )
                }

            val failure = assertIs<DeviceLinkApiException>(client.listDevices("watch-access").exceptionOrNull())

            assertEquals(401, failure.status)
            assertTrue(failure.isSessionRevoked)
        }

    @Test
    fun `an error without a JSON body still reports the status`() =
        runTest {
            val client = client { respond("upstream unavailable", HttpStatusCode.BadGateway) }

            val failure = assertIs<DeviceLinkApiException>(client.mintLinkCode("phone-access").exceptionOrNull())

            assertEquals(502, failure.status)
            assertNull(failure.code)
        }
}
