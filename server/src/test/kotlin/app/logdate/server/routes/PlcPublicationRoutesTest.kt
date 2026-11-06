package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.PlcPublicationConsent
import app.logdate.server.identity.PlcPublicationHistory
import app.logdate.server.identity.PlcPublicationPrompt
import app.logdate.server.identity.PlcPublicationStatus
import app.logdate.server.identity.PlcUsernameState
import app.logdate.shared.model.PasskeyAuthenticationOptions
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PlcPublicationRoutesTest {
    @Test
    fun `account routes protect the review and return pending when consent is accepted`() =
        testApplication {
            val accountId = Uuid.random()
            val reviewId = Uuid.random()
            val username = "alex.logdate.app"
            val account = Account(id = accountId, username = "alex", displayName = "Alex", createdAt = Clock.System.now())
            val accounts = mockk<AccountRepository>()
            val tokens = mockk<TokenService>()
            val consent = mockk<PlcPublicationConsent>()
            every { tokens.validateAccessToken("account-token") } returns accountId.toString()
            coEvery { accounts.findById(accountId) } returns account
            coEvery { consent.status(accountId) } returns PlcPublicationStatus(username, PlcUsernameState.Private)
            coEvery { consent.begin(accountId) } returns
                PlcPublicationPrompt(
                    reviewId,
                    username,
                    PlcPublicationHistory(updates = 1, signingChanges = 0, recoveryChanges = 1),
                    PasskeyAuthenticationOptions("challenge", "logdate.app"),
                )
            coEvery { consent.authorize(accountId, reviewId, "challenge", any()) } returns true
            application {
                install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
                routing { plcPublicationRoutes(accounts, tokens, consent) }
            }

            assertEquals(HttpStatusCode.Unauthorized, client.get(PATH).status)
            val status = client.get(PATH) { header(HttpHeaders.Authorization, "Bearer account-token") }
            assertEquals(HttpStatusCode.OK, status.status)
            assertEquals("no-store", status.headers[HttpHeaders.CacheControl])
            val review = client.post("$PATH/review") { header(HttpHeaders.Authorization, "Bearer account-token") }
            assertEquals(HttpStatusCode.OK, review.status)
            val reviewBody = Json.parseToJsonElement(review.bodyAsText()).jsonObject
            assertEquals(username, reviewBody.getValue("username").jsonPrimitive.content)
            assertEquals(
                "1",
                reviewBody
                    .getValue("history")
                    .jsonObject
                    .getValue("recoveryChanges")
                    .jsonPrimitive.content,
            )

            val submitted =
                client.post("$PATH/confirm") {
                    header(HttpHeaders.Authorization, "Bearer account-token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"reviewId":"$reviewId","challenge":"challenge","credential":{
                          "id":"credential","rawId":"credential","response":{
                            "clientDataJSON":"data","authenticatorData":"data","signature":"signature","userHandle":null
                          }
                        }}
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.Accepted, submitted.status, submitted.bodyAsText())
            coVerify(exactly = 1) { consent.authorize(accountId, reviewId, "challenge", any()) }
        }

    private companion object {
        const val PATH = "/identity/publication"
    }
}
