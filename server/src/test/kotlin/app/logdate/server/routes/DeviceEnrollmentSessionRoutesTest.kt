package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.InMemoryTokenService
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.database.toJavaUUID
import app.logdate.server.enrollment.DeviceEnrollment
import app.logdate.server.enrollment.DeviceEnrollmentRepository
import app.logdate.server.enrollment.InMemoryDeviceEnrollmentRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertNotEquals
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class DeviceEnrollmentSessionRoutesTest {
    private val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun `only the owning account can retry the same session for a pending request`() =
        testApplication {
            val tokens = InMemoryTokenService()
            val accounts = InMemoryAccountRepository()
            application {
                install(ContentNegotiation) { json() }
                routing { route("/api/v1") { deviceEnrollmentRoutes(tokens, InMemoryDeviceEnrollmentRepository(), accounts) } }
            }
            val owner = "Bearer ${tokens.generateAccessToken(accounts.newAccount("owner").toString())}"
            val other = "Bearer ${tokens.generateAccessToken(accounts.newAccount("other").toString())}"
            val id = client.createEnrollment(owner).first

            assertEquals(HttpStatusCode.Unauthorized, client.post(sessionPath(id)).status)
            assertError(client.issueSession(owner, "not-a-uuid"), HttpStatusCode.BadRequest, "INVALID_ENROLLMENT")
            assertError(client.issueSession(owner, UUID.randomUUID().toString()), HttpStatusCode.NotFound, "NOT_FOUND")
            assertError(client.issueSession(other, id), HttpStatusCode.NotFound, "NOT_FOUND")

            val issued = client.issueSession(owner, id)
            assertEquals(HttpStatusCode.OK, issued.status)
            assertEquals("no-store", issued.headers[HttpHeaders.CacheControl])
            val session = Json.parseToJsonElement(issued.bodyAsText()).jsonObject
            assertEquals(setOf("accessToken", "refreshToken"), session.keys)
            val accessToken = session.getValue("accessToken").jsonPrimitive.content
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/device-enrollments/$id") { header(HttpHeaders.Authorization, "Bearer $accessToken") }.status,
            )

            val retry = client.issueSession(owner, id)
            assertEquals(HttpStatusCode.OK, retry.status)
            assertEquals(session, Json.parseToJsonElement(retry.bodyAsText()).jsonObject)
            assertError(client.issueSession(other, id), HttpStatusCode.NotFound, "NOT_FOUND")
        }

    @Test
    fun `a session is issued only while the request is pending and unexpired`() =
        testApplication {
            val tokens = InMemoryTokenService()
            val accounts = InMemoryAccountRepository()
            val repository = InMemoryDeviceEnrollmentRepository()
            application {
                install(ContentNegotiation) { json() }
                routing { route("/api/v1") { deviceEnrollmentRoutes(tokens, repository, accounts) } }
            }
            val accountId = accounts.newAccount("pending")
            val owner = "Bearer ${tokens.generateAccessToken(accountId.toString())}"

            val (approvedId, code) = client.createEnrollment(owner)
            client.approve(owner, approvedId, code)
            assertError(client.issueSession(owner, approvedId), HttpStatusCode.Conflict, "ENROLLMENT_UNAVAILABLE")

            val rejectedId = client.createEnrollment(owner).first
            assertEquals(
                HttpStatusCode.NoContent,
                client.post("/api/v1/device-enrollments/$rejectedId/reject") { header(HttpHeaders.Authorization, owner) }.status,
            )
            assertError(client.issueSession(owner, rejectedId), HttpStatusCode.Conflict, "ENROLLMENT_UNAVAILABLE")

            val expired = repository.createExpired(accountId.toJavaUUID())
            assertError(client.issueSession(owner, expired.toString()), HttpStatusCode.NotFound, "NOT_FOUND")

            val (issuedThenApproved, issuedCode) = client.createEnrollment(owner)
            assertEquals(HttpStatusCode.OK, client.issueSession(owner, issuedThenApproved).status)
            client.approve(owner, issuedThenApproved, issuedCode)
            assertError(client.issueSession(owner, issuedThenApproved), HttpStatusCode.Conflict, "ENROLLMENT_UNAVAILABLE")
        }

    @Test
    fun `the new device session refreshes and logs out independently of the approving device`() =
        testApplication {
            val env = configureAuthV1TestApp()
            application {
                routing {
                    route("/api/v1") {
                        deviceEnrollmentRoutes(env.tokenService, InMemoryDeviceEnrollmentRepository(), env.accountRepository)
                    }
                }
            }
            val accountId = env.accountRepository.newAccount("phone_first").toString()
            val phoneAccess = "Bearer ${env.tokenService.generateAccessToken(accountId)}"
            val phoneRefresh = env.tokenService.generateRefreshToken(accountId)
            val id = client.createEnrollment(phoneAccess).first

            val issued = Json.parseToJsonElement(client.issueSession(phoneAccess, id).bodyAsText()).jsonObject
            val macAccess = issued.getValue("accessToken").jsonPrimitive.content
            val macRefresh = issued.getValue("refreshToken").jsonPrimitive.content
            assertNotEquals(phoneRefresh, macRefresh)
            assertEquals(accountId, env.tokenService.validateAccessToken(macAccess))

            assertEquals(HttpStatusCode.OK, client.refresh(macRefresh).status)
            assertEquals(HttpStatusCode.OK, client.logout(phoneRefresh).status)
            assertError(client.refresh(phoneRefresh), HttpStatusCode.Unauthorized, "REFRESH_TOKEN_REVOKED", apiEnvelope = true)
            val refreshed = client.refresh(macRefresh)
            assertEquals(HttpStatusCode.OK, refreshed.status)
            val refreshedAccess =
                Json
                    .parseToJsonElement(refreshed.bodyAsText())
                    .jsonObject
                    .getValue("data")
                    .jsonObject
                    .getValue("accessToken")
                    .jsonPrimitive.content
            assertEquals(accountId, env.tokenService.validateAccessToken(refreshedAccess))

            val secondPhoneRefresh = env.tokenService.generateRefreshToken(accountId)
            assertEquals(HttpStatusCode.OK, client.logout(macRefresh).status)
            assertEquals(HttpStatusCode.OK, client.refresh(secondPhoneRefresh).status)
        }

    private suspend fun AccountRepository.newAccount(username: String): Uuid {
        val id = Uuid.random()
        create(Account(id = id, username = username, displayName = username, createdAt = Clock.System.now()))
        return id
    }

    private suspend fun DeviceEnrollmentRepository.createExpired(accountId: UUID): UUID {
        val id = UUID.randomUUID()
        create(DeviceEnrollment(id, accountId, "Mac", publicKey, "123456", expiresAt = System.currentTimeMillis() - 1))
        return id
    }

    private suspend fun HttpClient.createEnrollment(token: String): Pair<String, String> {
        val response =
            post("/api/v1/device-enrollments") {
                header(HttpHeaders.Authorization, token)
                contentType(ContentType.Application.Json)
                setBody("""{"deviceName":"Alice's Mac","publicKey":"$publicKey"}""")
            }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body.getValue("id").jsonPrimitive.content to body.getValue("confirmationCode").jsonPrimitive.content
    }

    private suspend fun HttpClient.approve(
        token: String,
        id: String,
        code: String,
    ) {
        val response =
            post("/api/v1/device-enrollments/$id/approve") {
                header(HttpHeaders.Authorization, token)
                contentType(ContentType.Application.Json)
                setBody("""{"confirmationCode":"$code","encryptedEnvelope":"opaque"}""")
            }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    private suspend fun HttpClient.issueSession(
        token: String,
        id: String,
    ) = post(sessionPath(id)) { header(HttpHeaders.Authorization, token) }

    private suspend fun HttpClient.refresh(refreshToken: String) =
        post("/api/v1/auth/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }

    private suspend fun HttpClient.logout(refreshToken: String) =
        post("/api/v1/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }

    private fun sessionPath(id: String) = "/api/v1/device-enrollments/$id/session"

    private suspend fun assertError(
        response: HttpResponse,
        status: HttpStatusCode,
        code: String,
        apiEnvelope: Boolean = false,
    ) {
        assertEquals(status, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val envelope = if (apiEnvelope) body.getValue("error").jsonObject else body
        assertEquals(code, envelope.getValue("code").jsonPrimitive.content)
    }
}
