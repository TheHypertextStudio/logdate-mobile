package app.logdate.client.networking

import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.CompleteAuthenticationRequest
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyAssertionAuthenticatorResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountIdentityDecodingTest {
    @Test
    fun `external identities survive passkey sign in restore google and account refresh`() =
        runTest {
            val assertion =
                CompleteAuthenticationRequest(
                    challenge = "challenge",
                    credential =
                        PasskeyAssertionResponse(
                            "credential",
                            "credential",
                            PasskeyAssertionAuthenticatorResponse("client", "auth", "sig", ""),
                        ),
                )
            val reads: List<Pair<String, suspend (PasskeyApiClient) -> LogDateAccount>> =
                listOf(
                    "/auth/me" to { it.getAccountInfo("access").getOrThrow() },
                    "/auth/signin/passkey/complete" to { it.completeAuthentication(assertion).getOrThrow().account },
                    "/auth/restore/complete" to { it.completeRestoreSignIn(assertion).getOrThrow().account },
                    "/auth/signin/google" to { it.signInWithGoogle("fake-google-proof").getOrThrow().account },
                )
            for ((field, expected) in listOf(
                "\"identityKind\":\"external\"," to AccountIdentityKind.EXTERNAL,
                "\"identityKind\":\"hosted\"," to AccountIdentityKind.HOSTED,
                "\"identityKind\":\"futureKind\"," to AccountIdentityKind.UNRECOGNIZED,
                "" to AccountIdentityKind.UNRECOGNIZED,
            )) {
                for ((path, read) in reads) {
                    val transport =
                        HttpClient(
                            MockEngine { request ->
                                assertEquals("/api/v1$path", request.url.encodedPath)
                                respond(response(field), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                            },
                        ) { install(ContentNegotiation) { json() } }
                    try {
                        assertEquals(expected, read(PasskeyApiClient(transport, TestConfigRepository())).identityKind, "$path $field")
                    } finally {
                        transport.close()
                    }
                }
            }
        }

    private fun response(field: String) =
        """
        {"success":true,"data":{"account":{
          "id":"550e8400-e29b-41d4-a716-446655440001","username":"alice","displayName":"Alice",
          $field
          "storageMode":"personalSpaceV1","createdAt":"2026-03-05T00:00:00Z","updatedAt":"2026-03-05T00:00:00Z"
        },"tokens":{"accessToken":"access","refreshToken":"refresh"}}}
        """.trimIndent()
}
