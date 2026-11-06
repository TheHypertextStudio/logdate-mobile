package app.logdate.server.routes

import app.logdate.server.AuthV1TestEnvironment
import app.logdate.server.auth.Account
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.oauth.createDpopProof
import app.logdate.server.oauth.generateP256KeyPair
import app.logdate.server.oauth.publicJwk
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What an app signed in through OAuth may do with the account's repository: never anything in the
 * LogDate journal, writes only with `transition:generic`, and no repository export.
 */
@OptIn(ExperimentalUuidApi::class)
class OAuthXrpcAccessTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `service auth requires a public identity and a grant that permits remote methods`() =
        testApplication {
            val fixture = setUp()
            val path = "/xrpc/com.atproto.server.getServiceAuth?aud=did:web:api.bsky.app%23bsky_appview"
            val profile = "$path&lxm=app.bsky.actor.getProfile"

            assertEquals(HttpStatusCode.Unauthorized, client.get(profile).status)
            assertEquals(HttpStatusCode.Forbidden, fixture.oauthGet(profile, GENERIC).status)
            assertTrue(
                runBlocking {
                    fixture.env.accountRepository.recordPublicIdentity(fixture.account.id, requireNotNull(fixture.account.did))
                },
            )
            assertEquals(HttpStatusCode.Forbidden, fixture.oauthGet(profile, ATPROTO).status)
            assertEquals(HttpStatusCode.Forbidden, fixture.oauthGet("$path&lxm=com.atproto.server.deleteAccount", GENERIC).status)
            assertEquals(HttpStatusCode.Forbidden, fixture.oauthGet("$path&lxm=chat.bsky.convo.sendMessage", GENERIC).status)
            assertEquals(HttpStatusCode.BadRequest, fixture.oauthGet("$profile&exp=not-a-number", GENERIC).status)

            val issued = fixture.oauthGet(profile, GENERIC)
            assertEquals(HttpStatusCode.OK, issued.status, issued.bodyAsText())
            assertEquals("no-store", issued.headers[HttpHeaders.CacheControl])
            val payload = json.parseToJsonElement(issued.bodyAsText()).jsonObject
            val token = payload.getValue("token").jsonPrimitive.content
            assertEquals(3, token.split('.').size)
        }

    @Test
    fun `public accounts keep their own Bluesky preferences on this server`() =
        testApplication {
            val fixture = setUp()
            val get = "/xrpc/app.bsky.actor.getPreferences"
            val put = "/xrpc/app.bsky.actor.putPreferences"
            val empty = Json.parseToJsonElement("""{"preferences":[]}""")
            val preferences = """{"preferences":[{"${'$'}type":"app.bsky.actor.defs#feedViewPref","feed":"home","hideReplies":true}]}"""
            assertEquals(HttpStatusCode.Unauthorized, client.get(get).status)
            assertEquals(HttpStatusCode.Forbidden, fixture.oauthGet(get, GENERIC).status)

            val other =
                runBlocking {
                    check(fixture.env.accountRepository.recordPublicIdentity(fixture.account.id, requireNotNull(fixture.account.did)))
                    val saved =
                        fixture.env.accountRepository.save(
                            Account(
                                id = Uuid.random(),
                                username = "bob",
                                displayName = "Bob",
                                createdAt = Clock.System.now(),
                            ),
                        )
                    fixture.env.atprotoIdentityService.ensureIdentity(saved).also {
                        check(fixture.env.accountRepository.recordPublicIdentity(it.id, requireNotNull(it.did)))
                    }
                }
            assertEquals(HttpStatusCode.Forbidden, fixture.oauthPost(put, ATPROTO, preferences).status)
            assertEquals(HttpStatusCode.Forbidden, fixture.oauthGet(get, ATPROTO).status)
            assertEquals(empty, Json.parseToJsonElement(fixture.oauthGet(get, GENERIC).bodyAsText()))
            assertEquals(HttpStatusCode.NoContent, fixture.oauthPost(put, GENERIC, preferences).status)
            assertEquals(Json.parseToJsonElement(preferences), Json.parseToJsonElement(fixture.oauthGet(get, GENERIC).bodyAsText()))
            assertEquals(HttpStatusCode.BadRequest, fixture.oauthPost(put, GENERIC, """{"preferences":[{}]}""").status)
            val details = """{"preferences":[{"${'$'}type":"app.bsky.actor.defs#personalDetailsPref"}]}"""
            assertEquals(HttpStatusCode.BadRequest, fixture.oauthPost(put, GENERIC, details).status)
            val oversized = """{"preferences":["${"x".repeat(256 * 1024)}"]}"""
            assertEquals(HttpStatusCode.PayloadTooLarge, fixture.oauthPost(put, GENERIC, oversized).status)
            assertEquals(Json.parseToJsonElement(preferences), Json.parseToJsonElement(fixture.oauthGet(get, GENERIC).bodyAsText()))
            val otherToken = fixture.env.tokenService.generateAccessToken(other.id.toString())
            val otherPreferences = client.get(get) { header(HttpHeaders.Authorization, "Bearer $otherToken") }
            assertEquals(empty, Json.parseToJsonElement(otherPreferences.bodyAsText()))
            assertEquals(HttpStatusCode.NoContent, fixture.oauthPost(put, GENERIC, empty.toString()).status)
            assertEquals(empty, Json.parseToJsonElement(fixture.oauthGet(get, GENERIC).bodyAsText()))
        }

    @Test
    fun `an OAuth app can read its session identity without email details`() =
        testApplication {
            val fixture = setUp()
            val path = "/xrpc/com.atproto.server.getSession"

            val response = fixture.oauthGet(path, ATPROTO)

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(listOf(fixture.env.oauthNonceService.currentNonce()), response.headers.getAll("DPoP-Nonce"))
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(fixture.account.did, payload.getValue("did").jsonPrimitive.content)
            assertEquals(fixture.account.handle, payload.getValue("handle").jsonPrimitive.content)
            assertEquals("true", payload.getValue("active").jsonPrimitive.content)
            for (field in listOf("email", "emailConfirmed", "emailAuthFactor")) {
                assertFalse(payload.containsKey(field), field)
            }

            val legacySession = runBlocking { fixture.env.atprotoSessionTokenService.createSession(fixture.account) }
            val legacy = client.get(path) { header(HttpHeaders.Authorization, "Bearer ${legacySession.accessJwt}") }
            assertEquals(HttpStatusCode.OK, legacy.status, legacy.bodyAsText())
            val legacyPayload = json.parseToJsonElement(legacy.bodyAsText()).jsonObject
            assertEquals("alice@example.com", legacyPayload.getValue("email").jsonPrimitive.content)
        }

    @Test
    fun `getSession requires the OAuth proof and rejects a deactivated account`() =
        testApplication {
            val fixture = setUp()
            val path = "/xrpc/com.atproto.server.getSession"

            val stale = fixture.oauthGet(path, ATPROTO, nonce = "stale")
            assertEquals(HttpStatusCode.Unauthorized, stale.status)
            assertEquals("DPoP error=\"use_dpop_nonce\"", stale.headers[HttpHeaders.WWWAuthenticate])
            assertEquals(fixture.env.oauthNonceService.currentNonce(), stale.headers["DPoP-Nonce"])
            assertEquals(HttpStatusCode.OK, fixture.oauthGet(path, ATPROTO, nonce = stale.headers["DPoP-Nonce"]!!).status)

            val missing = fixture.oauthGet(path, ATPROTO, includeProof = false)
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertEquals("DPoP error=\"invalid_dpop_proof\"", missing.headers[HttpHeaders.WWWAuthenticate])

            runBlocking { fixture.env.accountRepository.deactivateAccount(fixture.account.id) }
            assertEquals(HttpStatusCode.Unauthorized, fixture.oauthGet(path, ATPROTO).status)
        }

    @Test
    fun `journal collections look empty to an OAuth app but not to the owner`() =
        testApplication {
            val fixture = setUp()

            val ownerList = fixture.bearerGet("/xrpc/com.atproto.repo.listRecords?repo=$HANDLE&collection=$CONTENT")
            val appList = fixture.oauthGet("/xrpc/com.atproto.repo.listRecords?repo=$HANDLE&collection=$CONTENT", GENERIC)

            assertEquals(1, ownerList.records())
            assertEquals(HttpStatusCode.OK, appList.status)
            assertEquals(0, appList.records())
        }

    @Test
    fun `a journal record is not found for an OAuth app`() =
        testApplication {
            val fixture = setUp()
            val path = "/xrpc/com.atproto.repo.getRecord?repo=$HANDLE&collection=$CONTENT&rkey=entry-1"

            assertEquals(HttpStatusCode.OK, fixture.bearerGet(path).status)
            val appRead = fixture.oauthGet(path, GENERIC)

            assertEquals(HttpStatusCode.BadRequest, appRead.status)
            assertTrue("RecordNotFound" in appRead.bodyAsText(), appRead.bodyAsText())
        }

    @Test
    fun `an OAuth app cannot delete a journal record`() =
        testApplication {
            val fixture = setUp()

            val delete =
                fixture.oauthPost(
                    "/xrpc/com.atproto.repo.deleteRecord",
                    GENERIC,
                    """{"repo":"$HANDLE","collection":"$CONTENT","rkey":"entry-1"}""",
                )

            assertEquals(HttpStatusCode.Forbidden, delete.status)
            assertEquals(
                HttpStatusCode.OK,
                fixture.bearerGet("/xrpc/com.atproto.repo.getRecord?repo=$HANDLE&collection=$CONTENT&rkey=entry-1").status,
            )
        }

    @Test
    fun `the atproto scope alone does not allow writes or uploads`() =
        testApplication {
            val fixture = setUp()

            val create =
                fixture.oauthPost(
                    "/xrpc/com.atproto.repo.createRecord",
                    ATPROTO,
                    """{"repo":"$HANDLE","collection":"com.example.note","record":{"text":"hi"}}""",
                )
            val upload = fixture.oauthPost("/xrpc/com.atproto.repo.uploadBlob", ATPROTO, "hello", ContentType.Text.Plain)

            assertEquals(HttpStatusCode.Forbidden, create.status)
            assertTrue("InsufficientScope" in create.bodyAsText(), create.bodyAsText())
            assertEquals(HttpStatusCode.Forbidden, upload.status)
        }

    @Test
    fun `uploads stay closed to OAuth apps until a collection open to apps refers to files`() =
        testApplication {
            val fixture = setUp()

            val upload = fixture.oauthPost("/xrpc/com.atproto.repo.uploadBlob", GENERIC, "hello", ContentType.Text.Plain)
            val ownerUpload = fixture.bearerPost("/xrpc/com.atproto.repo.uploadBlob", "hello", ContentType.Text.Plain)

            assertEquals(HttpStatusCode.Forbidden, upload.status, upload.bodyAsText())
            assertEquals(HttpStatusCode.OK, ownerUpload.status, ownerUpload.bodyAsText())
        }

    @Test
    fun `the atproto scope alone cannot read the repository`() =
        testApplication {
            val fixture = setUp()

            val read = fixture.oauthGet("/xrpc/com.atproto.repo.listRecords?repo=$HANDLE&collection=com.example.note", ATPROTO)

            assertEquals(HttpStatusCode.Forbidden, read.status)
            assertTrue("InsufficientScope" in read.bodyAsText(), read.bodyAsText())
        }

    @Test
    fun `repository export commit status and blob reads are closed to OAuth apps`() =
        testApplication {
            val fixture = setUp()
            val did = fixture.account.did!!
            val paths =
                listOf(
                    "/xrpc/com.atproto.sync.getRepo?did=$did",
                    "/xrpc/com.atproto.sync.getLatestCommit?did=$did",
                    "/xrpc/com.atproto.sync.getRepoStatus?did=$did",
                    "/xrpc/com.atproto.sync.getBlob?did=$did&cid=bafkreigh2akiscaildcqabsyg3dfr6chu3fgpregiymsck7e7aqa4s52zy",
                )

            paths.forEach { path ->
                val response = fixture.oauthGet(path, GENERIC)
                assertEquals(HttpStatusCode.Forbidden, response.status, "$path: ${response.bodyAsText()}")
            }
            assertEquals(HttpStatusCode.OK, fixture.bearerGet("/xrpc/com.atproto.sync.getLatestCommit?did=$did").status)
        }

    @Test
    fun `a deactivated account's OAuth token stops working`() =
        testApplication {
            val fixture = setUp()
            runBlocking { fixture.env.accountRepository.deactivateAccount(fixture.account.id) }

            val upload = fixture.oauthPost("/xrpc/com.atproto.repo.uploadBlob", GENERIC, "hello", ContentType.Text.Plain)

            assertEquals(HttpStatusCode.Unauthorized, upload.status)
        }

    @Test
    fun `describing a private repository reveals no identity or journal collections`() =
        testApplication {
            setUp()

            val description = client.get("/xrpc/com.atproto.repo.describeRepo?repo=$HANDLE")

            assertEquals(HttpStatusCode.BadRequest, description.status)
            val payload = json.parseToJsonElement(description.bodyAsText()).jsonObject
            assertEquals("RepoNotFound", payload["error"]?.jsonPrimitive?.content)
            assertFalse(payload.containsKey("did"))
            assertFalse(payload.containsKey("collections"))
        }

    private class Fixture(
        val test: ApplicationTestBuilder,
        val env: AuthV1TestEnvironment,
        val account: Account,
        val bearer: String,
    ) {
        private val dpopKey = generateP256KeyPair()

        suspend fun bearerGet(path: String): HttpResponse = test.client.get(path) { header(HttpHeaders.Authorization, "Bearer $bearer") }

        suspend fun bearerPost(
            path: String,
            body: String,
            type: ContentType,
        ): HttpResponse =
            test.client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                contentType(type)
                setBody(body)
            }

        suspend fun oauthGet(
            path: String,
            scope: String,
            includeProof: Boolean = true,
            nonce: String = env.oauthNonceService.currentNonce(),
        ): HttpResponse {
            val token = accessToken(scope)
            return test.client.get(path) {
                header(HttpHeaders.Authorization, "DPoP $token")
                if (includeProof) header("DPoP", proof("GET", path, token, nonce))
            }
        }

        suspend fun oauthPost(
            path: String,
            scope: String,
            body: String,
            type: ContentType = ContentType.Application.Json,
        ): HttpResponse {
            val token = accessToken(scope)
            return test.client.post(path) {
                header(HttpHeaders.Authorization, "DPoP $token")
                header("DPoP", proof("POST", path, token))
                contentType(type)
                setBody(body)
            }
        }

        private fun accessToken(scope: String): String =
            env.oauthAccessTokenService
                .issueAccessToken(
                    subjectDid = account.did!!,
                    clientId = "https://viewer.example.com/client.json",
                    scope = scope,
                    keyThumbprint = env.oauthDpopVerifier.jwkThumbprint(publicJwk(dpopKey)),
                ).token

        private fun proof(
            method: String,
            path: String,
            token: String,
            nonce: String = env.oauthNonceService.currentNonce(),
        ): String =
            createDpopProof(
                keyPair = dpopKey,
                method = method,
                htu = "http://localhost${path.substringBefore('?')}",
                nonce = nonce,
                ath = env.oauthDpopVerifier.accessTokenHash(token),
            )
    }

    private suspend fun HttpResponse.records(): Int =
        json
            .parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("records")
            .jsonArray.size

    private suspend fun ApplicationTestBuilder.setUp(): Fixture {
        val env = configureAuthV1TestApp()
        val account =
            runBlocking {
                val saved =
                    env.accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "alice",
                            displayName = "Alice",
                            email = "alice@example.com",
                            createdAt = Clock.System.now(),
                        ),
                    )
                env.atprotoIdentityService.ensureIdentity(saved)
            }
        val bearer = env.tokenService.generateAccessToken(account.id.toString())
        val created =
            client.post("/xrpc/com.atproto.repo.createRecord") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $bearer")
                setBody(
                    """
                    {
                      "repo": "$HANDLE",
                      "collection": "$CONTENT",
                      "rkey": "entry-1",
                      "record": {
                        "${'$'}type": "$CONTENT",
                        "type": "TEXT",
                        "content": "a private journal entry",
                        "createdAt": 1,
                        "lastUpdated": 1
                      }
                    }
                    """.trimIndent(),
                )
            }
        check(created.status == HttpStatusCode.OK) { "Seeding the journal failed: ${created.bodyAsText()}" }
        return Fixture(this, env, account, bearer)
    }

    private companion object {
        const val HANDLE = "alice.logdate.app"
        const val CONTENT = "studio.hypertext.logdate.content"
        const val ATPROTO = "atproto"
        const val GENERIC = "atproto transition:generic"
    }
}
