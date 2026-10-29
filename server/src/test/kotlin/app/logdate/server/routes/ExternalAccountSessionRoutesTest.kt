package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.configureAuthV1TestApp
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExternalAccountSessionRoutesTest {
    @Test
    fun `account refresh and profile edits retain the external DID and kind`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val account = account()
            runBlocking { env.accountRepository.create(account) }
            val token = env.tokenService.generateAccessToken(account.id.toString(), account.did)
            val me = client.get("/api/v1/auth/me") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, me.status)
            val view =
                Json
                    .parseToJsonElement(me.bodyAsText())
                    .jsonObject["data"]!!
                    .jsonObject["account"]!!
                    .jsonObject
            assertEquals(DID, view["did"]!!.jsonPrimitive.content)
            assertEquals("external", view["identityKind"]!!.jsonPrimitive.content)
            val profile =
                client.put("/api/v1/auth/me") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"Updated name","bio":"Private bio"}""")
                }
            assertEquals(HttpStatusCode.OK, profile.status)
            val updated = Json.parseToJsonElement(profile.bodyAsText()).jsonObject["data"]!!.jsonObject
            assertEquals("external", updated["identityKind"]!!.jsonPrimitive.content)
            assertEquals(DID, updated["did"]!!.jsonPrimitive.content)
            runBlocking {
                assertEquals(DID, env.accountRepository.findById(account.id)?.did)
                assertNull(env.signingKeyService.findActiveKey(account.id))
                assertNull(env.accountRepository.findById(account.id)?.publicIdentityDid)
            }
        }

    @Test
    fun `external accounts can write and read their sealed personal space without a hosted signing key`() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val account = account()
            runBlocking { env.accountRepository.create(account) }
            val token = env.tokenService.generateAccessToken(account.id.toString(), account.did)
            val space = "at://$DID/space/app.logdate.space.personal/self"
            val ciphertext = Base64.getEncoder().encodeToString(ByteArray(528) { 1 })
            val nonce = Base64.getEncoder().encodeToString(ByteArray(12))
            val sealed =
                """{"${'$'}type":"app.logdate.crypto.sealed","version":1,"keyId":"abcdefghijklm",""" +
                    """"nonce":{"${'$'}bytes":"$nonce"},"ciphertext":{"${'$'}bytes":"$ciphertext"}}"""
            val put =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"space":"$space","rkey":"private-entry","record":$sealed}""")
                }
            assertEquals(HttpStatusCode.OK, put.status)
            val get = client.get("/xrpc/app.logdate.space.getRecord?space=$space&rkey=private-entry") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, get.status)
            assertTrue(get.bodyAsText().contains(ciphertext))
            runBlocking { assertNull(env.signingKeyService.findActiveKey(account.id)) }
        }

    @Test
    fun `unknown identity kinds cannot receive refreshed sessions or access personal space`() =
        testApplication {
            val account = account().copy(identityKind = AccountIdentityKind.UNRECOGNIZED)
            val repository =
                object : AccountRepository by InMemoryAccountRepository() {
                    override suspend fun findById(id: Uuid): Account? = account.takeIf { it.id == id }
                }
            val env = configureAuthV1TestApp(accountRepository = repository, personalSpaceEnabled = true)
            val token = env.tokenService.generateAccessToken(account.id.toString(), account.did)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me") { bearerAuth(token) }.status)
            val refresh = env.tokenService.generateRefreshToken(account.id.toString(), account.did)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .post("/api/v1/auth/token/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"refreshToken":"$refresh"}""")
                    }.status,
            )

            val space = "at://$DID/space/app.logdate.space.personal/self"
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/xrpc/app.logdate.space.listRepoOps?space=$space") { bearerAuth(token) }.status,
            )
            runBlocking { assertNull(env.signingKeyService.findActiveKey(account.id)) }
        }

    @Test
    fun `linked-device refresh also refuses unknown account identity kinds`() =
        testApplication {
            val account = account().copy(identityKind = AccountIdentityKind.UNRECOGNIZED)
            val repository =
                object : AccountRepository by InMemoryAccountRepository() {
                    override suspend fun findById(id: Uuid): Account? = account.takeIf { it.id == id }
                }
            val devices = io.mockk.mockk<app.logdate.server.devices.DeviceLinkService>()
            io.mockk.coEvery { devices.refresh(any()) } returns
                app.logdate.server.devices.DeviceSessionRefresh
                    .Refreshed("sync-token", null)
            val env = configureAuthV1TestApp(accountRepository = repository, deviceLinkService = devices)
            val refresh = env.tokenService.generateDeviceRefreshToken(account.id.toString(), account.did, Uuid.random().toString())
            val response =
                client.post("/api/v1/auth/token/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"refreshToken":"$refresh"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            io.mockk.coVerify(exactly = 0) { devices.refresh(any()) }
        }

    private fun account() =
        Account(
            Uuid.random(),
            "external",
            "External",
            did = DID,
            createdAt = Clock.System.now(),
            storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
            identityKind = AccountIdentityKind.EXTERNAL,
        )

    private companion object {
        const val DID = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"
    }
}
