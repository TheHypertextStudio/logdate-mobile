package app.logdate.server.routes

import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.InMemoryTokenService
import app.logdate.server.enrollment.InMemoryDeviceEnrollmentRepository
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceEnrollmentRoutesTest {
    @Test
    fun `approved transfer can be read again before acknowledgement`() =
        testApplication {
            val tokens = InMemoryTokenService()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/v1") { deviceEnrollmentRoutes(tokens, InMemoryDeviceEnrollmentRepository(), InMemoryAccountRepository()) }
                }
            }
            val accountToken = "Bearer ${tokens.generateAccessToken(UUID.randomUUID().toString())}"
            val otherToken = "Bearer ${tokens.generateAccessToken(UUID.randomUUID().toString())}"
            val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
            val created =
                client.post("/api/v1/device-enrollments") {
                    header(HttpHeaders.Authorization, accountToken)
                    contentType(ContentType.Application.Json)
                    setBody("""{"deviceName":"Mac","publicKey":"$publicKey"}""")
                }
            assertEquals(HttpStatusCode.Created, created.status)
            val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val id = body.getValue("id").jsonPrimitive.content
            val code = body.getValue("confirmationCode").jsonPrimitive.content
            val transferPath = "/api/v1/device-enrollments/$id/transfer"

            suspend fun transfer(token: String) = client.get(transferPath) { header(HttpHeaders.Authorization, token) }

            assertEquals(HttpStatusCode.Conflict, transfer(accountToken).status)
            assertEquals(HttpStatusCode.NotFound, transfer(otherToken).status)
            val approved =
                client.post("/api/v1/device-enrollments/$id/approve") {
                    header(HttpHeaders.Authorization, accountToken)
                    contentType(ContentType.Application.Json)
                    setBody("""{"confirmationCode":"$code","encryptedEnvelope":"opaque"}""")
                }
            assertEquals(HttpStatusCode.NoContent, approved.status)
            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .post("/api/v1/device-enrollments/$id/approve") {
                        header(HttpHeaders.Authorization, accountToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"confirmationCode":"$code","encryptedEnvelope":"opaque"}""")
                    }.status,
            )
            repeat(2) {
                val response = transfer(accountToken)
                assertEquals(HttpStatusCode.OK, response.status)
                val envelope =
                    Json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject
                        .getValue("encryptedEnvelope")
                        .jsonPrimitive.content
                assertEquals("opaque", envelope)
            }
            val acknowledged =
                client.post("/api/v1/device-enrollments/$id/consume") {
                    header(HttpHeaders.Authorization, accountToken)
                }
            assertEquals(HttpStatusCode.OK, acknowledged.status)
            assertEquals(HttpStatusCode.NotFound, transfer(accountToken).status)
        }

    @Test
    fun `phone can create a claimable Mac connection and Mac consumes it once`() =
        testApplication {
            val tokens = InMemoryTokenService()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/v1") { deviceEnrollmentRoutes(tokens, InMemoryDeviceEnrollmentRepository(), InMemoryAccountRepository()) }
                }
            }
            val account = UUID.randomUUID()
            val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 40).toByte() })
            val claimPath = "/api/v1/device-enrollments/claim"

            suspend fun claim() = client.post(claimPath) { header("X-LogDate-Claim-Secret", secret) }
            assertEquals(HttpStatusCode.NotFound, claim().status)

            val created =
                client.post("/api/v1/device-enrollments") {
                    header(HttpHeaders.Authorization, "Bearer ${tokens.generateAccessToken(account.toString())}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"deviceName":"My Mac","publicKey":"$publicKey","claimSecret":"$secret","confirmationCode":"123456"}""")
                }
            assertEquals(HttpStatusCode.Created, created.status)
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            val retried =
                client.post("/api/v1/device-enrollments") {
                    header(HttpHeaders.Authorization, "Bearer ${tokens.generateAccessToken(account.toString())}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"deviceName":"My Mac","publicKey":"$publicKey","claimSecret":"$secret","confirmationCode":"123456"}""")
                }
            assertEquals(HttpStatusCode.Created, retried.status)
            assertEquals(
                id,
                Json
                    .parseToJsonElement(retried.bodyAsText())
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            val differentRequest =
                client.post("/api/v1/device-enrollments") {
                    header(HttpHeaders.Authorization, "Bearer ${tokens.generateAccessToken(account.toString())}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"deviceName":"Another Mac","publicKey":"$publicKey","claimSecret":"$secret","confirmationCode":"123456"}""")
                }
            assertEquals(HttpStatusCode.Conflict, differentRequest.status)
            assertEquals(
                "pending",
                Json
                    .parseToJsonElement(claim().bodyAsText())
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertEquals(HttpStatusCode.NotFound, client.post(claimPath) { header("X-LogDate-Claim-Secret", publicKey) }.status)

            val approved =
                client.post("/api/v1/device-enrollments/$id/approve") {
                    header(HttpHeaders.Authorization, "Bearer ${tokens.generateAccessToken(account.toString())}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"confirmationCode":"123456","encryptedEnvelope":"sealed-session-and-key"}""")
                }
            assertEquals(HttpStatusCode.NoContent, approved.status)
            val firstClaim = claim()
            assertEquals(HttpStatusCode.OK, firstClaim.status)
            val payload = Json.parseToJsonElement(firstClaim.bodyAsText()).jsonObject
            assertEquals(account.toString(), payload.getValue("accountId").jsonPrimitive.content)
            assertEquals("sealed-session-and-key", payload.getValue("encryptedEnvelope").jsonPrimitive.content)
            assertEquals(payload, Json.parseToJsonElement(claim().bodyAsText()).jsonObject)
            val consumed =
                client.post("/api/v1/device-enrollments/$id/consume") {
                    header(HttpHeaders.Authorization, "Bearer ${tokens.generateAccessToken(account.toString())}")
                }
            assertEquals(HttpStatusCode.OK, consumed.status)
            val consumedEnvelope =
                Json
                    .parseToJsonElement(consumed.bodyAsText())
                    .jsonObject
                    .getValue("encryptedEnvelope")
                    .jsonPrimitive.content
            assertEquals("sealed-session-and-key", consumedEnvelope)
            assertEquals(HttpStatusCode.NotFound, claim().status)
        }

    @Test
    fun `account bound transfer is opaque and one use`() =
        testApplication {
            val tokens = InMemoryTokenService()
            val repository = InMemoryDeviceEnrollmentRepository()
            application {
                install(ContentNegotiation) { json() }
                routing { route("/api/v1") { deviceEnrollmentRoutes(tokens, repository, InMemoryAccountRepository()) } }
            }
            val account = UUID.randomUUID()
            val other = UUID.randomUUID()
            val accountToken = "Bearer ${tokens.generateAccessToken(account.toString())}"
            val otherToken = "Bearer ${tokens.generateAccessToken(other.toString())}"
            val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
            val createBody = """{"deviceName":"Alice's Mac","publicKey":"$publicKey"}"""

            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .post("/api/v1/device-enrollments") {
                        contentType(ContentType.Application.Json)
                        setBody(createBody)
                    }.status,
            )
            val created =
                client.post("/api/v1/device-enrollments") {
                    header(HttpHeaders.Authorization, accountToken)
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJSON = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val id = createdJSON.getValue("id").jsonPrimitive.content
            val code = createdJSON.getValue("confirmationCode").jsonPrimitive.content
            assertTrue(code.matches(Regex("[0-9]{6}")))
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .get("/api/v1/device-enrollments/$id") {
                        header(HttpHeaders.Authorization, otherToken)
                    }.status,
            )
            assertEquals(
                HttpStatusCode.Conflict,
                client
                    .post("/api/v1/device-enrollments/$id/approve") {
                        header(HttpHeaders.Authorization, otherToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"confirmationCode":"$code","encryptedEnvelope":"opaque"}""")
                    }.status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .post("/api/v1/device-enrollments/$id/approve") {
                        header(HttpHeaders.Authorization, accountToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"confirmationCode":"$code","encryptedEnvelope":"opaque"}""")
                    }.status,
            )
            val consumed =
                client.post("/api/v1/device-enrollments/$id/consume") {
                    header(HttpHeaders.Authorization, accountToken)
                }
            assertEquals(HttpStatusCode.OK, consumed.status)
            assertEquals(
                "opaque",
                Json
                    .parseToJsonElement(consumed.bodyAsText())
                    .jsonObject
                    .getValue("encryptedEnvelope")
                    .jsonPrimitive.content,
            )
            assertEquals(
                HttpStatusCode.Conflict,
                client
                    .post("/api/v1/device-enrollments/$id/consume") {
                        header(HttpHeaders.Authorization, accountToken)
                    }.status,
            )
        }
}
