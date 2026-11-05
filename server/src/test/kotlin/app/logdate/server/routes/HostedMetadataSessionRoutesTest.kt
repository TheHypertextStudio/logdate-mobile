package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.configureAuthV1TestApp
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
class HostedMetadataSessionRoutesTest {
    @Test fun incompleteHostedMetadataKeepsPrivateAccountRefreshAndProfileAccess() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val owner = owner()
            runBlocking { assertTrue(env.accountRepository.create(owner)) }
            val refresh = env.tokenService.generateRefreshToken(owner.id.toString(), DID)
            val response =
                client.post("/api/v1/auth/token/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"refreshToken":"$refresh"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
            val access = data["accessToken"]!!.jsonPrimitive.content
            val me = client.get("/api/v1/auth/me") { bearerAuth(access) }
            assertEquals(HttpStatusCode.OK, me.status)
            val view =
                Json
                    .parseToJsonElement(me.bodyAsText())
                    .jsonObject["data"]!!
                    .jsonObject["account"]!!
                    .jsonObject
            assertEquals(DID, view["did"]!!.jsonPrimitive.content)
            val profile =
                client.put("/api/v1/auth/me") {
                    bearerAuth(access)
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"Updated owner"}""")
                }
            assertEquals(HttpStatusCode.OK, profile.status)
            runBlocking {
                assertEquals(owner.copy(displayName = "Updated owner"), env.accountRepository.findById(owner.id))
                assertNull(env.signingKeyService.findActiveKey(owner.id))
            }
        }

    @Test fun incompleteHostedMetadataDoesNotBlockSealedJournalReadsAndWrites() =
        testApplication {
            val env = configureAuthV1TestApp(personalSpaceEnabled = true)
            val owner = owner()
            runBlocking { assertTrue(env.accountRepository.create(owner)) }
            val token = env.tokenService.generateAccessToken(owner.id.toString(), DID)
            val space = "at://$DID/space/app.logdate.space.personal/self"
            val ciphertext = Base64.getEncoder().encodeToString(ByteArray(528) { 1 })
            val nonce = Base64.getEncoder().encodeToString(ByteArray(12))
            val record =
                """{"${'$'}type":"app.logdate.crypto.sealed","version":1,"keyId":"abcdefghijklm",""" +
                    """"nonce":{"${'$'}bytes":"$nonce"},"ciphertext":{"${'$'}bytes":"$ciphertext"}}"""
            val put =
                client.post("/xrpc/app.logdate.space.putRecord") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"space":"$space","rkey":"private-entry","record":$record}""")
                }
            assertEquals(HttpStatusCode.OK, put.status)
            val get = client.get("/xrpc/app.logdate.space.getRecord?space=$space&rkey=private-entry") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, get.status)
            assertTrue(get.bodyAsText().contains(ciphertext))
            runBlocking {
                assertEquals(owner, env.accountRepository.findById(owner.id))
                assertNull(env.signingKeyService.findActiveKey(owner.id))
            }
        }

    private fun owner(): Account {
        val id = Uuid.random()
        return Account(
            id,
            id.toHexString(),
            "Private owner",
            did = DID,
            createdAt = Clock.System.now(),
            storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
        )
    }

    private companion object {
        const val DID = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
