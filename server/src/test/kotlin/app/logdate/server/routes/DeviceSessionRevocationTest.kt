package app.logdate.server.routes

import app.logdate.server.auth.JwtTokenService
import app.logdate.server.oauth.MutableClock
import app.logdate.server.routes.support.assertApiErrorCode
import app.logdate.server.routes.support.assertSyncErrorCode
import app.logdate.server.routes.support.configureDeviceLinkTestApp
import app.logdate.server.routes.support.contentUploadBody
import app.logdate.server.routes.support.jsonBody
import app.logdate.server.routes.support.refreshTokens
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class DeviceSessionRevocationTest {
    private val tokenService = JwtTokenService("device-session-revocation-secret")

    @Test
    fun `removing a device ends its sync access and refresh and account read`() =
        testApplication {
            val kit = configureDeviceLinkTestApp(tokenService)
            val owner = kit.newAccount("revoke_owner")
            val watch = kit.linkWatch(owner)
            val watchBearer = "Bearer ${watch.tokens.accessToken}"
            val phoneBearer = "Bearer ${tokenService.generateAccessToken(owner.toString())}"
            assertEquals(HttpStatusCode.Created, client.putNote(watchBearer, "watch-before-removal").status)
            assertEquals(HttpStatusCode.OK, client.refreshTokens(watch.tokens.refreshToken).status)

            val removed = client.delete("/api/v1/auth/devices/${watch.sessionId}") { header(HttpHeaders.Authorization, phoneBearer) }
            assertEquals(HttpStatusCode.NoContent, removed.status)

            assertSyncErrorCode(
                client.putNote(watchBearer, "watch-after-removal"),
                HttpStatusCode.Unauthorized,
                DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED,
            )
            assertApiErrorCode(
                client.refreshTokens(watch.tokens.refreshToken),
                HttpStatusCode.Unauthorized,
                DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED,
            )
            assertApiErrorCode(
                client.get("/api/v1/auth/me") { header(HttpHeaders.Authorization, watchBearer) },
                HttpStatusCode.Unauthorized,
                DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED,
            )
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/me") { header(HttpHeaders.Authorization, phoneBearer) }.status)
            val phoneRead = client.get("/api/v1/contents/watch-before-removal") { header(HttpHeaders.Authorization, phoneBearer) }
            assertEquals(HttpStatusCode.OK, phoneRead.status)
        }

    @Test
    fun `refreshing renews a linked session only in its last fifteen days`() =
        testApplication {
            val clock = MutableClock(Instant.parse("2026-10-03T12:00:00Z"))
            val kit = configureDeviceLinkTestApp(tokenService, clock)
            val watch = kit.linkWatch(kit.newAccount("renew_owner"))

            val early = client.refreshTokens(watch.tokens.refreshToken)
            assertEquals(HttpStatusCode.OK, early.status)
            assertNull(early.jsonBody().getValue("data").jsonObject["refreshToken"])

            clock.nowValue += 16.days
            val renewed =
                client
                    .refreshTokens(watch.tokens.refreshToken)
                    .jsonBody()
                    .getValue("data")
                    .jsonObject
            val newRefresh = renewed.getValue("refreshToken").jsonPrimitive.content
            assertEquals(watch.sessionId, tokenService.decodeRefreshToken(newRefresh)?.sessionId)
            assertEquals(watch.sessionId, tokenService.decodeAccessToken(renewed.getValue("accessToken").jsonPrimitive.content)?.sessionId)
            assertEquals(HttpStatusCode.OK, client.refreshTokens(newRefresh).status)
        }

    @Test
    fun `a session that ran out unused answers expired rather than removed`() =
        testApplication {
            val clock = MutableClock(Instant.parse("2026-10-03T12:00:00Z"))
            val kit = configureDeviceLinkTestApp(tokenService, clock)
            val watch = kit.linkWatch(kit.newAccount("expire_owner"))

            clock.nowValue += 31.days
            assertApiErrorCode(
                client.refreshTokens(watch.tokens.refreshToken),
                HttpStatusCode.Unauthorized,
                DeviceLinkErrorCodes.DEVICE_SESSION_EXPIRED,
            )
        }

    private suspend fun HttpClient.putNote(
        bearer: String,
        id: String,
    ): HttpResponse =
        put("/api/v1/contents/$id") {
            header(HttpHeaders.Authorization, bearer)
            contentType(ContentType.Application.Json)
            setBody(contentUploadBody(id = id))
        }
}
