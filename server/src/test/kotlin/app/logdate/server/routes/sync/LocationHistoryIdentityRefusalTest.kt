package app.logdate.server.routes.sync

import app.logdate.server.auth.JwtTokenService
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.sync.LocationHistoryRepository
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LocationHistoryIdentityRefusalTest {
    @Test
    fun heldWriteAnswersAStableConflict() = check(IdentityFenceOutcome.HELD, HttpStatusCode.Conflict, "IDENTITY_WRITE_HELD")

    @Test
    fun staleWriteAnswersAStableConflict() = check(IdentityFenceOutcome.CHANGED, HttpStatusCode.Conflict, "IDENTITY_CHANGED")

    @Test
    fun deletedOwnerAnswersUnauthorized() = check(IdentityFenceOutcome.DELETED, HttpStatusCode.Unauthorized, "ACCOUNT_UNAVAILABLE")

    private fun check(
        outcome: IdentityFenceOutcome,
        status: HttpStatusCode,
        code: String,
    ) = testApplication {
        val id = Uuid.random()
        val tokens = JwtTokenService("location-refusal-test-key")
        val repository = mockk<LocationHistoryRepository>()
        every { repository.upload(any(), any()) } throws IdentityWriteRefusedException(outcome)
        application {
            install(ContentNegotiation) { json(Json) }
            routing { locationHistoryRoutes(tokens, repository) }
        }
        val result =
            runCatching {
                client.put("/location-history") {
                    header(HttpHeaders.Authorization, "Bearer ${tokens.generateAccessToken(id.toString())}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"records":[{
                          "id":"new","recordType":"observation","payload":"LDSE2:synthetic",
                          "deviceId":"d","deviceVersion":1
                        }]}
                        """.trimIndent(),
                    )
                }
            }
        verify(exactly = 1) { repository.upload(any(), any()) }
        val response = assertNotNull(result.getOrNull(), "The route must answer an identity refusal")
        assertEquals(status, response.status)
        val body = response.bodyAsText()
        assertEquals(
            code,
            Json
                .parseToJsonElement(body)
                .jsonObject["code"]
                ?.jsonPrimitive
                ?.content,
        )
        assertFalse(body.contains(id.toString()))
        assertFalse(body.contains("Identity write refused"))
    }
}
