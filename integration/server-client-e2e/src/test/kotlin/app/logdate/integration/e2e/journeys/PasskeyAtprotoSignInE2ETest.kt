package app.logdate.integration.e2e.journeys

import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.DeviceId
import app.logdate.client.sync.cloud.DraftUploadRequest
import app.logdate.integration.e2e.fixtures.DpopKey
import app.logdate.integration.e2e.fixtures.SyntheticPasskeyAccount
import app.logdate.integration.e2e.fixtures.createPasskeyOnlyAccount
import app.logdate.integration.e2e.fixtures.pkceChallenge
import app.logdate.integration.e2e.harness.ServerClientE2EHarness
import app.logdate.integration.e2e.harness.withServerClientHarness
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A LogDate account that only has a passkey signs in to another AT Protocol app with its LogDate
 * identity through OAuth, and the app can use that identity without reaching the private journal.
 *
 * The other app is a separate server publishing its own client metadata document. It pushes an
 * authorization request, this test plays the person's browser through LogDate's authorization page
 * and passkey ceremony, and the app exchanges the code with PKCE and DPoP and then acts as the
 * account. Nothing here sets or uses a password.
 */
class PasskeyAtprotoSignInE2ETest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a passkey-only account signs in to another AT Protocol app with its LogDate identity`() =
        kotlinx.coroutines.test.runTest {
            withServerClientHarness {
                withThirdPartyApp { app ->
                    val account = apiClient.createPasskeyOnlyAccount("oauth_${Random.nextInt(1000, 9999)}")
                    val did = assertNotNull(account.response.data.account.did)
                    val handle = assertNotNull(account.response.data.account.handle)
                    val logDate = originOf(baseUrl)

                    HttpClient(OkHttp) { followRedirects = false }.use { http ->
                        val passwordAttempt =
                            http.postJson(
                                "$logDate/xrpc/com.atproto.server.createSession",
                                buildJsonObject {
                                    put("identifier", handle)
                                    put("password", "any-password-at-all")
                                },
                            )
                        assertEquals(HttpStatusCode.BadRequest, passwordAttempt.status, "sign-in is passkey-only")

                        val session = http.signInThroughBrowser(logDate, app, account, scope = "atproto transition:generic")
                        assertEquals(did, session.subject)
                        assertEquals("atproto transition:generic", session.scope)

                        // Explicit sign-in does not make the identity discoverable to strangers.
                        val description = http.get("$logDate/xrpc/com.atproto.repo.describeRepo?repo=${session.subject}").json()
                        assertEquals("RepoNotFound", description["error"]?.jsonPrimitive?.content)

                        // Uploads stay closed to apps until a collection open to apps refers to files.
                        val upload =
                            session.post(
                                http,
                                "$logDate/xrpc/com.atproto.repo.uploadBlob",
                                "Posted by Journal Viewer",
                                ContentType.Text.Plain,
                            )
                        assertEquals(HttpStatusCode.Forbidden, upload.status, upload.bodyAsText())
                    }
                }
            }
        }

    @Test
    fun `an app signed in with a passkey never reaches the private journal`() =
        kotlinx.coroutines.test.runTest {
            withServerClientHarness {
                withThirdPartyApp { app ->
                    val account = apiClient.createPasskeyOnlyAccount("oauth_${Random.nextInt(1000, 9999)}")
                    val token = account.response.data.tokens.accessToken
                    val did = assertNotNull(account.response.data.account.did)
                    val logDate = originOf(baseUrl)
                    seedJournal(token)

                    HttpClient(OkHttp) { followRedirects = false }.use { http ->
                        val session = http.signInThroughBrowser(logDate, app, account, scope = "atproto transition:generic")
                        val contentList = "$logDate/xrpc/com.atproto.repo.listRecords?repo=$did&collection=$CONTENT"
                        val draftRecord = "$logDate/xrpc/com.atproto.repo.getRecord?repo=$did&collection=$DRAFT&rkey=$DRAFT_ID"

                        // The account's own LogDate session sees the journal records the app is about to look for.
                        assertEquals(
                            1,
                            http
                                .bearerGet(contentList, token)
                                .json()
                                .getValue("records")
                                .jsonArray.size,
                        )
                        assertEquals(HttpStatusCode.OK, http.bearerGet(draftRecord, token).status)

                        val appList = session.get(http, contentList)
                        assertEquals(HttpStatusCode.OK, appList.status, appList.bodyAsText())
                        assertEquals(
                            0,
                            appList
                                .json()
                                .getValue("records")
                                .jsonArray.size,
                        )

                        val appDraft = session.get(http, draftRecord)
                        assertTrue("RecordNotFound" in appDraft.bodyAsText(), appDraft.bodyAsText())

                        val appDelete =
                            session.post(
                                http,
                                "$logDate/xrpc/com.atproto.repo.deleteRecord",
                                """{"repo":"$did","collection":"$CONTENT","rkey":"$ENTRY_ID"}""",
                            )
                        assertEquals(HttpStatusCode.Forbidden, appDelete.status, appDelete.bodyAsText())
                        val journalLexicon =
                            session.post(
                                http,
                                "$logDate/xrpc/com.atproto.repo.createRecord",
                                """{"repo":"$did","collection":"app.logdate.journal.entry","record":{"text":"written by an app"}}""",
                            )
                        assertEquals(HttpStatusCode.Forbidden, journalLexicon.status, journalLexicon.bodyAsText())
                        val entries = apiClient.getContentChanges(token, 0).getOrThrow().changes
                        assertTrue(entries.any { it.id == ENTRY_ID && !it.isDeleted }, "the entry is still in the journal")

                        val export = session.get(http, "$logDate/xrpc/com.atproto.sync.getRepo?did=${did.encodeURLParameter()}")
                        assertEquals(HttpStatusCode.Forbidden, export.status, export.bodyAsText())

                        val description = http.get("$logDate/xrpc/com.atproto.repo.describeRepo?repo=$did").json()
                        assertEquals("RepoNotFound", description["error"]?.jsonPrimitive?.content)
                        assertFalse(description.containsKey("collections"))
                    }
                }
            }
        }

    @Test
    fun `an app granted only the atproto scope cannot use the repository`() =
        kotlinx.coroutines.test.runTest {
            withServerClientHarness {
                withThirdPartyApp { app ->
                    val account = apiClient.createPasskeyOnlyAccount("oauth_${Random.nextInt(1000, 9999)}")
                    val logDate = originOf(baseUrl)

                    HttpClient(OkHttp) { followRedirects = false }.use { http ->
                        val session = http.signInThroughBrowser(logDate, app, account, scope = "atproto")
                        assertEquals("atproto", session.scope)

                        val upload =
                            session.post(
                                http,
                                "$logDate/xrpc/com.atproto.repo.uploadBlob",
                                "Posted by Journal Viewer",
                                ContentType.Text.Plain,
                            )

                        assertEquals(HttpStatusCode.Forbidden, upload.status, upload.bodyAsText())
                        val did = assertNotNull(account.response.data.account.did)
                        val read = session.get(http, "$logDate/xrpc/com.atproto.repo.listRecords?repo=$did&collection=$CONTENT")
                        assertEquals(HttpStatusCode.Forbidden, read.status, read.bodyAsText())
                        assertTrue("InsufficientScope" in read.bodyAsText(), read.bodyAsText())
                    }
                }
            }
        }

    /** What the app holds after signing in: a DPoP key, its access token and the latest nonce. */
    private inner class AppSession(
        private val dpop: DpopKey,
        private val accessToken: String,
        private val nonce: String,
        val subject: String,
        val scope: String,
    ) {
        suspend fun get(
            http: HttpClient,
            url: String,
        ): HttpResponse =
            http.get(url) {
                header(HttpHeaders.Authorization, "DPoP $accessToken")
                header("DPoP", dpop.proof("GET", publicUrl(url.substringBefore('?')), nonce = nonce, accessToken = accessToken))
            }

        suspend fun post(
            http: HttpClient,
            url: String,
            body: String,
            type: ContentType = ContentType.Application.Json,
        ): HttpResponse =
            http.post(url) {
                header(HttpHeaders.Authorization, "DPoP $accessToken")
                header("DPoP", dpop.proof("POST", publicUrl(url), nonce = nonce, accessToken = accessToken))
                contentType(type)
                setBody(body)
            }
    }

    /**
     * Runs the whole OAuth sign-in: the app pushes a request, the browser opens LogDate's page and
     * signs in with the account's passkey, and the app exchanges the code for DPoP-bound tokens.
     */
    private suspend fun HttpClient.signInThroughBrowser(
        logDate: String,
        app: ThirdPartyApp,
        account: SyntheticPasskeyAccount,
        scope: String,
    ): AppSession {
        val handle = assertNotNull(account.response.data.account.handle)
        val dpop = DpopKey()
        val verifier = randomToken()
        val par =
            submitForm(
                "$logDate/oauth/par",
                parameters {
                    append("client_id", app.clientId)
                    append("redirect_uri", app.redirectUri)
                    append("response_type", "code")
                    append("scope", scope)
                    append("code_challenge", pkceChallenge(verifier))
                    append("code_challenge_method", "S256")
                    append("state", "journal-viewer-state")
                    append("login_hint", handle)
                },
            ) { header("DPoP", dpop.proof("POST", "$PUBLIC_ORIGIN/oauth/par")) }
        assertEquals(HttpStatusCode.Created, par.status, par.bodyAsText())
        val requestUri =
            par
                .json()
                .getValue("request_uri")
                .jsonPrimitive.content
        val nonce = assertNotNull(par.headers["DPoP-Nonce"])

        // The person's browser lands on LogDate's authorization page.
        val page =
            get(
                "$logDate/oauth/authorize?client_id=${app.clientId.encodeURLParameter()}" +
                    "&request_uri=${requestUri.encodeURLParameter()}",
            ) { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
        val pageHtml = page.bodyAsText()
        assertTrue("Sign in to ${app.clientId}" in pageHtml, pageHtml)
        assertTrue("@$handle" in pageHtml, pageHtml)

        // The page runs the passkey ceremony with the account's only credential.
        val options =
            postJson("$logDate/oauth/authorize/passkey/options", buildJsonObject { put("request_uri", requestUri) })
                .also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
                .json()
        // The options never name credentials; the browser offers the person's discoverable passkeys.
        assertEquals(emptyList(), options.getValue("allow_credentials").jsonArray.map { it.jsonPrimitive.content })
        val challenge = options.getValue("challenge").jsonPrimitive.content
        val (clientDataJson, authenticatorData, signature) = account.authenticator.assert(challenge)
        val approval =
            postJson(
                "$logDate/oauth/authorize/passkey",
                buildJsonObject {
                    put("request_uri", requestUri)
                    put("challenge", challenge)
                    putJsonObject("credential") {
                        put("id", account.authenticator.credentialIdB64)
                        put("rawId", account.authenticator.credentialIdB64)
                        put("type", "public-key")
                        putJsonObject("response") {
                            put("clientDataJSON", clientDataJson)
                            put("authenticatorData", authenticatorData)
                            put("signature", signature)
                            put("userHandle", "")
                        }
                    }
                },
            )
        assertEquals(HttpStatusCode.OK, approval.status, approval.bodyAsText())
        val redirect =
            Url(
                approval
                    .json()
                    .getValue("redirect_uri")
                    .jsonPrimitive.content,
            )
        assertEquals(app.redirectUri, redirect.toString().substringBefore('?'))
        assertEquals("journal-viewer-state", redirect.parameters["state"])
        assertEquals("https://logdate.app", redirect.parameters["iss"])
        val code = assertNotNull(redirect.parameters["code"])

        // The app trades the code for DPoP-bound tokens.
        val token =
            submitForm(
                "$logDate/oauth/token",
                parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", app.redirectUri)
                    append("client_id", app.clientId)
                    append("code_verifier", verifier)
                },
            ) { header("DPoP", dpop.proof("POST", "$PUBLIC_ORIGIN/oauth/token", nonce = nonce)) }
        assertEquals(HttpStatusCode.OK, token.status, token.bodyAsText())
        val tokens = token.json()
        assertEquals("DPoP", tokens["token_type"]?.jsonPrimitive?.content)
        return AppSession(
            dpop = dpop,
            accessToken = tokens.getValue("access_token").jsonPrimitive.content,
            nonce = assertNotNull(token.headers["DPoP-Nonce"]),
            subject = tokens.getValue("sub").jsonPrimitive.content,
            scope = tokens.getValue("scope").jsonPrimitive.content,
        )
    }

    /** Writes one journal entry and one draft the way the LogDate apps sync them. */
    private suspend fun ServerClientE2EHarness.seedJournal(token: String) {
        apiClient
            .uploadContent(
                token,
                ContentUploadRequest(
                    id = ENTRY_ID,
                    type = "TEXT",
                    content = "A private entry",
                    mediaUri = null,
                    createdAt = 1000,
                    lastUpdated = 1000,
                ),
            ).getOrThrow()
        apiClient
            .uploadDraft(
                token,
                DraftUploadRequest(
                    id = DRAFT_ID,
                    content = "A private draft",
                    blockTypes = listOf("TEXT"),
                    journalIds = emptyList(),
                    createdAt = 1L,
                    lastUpdated = 1L,
                    deviceId = DeviceId("device-a"),
                ),
            ).getOrThrow()
    }

    private suspend fun HttpResponse.json(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.bearerGet(
        url: String,
        token: String,
    ): HttpResponse = get(url) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.postJson(
        url: String,
        body: JsonObject,
    ): HttpResponse =
        post(url) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

    private class ThirdPartyApp(
        val clientId: String,
        val redirectUri: String,
    )

    /** Runs a stand-in third-party app that serves its OAuth client metadata document over loopback HTTP. */
    private suspend fun <T> withThirdPartyApp(block: suspend (ThirdPartyApp) -> T): T {
        val metadata = AtomicReference<String>()
        val server =
            embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                routing {
                    get("/client-metadata.json") { call.respondText(metadata.get(), ContentType.Application.Json) }
                }
            }
        server.start(wait = false)
        try {
            val port =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val app =
                ThirdPartyApp(
                    clientId = "http://localhost:$port/client-metadata.json",
                    redirectUri = "http://localhost:$port/callback",
                )
            metadata.set(
                buildJsonObject {
                    put("client_id", app.clientId)
                    put("client_name", "Journal Viewer")
                    put("redirect_uris", Json.parseToJsonElement("""["${app.redirectUri}"]"""))
                    put("grant_types", Json.parseToJsonElement("""["authorization_code","refresh_token"]"""))
                    put("response_types", Json.parseToJsonElement("""["code"]"""))
                    put("scope", "atproto transition:generic")
                    put("token_endpoint_auth_method", "none")
                    put("dpop_bound_access_tokens", true)
                }.toString(),
            )
            return block(app)
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
        }
    }

    /** A DPoP proof names the server's configured public address, whatever address the test reaches it at. */
    private fun publicUrl(url: String): String = PUBLIC_ORIGIN + URI(url).rawPath

    private fun originOf(baseUrl: String): String {
        val uri = URI(baseUrl)
        return "${uri.scheme}://${uri.host}:${uri.port}"
    }

    private fun randomToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    private companion object {
        const val PUBLIC_ORIGIN = "https://logdate.app"
        const val CONTENT = "studio.hypertext.logdate.content"
        const val DRAFT = "studio.hypertext.logdate.draft"
        const val ENTRY_ID = "journal-entry-1"
        const val DRAFT_ID = "journal-draft-1"
    }
}
