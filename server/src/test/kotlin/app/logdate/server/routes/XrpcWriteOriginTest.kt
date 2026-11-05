package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.identity.adoption.AccountWriteOrigin
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.pds.PdsRepoService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class XrpcWriteOriginTest {
    @Test
    fun createCarriesTheAuthenticatedAccountOrigin() = checkOrigin("createRecord")

    @Test
    fun putCarriesTheAuthenticatedAccountOrigin() = checkOrigin("putRecord")

    @Test
    fun deleteCarriesTheAuthenticatedAccountOrigin() = checkOrigin("deleteRecord")

    private fun checkOrigin(method: String) =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val identity =
                AtprotoIdentityService(
                    accounts,
                    SigningKeyService(InMemorySigningKeyRepository(), "origin-test-kek"),
                    AtprotoIdentityConfig("logdate.test", "https://logdate.test"),
                )
            val id = Uuid.random()
            val account =
                identity.ensureIdentity(
                    accounts.save(Account(id, "origin", "Owner", createdAt = Clock.System.now())),
                )
            val tokens = JwtTokenService("origin-test-secret")
            val service = mockk<PdsRepoService>()
            var seen: IdentityWriteExpectation? = null
            var calls = 0
            coEvery { service.createRecord(any()) } coAnswers {
                calls++
                seen = currentCoroutineContext()[AccountWriteOrigin]?.expectation
                Result.failure(IllegalStateException("refused test write"))
            }
            coEvery { service.putRecord(any()) } coAnswers {
                calls++
                seen = currentCoroutineContext()[AccountWriteOrigin]?.expectation
                Result.failure(IllegalStateException("refused test write"))
            }
            coEvery { service.deleteRecord(any()) } coAnswers {
                calls++
                seen = currentCoroutineContext()[AccountWriteOrigin]?.expectation
                Result.failure(IllegalStateException("refused test write"))
            }
            application {
                install(ContentNegotiation) { json(Json) }
                routing {
                    xrpcRoutes(identity, accountRepository = accounts, tokenService = tokens, repoService = service)
                }
            }
            client.post("/xrpc/com.atproto.repo.$method") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${tokens.generateAccessToken(id.toString())}")
                val record = if (method == "deleteRecord") "" else ",\"record\":{\"type\":\"TEXT\"}"
                setBody("""{"repo":"${account.did}","collection":"studio.hypertext.logdate.content","rkey":"entry"$record}""")
            }
            assertEquals(1, calls, "The request must reach the repository mutation")
            assertEquals(IdentityWriteExpectation(id, 0), seen)
        }
}
