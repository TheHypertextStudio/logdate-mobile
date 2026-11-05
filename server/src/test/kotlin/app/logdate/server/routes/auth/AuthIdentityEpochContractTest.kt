@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes.auth

import app.logdate.server.auth.AccessTokenClaims
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.configureAuthV1TestApp
import app.logdate.shared.model.CloudAccount
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.toCloudAccount
import app.logdate.util.UuidSerializer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AuthIdentityEpochContractTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            serializersModule = SerializersModule { contextual(Uuid::class, UuidSerializer) }
        }

    @Test
    fun `account refresh sends the explicit canonical epoch including zero`() {
        for (epoch in listOf(0L, 4L)) {
            testApplication {
                val account = account(epoch)
                val accounts = mockk<AccountRepository>(relaxed = true)
                coEvery { accounts.findById(account.id) } returns account
                configureAuthV1TestApp(accountRepository = accounts, tokenService = tokens(account))
                val response = client.get("/api/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer synthetic-token") }
                assertEquals(HttpStatusCode.OK, response.status)
                val data =
                    json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject
                        .getValue("data")
                        .jsonObject
                assertEquals(
                    epoch.toString(),
                    data
                        .getValue("account")
                        .jsonObject["identityEpoch"]
                        ?.jsonPrimitive
                        ?.content,
                )
            }
        }
    }

    @Test
    fun `profile updates cannot replace the canonical epoch`() =
        testApplication {
            var account = account(4)
            val accounts = mockk<AccountRepository>(relaxed = true)
            coEvery { accounts.findById(account.id) } answers { account }
            coEvery { accounts.save(any()) } coAnswers { firstArg<Account>().also { account = it } }
            configureAuthV1TestApp(accountRepository = accounts, tokenService = tokens(account))
            val response =
                client.put("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer synthetic-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"Updated name","identityEpoch":999}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val data =
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("data")
                    .jsonObject
            assertEquals("4", data["identityEpoch"]?.jsonPrimitive?.content)
            assertEquals(4L, account.identityEpoch)
        }

    @Test
    fun `shared account decoding and conversion retain the epoch without guessing missing values`() {
        val common =
            """
            "id":"11111111-1111-4111-8111-111111111111",
            "username":"synthetic","displayName":"Synthetic",
            "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"
            """.trimIndent()
        val known = json.decodeFromString<LogDateAccount>("{$common,\"identityEpoch\":4}")
        val encoded = json.parseToJsonElement(json.encodeToString(LogDateAccount.serializer(), known)).jsonObject
        assertEquals("4", encoded["identityEpoch"]?.jsonPrimitive?.content)
        val convertedBytes = json.encodeToString(CloudAccount.serializer(), known.toCloudAccount())
        val converted = json.parseToJsonElement(convertedBytes).jsonObject
        assertEquals("4", converted["identityEpoch"]?.jsonPrimitive?.content)
        val unknown = json.decodeFromString<LogDateAccount>("{$common}")
        val oldBytes = json.encodeToString(CloudAccount.serializer(), unknown.toCloudAccount())
        val old = json.parseToJsonElement(oldBytes).jsonObject
        assertTrue(old["identityEpoch"] == null || old["identityEpoch"] == JsonNull)
    }

    private fun account(epoch: Long) =
        Account(
            id = Uuid.random(),
            username = "synthetic",
            displayName = "Synthetic",
            did = "did:web:synthetic.logdate.app",
            handle = "synthetic.logdate.app",
            signingKeyPublic = "zSyntheticKey",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            identityEpoch = epoch,
        )

    private fun tokens(account: Account): JwtTokenService =
        mockk<JwtTokenService>(relaxed = true).also {
            every { it.validateAccessToken("synthetic-token") } returns account.id.toString()
            every { it.decodeAccessToken(any()) } returns null
            every { it.decodeAccessToken("synthetic-token") } returns AccessTokenClaims.full(account.id.toString(), account.did)
            every { it.decodeRefreshToken(any()) } returns null
        }
}
