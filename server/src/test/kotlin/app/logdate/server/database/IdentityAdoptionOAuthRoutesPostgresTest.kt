@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AccountModeration
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.bluesky.BlueskySignInProvider
import app.logdate.server.bluesky.BlueskySignInService
import app.logdate.server.bluesky.VerifiedBlueskyCallback
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthService
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionOAuthExchangeRequest
import app.logdate.shared.model.identity.IdentityAdoptionOAuthStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionOAuthStartResponse
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionStatusResponse
import io.ktor.client.request.bearerAuth
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
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Handle
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityAdoptionOAuthRoutesPostgresTest {
    @Test fun browserProofCreatesOnlyTheOriginalAccountProposalAndReplaysItsLostResponse() = exercise(Scenario.JOURNEY)

    @Test fun anonymousStartCannotAllocateProviderState() = exercise(Scenario.ANONYMOUS)

    @Test fun linkedDeviceStartCannotAllocateProviderState() = exercise(Scenario.LINKED)

    @Test fun defaultDisabledStartCannotAllocateProviderState() = exercise(Scenario.DISABLED)

    @Test fun changedEpochRefusesBeforeProviderIo() = exercise(Scenario.WRONG_EPOCH)

    @Test fun changedDidRefusesBeforeProviderIo() = exercise(Scenario.WRONG_DID)

    @Test fun moderatedAccountRefusesBeforeProviderIo() = exercise(Scenario.MODERATED)

    @Test fun aStaleAccountReadCannotBypassTheTransactionalSourceCheck() = exercise(Scenario.STALE_READ)

    @Test fun malformedChallengeCannotAllocateProviderState() = exercise(Scenario.INVALID_CHALLENGE)

    @Test fun malformedDeviceCannotAllocateProviderState() = exercise(Scenario.INVALID_DEVICE)

    @Test fun malformedJsonCannotAllocateProviderState() = exercise(Scenario.BAD_JSON)

    @Test fun wrongContentTypeCannotAllocateProviderState() = exercise(Scenario.WRONG_TYPE)

    @Test fun oversizedBodyCannotAllocateProviderState() = exercise(Scenario.OVERSIZED)

    @Test fun providerFailureNeverRevealsPrivateDiagnosticsOrChangesAuthority() = exercise(Scenario.PROVIDER_FAILURE)

    @Test fun repeatedStartsAreBoundedPerAccount() = exercise(Scenario.RATE_LIMIT)

    @Test fun anotherVerifierCannotClaimTheProposal() = exercise(Scenario.BAD_VERIFIER)

    @Test fun anotherAccountCannotClaimTheProposal() = exercise(Scenario.FOREIGN_EXCHANGE)

    @Test fun expiredProviderProofCannotClaimTheProposal() = exercise(Scenario.EXPIRED_EXCHANGE)

    @Test fun anAccountChangeAfterTheBrowserOpensCannotClaimTheProposal() = exercise(Scenario.SOURCE_CHANGED_EXCHANGE)

    private enum class Scenario {
        JOURNEY,
        ANONYMOUS,
        LINKED,
        DISABLED,
        WRONG_EPOCH,
        WRONG_DID,
        MODERATED,
        STALE_READ,
        INVALID_CHALLENGE,
        INVALID_DEVICE,
        BAD_JSON,
        WRONG_TYPE,
        OVERSIZED,
        PROVIDER_FAILURE,
        RATE_LIMIT,
        BAD_VERIFIER,
        FOREIGN_EXCHANGE,
        EXPIRED_EXCHANGE,
        SOURCE_CHANGED_EXCHANGE,
    }

    private fun exercise(scenario: Scenario) =
        withIdentityAdoptionAuthorization {
            val auth = this
            source.blueskyTransaction {
                prepareStatement("DELETE FROM identity_adoption_operations WHERE id = ?").use {
                    it.setString(1, operation.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val owner = assertNotNull(PostgreSQLAccountRepository().findById(operation.binding.accountId))
            val provider = Provider(scenario == Scenario.PROVIDER_FAILURE)
            val attempts = PostgreSQLBlueskySignInAttemptStore(source, AtRestKeyCipher("synthetic-browser-secret"), clock)
            val service =
                IdentityAdoptionOAuthService(provider, attempts, operations, PostgreSQLIdentityAdoptionBrowserCancellation(source), clock)
            val tokens = JwtTokenService("synthetic-adoption-browser-token-secret")
            val accounts = PostgreSQLAccountRepository()
            var race = scenario == Scenario.STALE_READ
            val guardedAccounts: AccountRepository =
                object : AccountRepository by accounts {
                    override suspend fun findById(id: Uuid) =
                        accounts.findById(id).also {
                            if (race && id == owner.id) {
                                race = false
                                auth.advanceEpoch()
                            }
                        }
                }
            if (scenario == Scenario.MODERATED) {
                source.blueskyTransaction {
                    prepareStatement("UPDATE accounts SET moderation = ? WHERE id = ?").use {
                        it.setString(1, AccountModeration.SUSPENDED.wireValue)
                        it.setObject(2, owner.id.toJavaUUID())
                        it.executeUpdate()
                    }
                }
            }
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.LINKED -> tokens.generateDeviceAccessToken(owner.id.toString(), owner.did, "linked")
                    else -> tokens.generateAccessToken(owner.id.toString())
                }
            val foreign =
                if (scenario == Scenario.FOREIGN_EXCHANGE) {
                    createTestAccount(Uuid.random().toJavaUUID(), "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
                } else {
                    null
                }
            val verifier = "v".repeat(64)
            val proofBytes = MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(proofBytes)
            var request =
                IdentityAdoptionOAuthStartRequest(
                    "target.example",
                    challenge,
                    "device-a",
                    requireNotNull(owner.did),
                    0,
                )
            request =
                when (scenario) {
                    Scenario.WRONG_EPOCH -> request.copy(sourceEpoch = 1)
                    Scenario.WRONG_DID -> request.copy(sourceDid = "did:web:other.example")
                    Scenario.INVALID_CHALLENGE -> request.copy(challenge = "bad")
                    Scenario.INVALID_DEVICE -> request.copy(deviceId = "\u0000")
                    Scenario.OVERSIZED -> request.copy(handle = "x".repeat(70 * 1024))
                    else -> request
                }
            val attemptCount = count("bluesky_sign_in_attempts")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        route("/api/v1") {
                            identityAdoptionRoutes(
                                accounts = guardedAccounts,
                                tokens = tokens,
                                repository = operations,
                                metrics = AuthMetricsRegistry(),
                                enabled = scenario != Scenario.DISABLED,
                                oauth = service,
                            )
                        }
                    }
                }

                suspend fun start() =
                    client.post("/api/v1/account/identity-adoption/begin") {
                        if (token != null) bearerAuth(token)
                        contentType(if (scenario == Scenario.WRONG_TYPE) ContentType.Text.Plain else ContentType.Application.Json)
                        setBody(if (scenario == Scenario.BAD_JSON) "{" else Json.encodeToString(request))
                    }
                if (scenario == Scenario.RATE_LIMIT) repeat(10) { assertEquals(HttpStatusCode.OK, start().status) }
                val response = start()
                val expected =
                    when (scenario) {
                        Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                        Scenario.LINKED, Scenario.MODERATED -> HttpStatusCode.Forbidden
                        Scenario.DISABLED -> HttpStatusCode.NotFound
                        Scenario.WRONG_EPOCH, Scenario.WRONG_DID, Scenario.STALE_READ -> HttpStatusCode.Conflict
                        Scenario.INVALID_CHALLENGE,
                        Scenario.INVALID_DEVICE,
                        Scenario.BAD_JSON,
                        Scenario.WRONG_TYPE,
                        -> HttpStatusCode.BadRequest
                        Scenario.OVERSIZED -> HttpStatusCode.PayloadTooLarge
                        Scenario.PROVIDER_FAILURE -> HttpStatusCode.ServiceUnavailable
                        Scenario.RATE_LIMIT -> HttpStatusCode.TooManyRequests
                        else -> HttpStatusCode.OK
                    }
                assertEquals(expected, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val body = response.bodyAsText()
                assertFalse(body.contains("synthetic provider token"))
                if (expected != HttpStatusCode.OK) {
                    val calls =
                        when (scenario) {
                            Scenario.RATE_LIMIT -> 10
                            Scenario.PROVIDER_FAILURE -> 1
                            else -> 0
                        }
                    assertEquals(calls, provider.starts)
                    assertEquals(attemptCount + calls, count("bluesky_sign_in_attempts"))
                } else {
                    val started = Json.decodeFromString<IdentityAdoptionOAuthStartResponse>(body)
                    assertTrue(started.success)
                    val data = started.data
                    assertNotNull(Uuid.parseOrNull(data.proposalId))
                    assertEquals(owner.id.toString(), data.accountId)
                    assertEquals(request.sourceDid, data.sourceDid)
                    assertEquals(0L, data.sourceEpoch)
                    assertEquals(request.deviceId, data.deviceId)
                    assertEquals(clock.now() + 300.seconds, Instant.parse(data.expiresAt))
                    assertEquals(provider.attempt, data.attemptId)
                    assertEquals("https://provider.example/authorize", data.authorizationUrl)
                    assertEquals(0L, count("identity_adoption_operations"))
                    BlueskySignInService(provider, attempts, clock).callback(emptyMap())
                    assertNull(attempts.consume(data.attemptId, verifier))
                    val exchangeBody =
                        IdentityAdoptionOAuthExchangeRequest(
                            proposalId = data.proposalId,
                            attemptId = data.attemptId,
                            verifier = if (scenario == Scenario.BAD_VERIFIER) "z".repeat(64) else verifier,
                            deviceId = request.deviceId,
                            sourceDid = request.sourceDid,
                            sourceEpoch = request.sourceEpoch,
                        )
                    if (scenario == Scenario.EXPIRED_EXCHANGE) clock.time += 301.seconds
                    if (scenario == Scenario.SOURCE_CHANGED_EXCHANGE) auth.advanceEpoch()
                    val exchangeToken = if (foreign != null) tokens.generateAccessToken(foreign.id.toString()) else requireNotNull(token)

                    suspend fun exchange() =
                        client.post("/api/v1/account/identity-adoption/exchange") {
                            bearerAuth(exchangeToken)
                            contentType(ContentType.Application.Json)
                            setBody(Json.encodeToString(exchangeBody))
                        }
                    val exchanged = exchange()
                    val exchangeStatus =
                        when (scenario) {
                            Scenario.BAD_VERIFIER, Scenario.EXPIRED_EXCHANGE -> HttpStatusCode.BadRequest
                            Scenario.FOREIGN_EXCHANGE, Scenario.SOURCE_CHANGED_EXCHANGE -> HttpStatusCode.Conflict
                            else -> HttpStatusCode.OK
                        }
                    assertEquals(exchangeStatus, exchanged.status)
                    if (exchangeStatus == HttpStatusCode.OK) {
                        val proposed = Json.decodeFromString<IdentityAdoptionStatusResponse>(exchanged.bodyAsText())
                        assertEquals(data.proposalId, proposed.data.operation.id)
                        assertEquals(owner.id.toString(), proposed.data.operation.binding.accountId)
                        assertEquals(provider.target.toString(), proposed.data.operation.binding.targetDid)
                        assertEquals(IdentityAdoptionPhaseView.PREPARING, proposed.data.operation.phase)
                        assertNull(proposed.data.receipt)
                        val replay = Json.decodeFromString<IdentityAdoptionStatusResponse>(exchange().bodyAsText())
                        assertEquals(proposed, replay)
                        assertEquals(1L, count("identity_adoption_operations"))
                    } else {
                        assertEquals(0L, count("identity_adoption_operations"))
                    }
                }
                assertEquals(owner.did, accounts.findById(owner.id)?.did)
                assertEquals(0L, count("identity_adoption_authorizations"))
                assertEquals(0L, count("identity_adoption_staged_records"))
                assertEquals(0L, count("identity_adoption_receipts"))
            }
        }

    private class Provider(
        private val fail: Boolean,
    ) : BlueskySignInProvider {
        val target = AtprotoDid.require("did:web:verified.example")
        var starts = 0
        var attempt: String? = null

        override suspend fun authorize(
            handle: Handle,
            attemptId: String,
        ): String {
            starts++
            attempt = attemptId
            if (fail) error("synthetic provider token must stay private")
            return "https://provider.example/authorize"
        }

        override suspend fun callback(
            parameters: Map<String, String>,
            acceptIdentity: suspend (VerifiedBlueskyCallback) -> Unit,
        ) {
            acceptIdentity(VerifiedBlueskyCallback(target, attempt))
        }
    }

    private suspend fun IdentityAdoptionAuthorizedFixture.count(table: String): Long =
        source.blueskyTransaction {
            createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use {
                    check(it.next())
                    it.getLong(1)
                }
            }
        }

    private suspend fun IdentityAdoptionAuthorizedFixture.advanceEpoch() =
        source.blueskyTransaction {
            prepareStatement("UPDATE accounts SET identity_epoch = identity_epoch + 1 WHERE id = ?").use {
                it.setObject(1, operation.binding.accountId.toJavaUUID())
                it.executeUpdate()
            }
        }
}
