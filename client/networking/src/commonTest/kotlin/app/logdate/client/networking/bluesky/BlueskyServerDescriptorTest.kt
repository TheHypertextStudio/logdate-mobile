package app.logdate.client.networking.bluesky

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class BlueskyServerDescriptorTest {
    @Test
    fun `descriptor reads use the original credential free pipeline`() =
        runTest {
            var requests = 0
            val parent =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        assertEquals("https://cloud.logdate.app/api/v1/server/info", request.url.toString())
                        assertEquals("GET", request.method.value)
                        assertEquals(null, request.headers[HttpHeaders.Authorization])
                        assertEquals(null, request.headers[HttpHeaders.Cookie])
                        respond(INFO, headers = JSON)
                    },
                ) {
                    defaultRequest {
                        header(HttpHeaders.Authorization, "Bearer private-marker")
                        header(HttpHeaders.Cookie, "private-marker")
                    }
                }
            val api = BlueskyApiClient(parent, "https://cloud.logdate.app")
            try {
                assertEquals("https://cloud.logdate.app", api.serverDescriptor().getOrThrow().serverOrigin)
                assertEquals(1, requests)
            } finally {
                api.close()
                parent.close()
            }
        }

    @Test
    fun `false foreign oversized and redirect metadata are refused without transport details`() =
        runTest {
            for ((status, body) in listOf(
                HttpStatusCode.OK to INFO.replace("\"success\":true", "\"success\":false"),
                HttpStatusCode.OK to INFO.replace("cloud.logdate.app", "other.logdate.app"),
                HttpStatusCode.OK to (INFO.dropLast(1) + ",\"padding\":\"${"x".repeat(1024 * 1024)}\"}"),
                HttpStatusCode.Found to "private-marker",
            )) {
                var requests = 0
                val parent =
                    HttpClient(
                        MockEngine {
                            requests++
                            respond(
                                body,
                                status,
                                headersOf(
                                    HttpHeaders.Location to listOf("https://other.logdate.app"),
                                    HttpHeaders.ContentType to listOf("application/json"),
                                ),
                            )
                        },
                    )
                val api = BlueskyApiClient(parent, "https://cloud.logdate.app")
                try {
                    val error = assertIs<BlueskyApiException>(api.serverDescriptor().exceptionOrNull())
                    assertFalse(error.toString().contains("private-marker"))
                    assertEquals(1, requests)
                } finally {
                    api.close()
                    parent.close()
                }
            }
        }

    private companion object {
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
        val INFO =
            """
            {"success":true,"data":{
              "serverOrigin":"https://cloud.logdate.app",
              "apiBaseUrl":"https://cloud.logdate.app/api/v1",
              "deploymentKind":"FIRST_PARTY","displayName":"LogDate"}}
            """.trimIndent()
    }
}
