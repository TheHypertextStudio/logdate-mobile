package app.logdate.server.routes

import app.logdate.server.AuthV1TestEnvironment
import app.logdate.server.bluesky.BlueskyAccountContinuationService
import app.logdate.server.bluesky.BlueskyPasskeyAccountService
import app.logdate.server.bluesky.BlueskyProtocolHarness
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.database.PostgreSQLAccountIdentityRepository
import app.logdate.server.database.PostgreSQLAccountRepository
import app.logdate.server.database.PostgreSQLBlueskyAccountEnrollmentRepository
import app.logdate.server.database.PostgreSQLBlueskyAccountHandoffStore
import app.logdate.server.database.PostgreSQLPasskeyChallengeRepository
import app.logdate.server.database.PostgreSQLPasskeyRepository
import app.logdate.server.database.withBlueskyOAuthDatabase
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyAuthenticationBeginRequest
import app.logdate.shared.model.BlueskyAuthenticationBeginResponse
import app.logdate.shared.model.BlueskyAuthenticationCompleteRequest
import app.logdate.shared.model.BlueskyExchangeRequest
import app.logdate.shared.model.BlueskyExchangeResponse
import app.logdate.shared.model.BlueskyRegistrationBeginRequest
import app.logdate.shared.model.BlueskyRegistrationBeginResponse
import app.logdate.shared.model.BlueskyRegistrationCompleteRequest
import app.logdate.shared.model.PasskeyAuthenticationResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.syntax.Handle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class BlueskyAccountHttpPostgresTest {
    @Test
    fun `provider proof enrolls one passkey account over HTTP and restores its external session`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { provider ->
                val signIn = provider.service()
                val verifier = "app-verifier-with-at-least-forty-three-characters-123456"
                val challenge =
                    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        JvmOAuthCrypto.sha256(verifier.encodeToByteArray()),
                    )
                val start = signIn.start(Handle.require(provider.atmosphere.alice.handle), challenge)
                signIn.callback(provider.atmosphere.approve(start.authorizationUrl))
                val accounts = PostgreSQLAccountRepository()
                val identities = PostgreSQLAccountIdentityRepository()
                val handoffs = PostgreSQLBlueskyAccountHandoffStore(source, AtRestKeyCipher("http-enrollment-test"))
                val webAuthn =
                    WebAuthnPasskeyService(
                        PostgreSQLPasskeyRepository(),
                        origins = setOf("https://logdate.app"),
                        strictVerificationEnabled = true,
                        challengeRepository = PostgreSQLPasskeyChallengeRepository(),
                    )
                val continuations = BlueskyAccountContinuationService(signIn, accounts, handoffs)
                val passkeys =
                    BlueskyPasskeyAccountService(accounts, handoffs, PostgreSQLBlueskyAccountEnrollmentRepository(source), webAuthn)
                lateinit var env: AuthV1TestEnvironment
                testApplication {
                    env =
                        configureAuthV1TestApp(
                            accountRepository = accounts,
                            identityRepository = identities,
                            webAuthnPasskeyService = webAuthn,
                            personalSpaceEnabled = true,
                            additionalAccountRoutes = {
                                blueskyAccountRoutes(continuations, passkeys) { account ->
                                    issueAuthResponse(
                                        account,
                                        accounts,
                                        identities,
                                        webAuthn,
                                        env.atprotoIdentityService,
                                        env.tokenService,
                                        passkeyConfirmed = true,
                                    )
                                }
                            },
                        )
                    val exchange =
                        client.post("$ROOT/exchange") {
                            contentType(ContentType.Application.Json)
                            setBody(Json.encodeToString(BlueskyExchangeRequest(start.attemptId, verifier)))
                        }
                    assertEquals(HttpStatusCode.OK, exchange.status)
                    assertEquals("no-store", exchange.headers["Cache-Control"])
                    assertFalse(exchange.bodyAsText().contains("accessToken"))
                    val continuation = Json.decodeFromString<BlueskyExchangeResponse>(exchange.bodyAsText())
                    assertEquals(BlueskyAccountNextStep.CREATE_ACCOUNT, continuation.nextStep)
                    val owner = Uuid.random()
                    val begin =
                        client.post("$ROOT/registration/begin") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                Json.encodeToString(
                                    BlueskyRegistrationBeginRequest(continuation.continuationToken, owner.toString(), "alice", "Alice"),
                                ),
                            )
                        }
                    assertEquals(HttpStatusCode.OK, begin.status)
                    val options = Json.decodeFromString<BlueskyRegistrationBeginResponse>(begin.bodyAsText())
                    val authenticator = PasskeyTestAuthenticator()
                    val complete =
                        client.post("$ROOT/registration/complete") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                Json.encodeToString(
                                    BlueskyRegistrationCompleteRequest(
                                        options.continuationToken,
                                        authenticator.register(options.options.challenge),
                                    ),
                                ),
                            )
                        }
                    assertEquals(HttpStatusCode.OK, complete.status)
                    val data = Json.parseToJsonElement(complete.bodyAsText()).jsonObject["data"]!!.jsonObject
                    val account = data["account"]!!.jsonObject
                    assertEquals(owner.toString(), account["id"]!!.jsonPrimitive.content)
                    assertEquals(provider.atmosphere.alice.did, account["did"]!!.jsonPrimitive.content)
                    assertEquals("external", account["identityKind"]!!.jsonPrimitive.content)
                    val token = data["tokens"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content
                    val me = client.get("/api/v1/auth/me") { bearerAuth(token) }
                    assertEquals(HttpStatusCode.OK, me.status)
                    assertFalse(me.bodyAsText().contains("stepUpGrant"))
                    assertNull(env.signingKeyService.findActiveKey(owner))
                    val secondStart = signIn.start(Handle.require(provider.atmosphere.alice.handle), challenge)
                    signIn.callback(provider.atmosphere.approve(secondStart.authorizationUrl))
                    val secondExchange =
                        client.post("$ROOT/exchange") {
                            contentType(ContentType.Application.Json)
                            setBody(Json.encodeToString(BlueskyExchangeRequest(secondStart.attemptId, verifier)))
                        }
                    assertEquals(HttpStatusCode.OK, secondExchange.status)
                    val existing = Json.decodeFromString<BlueskyExchangeResponse>(secondExchange.bodyAsText())
                    assertEquals(BlueskyAccountNextStep.CONFIRM_PASSKEY, existing.nextStep)
                    val authBegin =
                        client.post("$ROOT/authentication/begin") {
                            contentType(ContentType.Application.Json)
                            setBody(Json.encodeToString(BlueskyAuthenticationBeginRequest(existing.continuationToken)))
                        }
                    assertEquals(HttpStatusCode.OK, authBegin.status)
                    val authOptions = Json.decodeFromString<BlueskyAuthenticationBeginResponse>(authBegin.bodyAsText())
                    val signed = authenticator.assert(authOptions.options.challenge)
                    val credential =
                        PasskeyAuthenticationResponse(
                            signed.id,
                            signed.rawId,
                            AuthenticatorAssertionResponse(
                                signed.response.clientDataJSON,
                                signed.response.authenticatorData,
                                signed.response.signature,
                                null,
                            ),
                        )
                    val authComplete =
                        client.post("$ROOT/authentication/complete") {
                            contentType(ContentType.Application.Json)
                            setBody(Json.encodeToString(BlueskyAuthenticationCompleteRequest(authOptions.continuationToken, credential)))
                        }
                    assertEquals(HttpStatusCode.OK, authComplete.status)
                    val confirmed =
                        Json
                            .parseToJsonElement(
                                authComplete.bodyAsText(),
                            ).jsonObject["data"]!!
                            .jsonObject["account"]!!
                            .jsonObject
                    assertEquals(owner.toString(), confirmed["id"]!!.jsonPrimitive.content)
                    assertEquals(1, accounts.getAllAccounts().size)
                }
            }
        }

    companion object {
        private const val ROOT = "/api/v1/auth/bluesky"
    }
}
