@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.integration.e2e.journeys

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.networking.DeviceLinkApiClient
import app.logdate.client.networking.DeviceLinkApiException
import app.logdate.client.sync.peer.devices.AccountLinkedDevicesRepository
import app.logdate.client.sync.peer.devices.LinkedDeviceClients
import app.logdate.client.sync.peer.devices.LinkedDeviceRemoval
import app.logdate.client.sync.peer.devices.LinkedDevicesLoad
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.harness.ServerClientE2EHarness
import app.logdate.integration.e2e.harness.withServerClientHarness
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import app.logdate.shared.model.devices.DeviceLinkPlatform
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CAPTURED_BEFORE_LINK = "watch-capture-before-link"
private const val CAPTURED_AFTER_REMOVAL = "watch-capture-after-removal"

/** How far the server's clock may trail this test's when it stamps a new link. */
private const val CLOCK_SKEW_MS = 5_000L

class WatchDeviceLinkE2ETest {
    @Test
    fun `a watch linked by code uploads its captures and loses access once removed`() =
        runTest {
            withServerClientHarness {
                val config = DefaultLogDateConfigRepository(initialBackendUrl = baseUrl.removeSuffix("/api/v1"))
                val links = DeviceLinkApiClient(httpClient, config)
                val phone = apiClient.createAccountWithSyntheticPasskey("watch_owner_${Random.nextInt(100000, 999999)}").data
                val phoneToken = phone.tokens.accessToken

                val code = links.mintLinkCode(phoneToken).getOrThrow()
                val watch = links.redeem(code.code, "Pixel Watch 3").getOrThrow()
                assertEquals(phone.account.id.toString(), watch.accountId)
                val reused = assertIs<DeviceLinkApiException>(links.redeem(code.code, "Second watch").exceptionOrNull())
                assertEquals(400, reused.status)
                assertEquals(DeviceLinkErrorCodes.LINK_CODE_INVALID, reused.code)

                assertEquals(HttpStatusCode.Created, putCapture(watch.tokens.accessToken, CAPTURED_BEFORE_LINK).status)
                assertEquals(HttpStatusCode.OK, getCapture(phoneToken, CAPTURED_BEFORE_LINK).status)
                assertEquals(listOf("Pixel Watch 3"), links.listDevices(phoneToken).getOrThrow().map { it.deviceName })
                val watchView = links.listDevices(watch.tokens.accessToken).getOrThrow()
                assertTrue(watchView.single().current)
                val minting = assertIs<DeviceLinkApiException>(links.mintLinkCode(watch.tokens.accessToken).exceptionOrNull())
                assertEquals(403, minting.status)

                links.revoke(watch.sessionId, phoneToken).getOrThrow()

                val refused = putCapture(watch.tokens.accessToken, CAPTURED_AFTER_REMOVAL)
                assertEquals(HttpStatusCode.Unauthorized, refused.status)
                assertEquals(DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED, refused.json().string("code"))
                val refresh =
                    httpClient.post("$baseUrl/auth/token/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"refreshToken":"${watch.tokens.refreshToken}"}""")
                    }
                assertEquals(HttpStatusCode.Unauthorized, refresh.status)
                val refreshError = refresh.json().getValue("error").jsonObject
                assertEquals(DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED, refreshError.string("code"))
                val unlink = assertIs<DeviceLinkApiException>(links.unlinkSelf(watch.tokens.accessToken).exceptionOrNull())
                assertTrue(unlink.isSessionRevoked)

                assertEquals(HttpStatusCode.OK, getCapture(phoneToken, CAPTURED_BEFORE_LINK).status)
                assertTrue(links.listDevices(phoneToken).getOrThrow().isEmpty())
            }
        }

    @Test
    fun `the phone lists a linked watch with its times until the watch is removed`() =
        runTest {
            withServerClientHarness {
                val origin = baseUrl.removeSuffix("/api/v1")
                val links = DeviceLinkApiClient(httpClient, DefaultLogDateConfigRepository(initialBackendUrl = origin))
                val phone = apiClient.createAccountWithSyntheticPasskey("linked_owner_${Random.nextInt(100000, 999999)}").data
                val sessions =
                    PhoneSessions(
                        OriginBoundSession(
                            origin,
                            UserSession(phone.tokens.accessToken, phone.tokens.refreshToken, phone.account.id.toString()),
                        ),
                    )
                val repository =
                    AccountLinkedDevicesRepository(
                        sessions = sessions,
                        linkingAvailable = { true },
                        clientsFor = { LinkedDeviceClients(links, refresher = null) },
                    )
                assertEquals(LinkedDevicesLoad.Loaded(emptyList()), repository.load())

                val before = System.currentTimeMillis()
                val watch = links.redeem(links.mintLinkCode(phone.tokens.accessToken).getOrThrow().code, "Pixel Watch 3").getOrThrow()

                val linked = assertIs<LinkedDevicesLoad.Loaded>(repository.load()).devices.single()
                assertEquals(watch.sessionId, linked.id)
                assertEquals("Pixel Watch 3", linked.name)
                assertEquals(DeviceLinkPlatform.WEAR_OS, linked.platform)
                assertTrue(linked.linkedAt.toEpochMilliseconds() >= before - CLOCK_SKEW_MS)
                assertTrue(linked.lastSeenAt >= linked.linkedAt)

                assertEquals(LinkedDeviceRemoval.Removed, repository.remove(linked))
                assertEquals(LinkedDevicesLoad.Loaded(emptyList()), repository.load())
                assertEquals(LinkedDeviceRemoval.Removed, repository.remove(linked))
                assertEquals(HttpStatusCode.Unauthorized, putCapture(watch.tokens.accessToken, CAPTURED_AFTER_REMOVAL).status)
            }
        }

    private suspend fun ServerClientE2EHarness.putCapture(
        accessToken: String,
        id: String,
    ): HttpResponse {
        val now = System.currentTimeMillis()
        return httpClient.put("$baseUrl/contents/$id") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                """{"id":"$id","type":"AUDIO","content":"LDSE2:opaque","mediaUri":null,""" +
                    """"createdAt":$now,"lastUpdated":$now,"deviceId":"pixel-watch-3"}""",
            )
        }
    }

    private suspend fun ServerClientE2EHarness.getCapture(
        accessToken: String,
        id: String,
    ): HttpResponse = httpClient.get("$baseUrl/contents/$id") { header(HttpHeaders.Authorization, "Bearer $accessToken") }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}

private class PhoneSessions(
    private val bound: OriginBoundSession,
) : SessionStorage {
    private val current = MutableStateFlow<UserSession?>(bound.session)

    override fun getSession() = current.value

    override fun getOriginBoundSession() = current.value?.let { bound.copy(session = it) }

    override fun getSessionFlow() = current

    override suspend fun hasValidSession() = current.value != null

    override suspend fun saveSession(session: UserSession) {
        current.value = session
    }

    override suspend fun clearSession() {
        current.value = null
    }
}
